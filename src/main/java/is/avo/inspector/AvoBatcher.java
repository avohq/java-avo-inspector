package is.avo.inspector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

// The pending batch buffer and its lifecycle (SPEC.md §12, §4.5, §4.6).
class AvoBatcher {

    interface Sender {
        AvoNetworkCallsHandler.SendResult send(List<Map<String, Object>> events, String apiKey);
    }

    // Sends one instance may have running at once; later ones wait in order.
    static final int MAX_IN_FLIGHT_SENDS = 4;
    // One bounded pool runs the sends of every instance, so creating many instances cannot
    // multiply threads until thread creation fails.
    static final int SHARED_SEND_THREADS = 16;
    private static final ThreadPoolExecutor sharedSendPool = newSharedSendPool();
    // Test-only replacement for the shared pool (e.g. one that fails to start a thread).
    @Nullable static volatile java.util.concurrent.Executor sendExecutorForTesting;

    // Every batcher with buffered or in-flight events, held strongly so pending events are never
    // lost to garbage collection; a batcher leaves once drained, so an idle instance stays
    // collectable. One JVM shutdown hook flushes them all at exit (normal exit or SIGTERM; not
    // SIGKILL or Runtime.halt). It runs only once the JVM is already shutting down, so it never
    // holds the process open (SPEC.md §3.4).
    private static final Set<AvoBatcher> busyBatchers = Collections.newSetFromMap(new IdentityHashMap<AvoBatcher, Boolean>());
    private static boolean shutdownHookInstalled;
    // Present only while some batcher has work, so an idle JVM (or a redeployed webapp) holds no
    // hook, and through it no thread or class loader.
    @Nullable private static Thread shutdownHook;
    // Guarded by busyBatchers: the pending check that removes the hook if the registry is still
    // empty, so back-to-back sends (every event in dev) don't add and remove it each time.
    @Nullable private static ScheduledFuture<?> hookRemovalCheck;
    private static final long HOOK_REMOVAL_DELAY_MS = 5_000;
    // Guarded by busyBatchers. Test-only counts of hook registrations and removals.
    static int hookAddsForTesting;
    static int hookRemovalsForTesting;

    private final Sender sender;
    private final int batchSize;
    private final int maxQueueSize;
    private final long flushMillis;

    private final Object lock = new Object();
    // Guarded by lock.
    private final ArrayDeque<Map<String, Object>> buffer = new ArrayDeque<>();
    private long generation;
    private boolean destroyed;
    // Guarded by lock: whether this batcher is in busyBatchers.
    private boolean registered;

    // One daemon timer thread for all instances, so an idle instance holds no timer thread.
    private static final ScheduledThreadPoolExecutor sharedTimer = newSharedTimer();

    private final boolean timerEnabled;
    // Guarded by lock: the pending scheduled flush, cancelled on destroy().
    @Nullable private ScheduledFuture<?> pendingTimer;
    // Guarded by lock. Test-only count of scheduled flushes.
    int timerArmsForTesting;
    // Test-only: runs after a size-triggered swap, outside the lock, before the batch is sent.
    @Nullable volatile Runnable afterSwapForTesting;

    private final Object sendQueueLock = new Object();
    // Guarded by sendQueueLock: sends waiting for one of this instance's send slots, the events
    // they hold, and how many of its sends are running.
    private final ArrayDeque<SendTask> waiting = new ArrayDeque<>();
    private int queuedEvents;
    private int running;

    private final Set<Future<AvoNetworkCallsHandler.SendResult>> inFlight =
            Collections.newSetFromMap(new ConcurrentHashMap<Future<AvoNetworkCallsHandler.SendResult>, Boolean>());

    AvoBatcher(@NotNull Sender sender, int batchSize, double batchFlushSeconds, int maxQueueSize, boolean disableBatchTimer) {
        this.sender = sender;
        this.batchSize = batchSize;
        this.maxQueueSize = maxQueueSize;
        this.flushMillis = Math.max(1L, (long) (batchFlushSeconds * 1000.0));

        // Daemon threads: neither the timer nor a pending send holds the JVM open (SPEC.md §11.4).
        timerEnabled = !disableBatchTimer && batchSize > 1;
    }

    private static void registerForShutdownFlush(AvoBatcher batcher) {
        synchronized (busyBatchers) {
            if (!shutdownHookInstalled) {
                Thread hook = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        flushAllAtShutdown(AvoInspector.DEFAULT_FLUSH_TIMEOUT_MS);
                    }
                }, "avo-inspector-shutdown-flush");
                try {
                    Runtime.getRuntime().addShutdownHook(hook);
                    hookAddsForTesting++;
                    shutdownHook = hook;
                    shutdownHookInstalled = true;
                } catch (IllegalStateException | SecurityException e) {
                    // Already shutting down, or hooks are not permitted: rely on explicit flush().
                }
            }
            busyBatchers.add(batcher);
        }
    }

    // immediately: remove the hook now if nothing is left (destroy()); otherwise only if the
    // registry is still empty at a check shortly after, which absorbs back-to-back sends.
    private static void unregisterFromShutdownFlush(AvoBatcher batcher, boolean immediately) {
        synchronized (busyBatchers) {
            busyBatchers.remove(batcher);
            if (!busyBatchers.isEmpty() || !shutdownHookInstalled) {
                return;
            }
            if (immediately) {
                removeShutdownHookIfIdle();
            } else if (hookRemovalCheck == null) {
                try {
                    hookRemovalCheck = sharedTimer.schedule(new Runnable() {
                        @Override
                        public void run() {
                            synchronized (busyBatchers) {
                                hookRemovalCheck = null;
                                removeShutdownHookIfIdle();
                            }
                        }
                    }, HOOK_REMOVAL_DELAY_MS, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    removeShutdownHookIfIdle();
                }
            }
        }
    }

    // Caller holds busyBatchers.
    private static void removeShutdownHookIfIdle() {
        if (hookRemovalCheck != null) {
            hookRemovalCheck.cancel(false);
            hookRemovalCheck = null;
        }
        if (!busyBatchers.isEmpty() || !shutdownHookInstalled) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
            hookRemovalsForTesting++;
            shutdownHook = null;
            shutdownHookInstalled = false;
        } catch (IllegalStateException | SecurityException e) {
            // Shutdown in progress: the hook is already running.
        }
    }

    // {hook registrations, hook removals}, read under the lock that guards the writes.
    static int[] hookCountsForTesting() {
        synchronized (busyBatchers) {
            return new int[]{hookAddsForTesting, hookRemovalsForTesting};
        }
    }

    static boolean isShutdownHookInstalledForTesting() {
        synchronized (busyBatchers) {
            return shutdownHookInstalled;
        }
    }

    static boolean isRegisteredForShutdownFlush(AvoBatcher batcher) {
        synchronized (busyBatchers) {
            return busyBatchers.contains(batcher);
        }
    }

    // Caller holds lock. Leaves the shutdown registry once nothing is buffered or in flight.
    private void releaseIfDrained() {
        if (registered && buffer.isEmpty() && inFlight.isEmpty()) {
            registered = false;
            unregisterFromShutdownFlush(this, false);
        }
    }

    // Sends every live batcher's buffer, then waits for all their sends, within one shared timeout.
    static void flushAllAtShutdown(long timeoutMs) {
        List<AvoBatcher> batchers;
        synchronized (busyBatchers) {
            batchers = new ArrayList<>(busyBatchers);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        for (AvoBatcher batcher : batchers) {
            batcher.sendBuffered();
        }
        for (AvoBatcher batcher : batchers) {
            batcher.awaitInFlight(deadline);
        }
    }

    /**
     * Appends one event and fires the size trigger. Returns the sends this call dispatched, which
     * is the event's own send when {@code batchSize == 1}.
     */
    List<Future<AvoNetworkCallsHandler.SendResult>> enqueue(@NotNull Map<String, Object> event) {
        List<SendTask> sends = null;
        int dropped = 0;
        synchronized (lock) {
            if (destroyed) {
                return Collections.emptyList();
            }
            while (buffer.size() >= maxQueueSize) {
                buffer.pollFirst();
                dropped++;
            }
            buffer.addLast(event);
            if (!registered) {
                registered = true;
                registerForShutdownFlush(this);
            }
            if (buffer.size() >= batchSize) {
                sends = prepare(swap());
            } else if (timerEnabled && pendingTimer == null) {
                armTimer(generation);
            }
        }

        if (dropped > 0 && AvoInspector.isLogging()) {
            System.err.println("Avo Inspector: maxQueueSize exceeded; dropped " + dropped + " oldest event(s).");
        }
        Runnable afterSwap = afterSwapForTesting;
        if (sends != null && afterSwap != null) {
            afterSwap.run();
        }
        return sends != null ? submit(sends) : Collections.<Future<AvoNetworkCallsHandler.SendResult>>emptyList();
    }

    /** Sends everything buffered, then waits for every in-flight send or the timeout. Never throws. */
    void flush(long timeoutMs) {
        if (!sendBuffered()) {
            return;
        }
        awaitInFlight(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs)));
    }

    // Returns false once destroyed.
    private boolean sendBuffered() {
        List<SendTask> sends;
        synchronized (lock) {
            if (destroyed) {
                return false;
            }
            sends = buffer.isEmpty() ? null : prepare(swap());
        }
        if (sends != null) {
            submit(sends);
        }
        return true;
    }

    private void awaitInFlight(long deadline) {
        for (Future<AvoNetworkCallsHandler.SendResult> send : new ArrayList<>(inFlight)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            await(send, remaining);
        }
    }

    /** Discards the buffer, stops the timer and abandons in-flight sends. */
    void destroy() {
        synchronized (lock) {
            registered = false;
            unregisterFromShutdownFlush(this, true);
            destroyed = true;
            buffer.clear();
            generation++;
            if (pendingTimer != null) {
                pendingTimer.cancel(false);
                pendingTimer = null;
            }
        }
        synchronized (sendQueueLock) {
            waiting.clear();
            queuedEvents = 0;
        }
        for (Future<AvoNetworkCallsHandler.SendResult> send : new ArrayList<>(inFlight)) {
            send.cancel(true);
        }
        inFlight.clear();
    }

    int pendingCount() {
        return inFlight.size();
    }

    int bufferedCount() {
        synchronized (lock) {
            return buffer.size();
        }
    }

    boolean isTimerRunning() {
        synchronized (lock) {
            return timerEnabled && !destroyed;
        }
    }

    // Caller holds lock. The atomic swap-and-clear of SPEC.md §3.1.
    private List<Map<String, Object>> swap() {
        List<Map<String, Object>> batch = new ArrayList<>(buffer);
        buffer.clear();
        generation++;
        // The pending flush was for the events just swapped out.
        if (pendingTimer != null) {
            pendingTimer.cancel(false);
            pendingTimer = null;
        }
        return batch;
    }

    // Caller holds lock. One-shot flush of this buffer generation once its oldest event is due.
    private void armTimer(final long armedGeneration) {
        timerArmsForTesting++;
        try {
            pendingTimer = sharedTimer.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        List<SendTask> sends;
                        synchronized (lock) {
                            if (destroyed || generation != armedGeneration) {
                                return;
                            }
                            pendingTimer = null;
                            if (buffer.isEmpty()) {
                                return;
                            }
                            sends = prepare(swap());
                        }
                        submit(sends);
                    } catch (Throwable e) {
                        Util.logInternalError(e);
                    }
                }
            }, flushMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            // No timer thread (e.g. thread creation failed): the events wait for the size trigger
            // or flush().
            Util.logInternalError(e);
        }
    }

    // Caller holds lock, so a swapped-out batch is in flight before the lock is released and a
    // concurrent flush() waits for it. apiKey is a header, so events for different
    // AvoInspectorTargets travel in separate requests: one per (apiKey, appName), each keeping
    // enqueue order.
    private List<SendTask> prepare(List<Map<String, Object>> batch) {
        Map<List<Object>, List<Map<String, Object>>> partitions = new LinkedHashMap<>();
        for (Map<String, Object> event : batch) {
            List<Object> key = Arrays.asList(event.get("apiKey"), event.get("appName"));
            List<Map<String, Object>> partition = partitions.get(key);
            if (partition == null) {
                partition = new ArrayList<>();
                partitions.put(key, partition);
            }
            partition.add(event);
        }

        List<SendTask> sends = new ArrayList<>(partitions.size());
        for (Map.Entry<List<Object>, List<Map<String, Object>>> partition : partitions.entrySet()) {
            SendTask send = new SendTask(String.valueOf(partition.getKey().get(0)), partition.getValue());
            inFlight.add(send);
            sends.add(send);
        }
        return sends;
    }

    // Outside the lock: the HTTP send runs on the shared send pool, at most MAX_IN_FLIGHT_SENDS of
    // this instance's sends at once. Sends waiting for a slot hold at most maxQueueSize events;
    // past that the oldest waiting sends are dropped (at-most-once, like the maxQueueSize bound).
    private List<Future<AvoNetworkCallsHandler.SendResult>> submit(List<SendTask> sends) {
        int dropped = 0;
        synchronized (sendQueueLock) {
            for (SendTask send : sends) {
                waiting.addLast(send);
                queuedEvents += send.eventCount;
            }
            while (queuedEvents > maxQueueSize && !waiting.isEmpty()) {
                SendTask oldest = waiting.pollFirst();
                queuedEvents -= oldest.eventCount;
                dropped += oldest.eventCount;
                oldest.cancel(false);
            }
        }
        if (dropped > 0 && AvoInspector.isLogging()) {
            System.err.println("Avo Inspector: send queue full; dropped " + dropped + " oldest event(s).");
        }
        pump();
        return new ArrayList<Future<AvoNetworkCallsHandler.SendResult>>(sends);
    }

    // Starts waiting sends while this instance has a free slot. A send the pool cannot start (for
    // example when no thread can be created) is dropped rather than left pending forever.
    private void pump() {
        while (true) {
            SendTask next;
            synchronized (sendQueueLock) {
                if (running >= MAX_IN_FLIGHT_SENDS || waiting.isEmpty()) {
                    return;
                }
                next = waiting.pollFirst();
                queuedEvents -= next.eventCount;
                running++;
            }
            try {
                java.util.concurrent.Executor override = sendExecutorForTesting;
                (override != null ? override : sharedSendPool).execute(new SendRunner(next));
            } catch (Throwable e) {
                synchronized (sendQueueLock) {
                    running--;
                }
                next.cancel(false);
                Util.logInternalError(e);
            }
        }
    }

    // Runs one send and frees its slot, whatever happens.
    private final class SendRunner implements Runnable {
        private final SendTask send;

        SendRunner(SendTask send) {
            this.send = send;
        }

        @Override
        public void run() {
            try {
                send.run();
            } finally {
                synchronized (sendQueueLock) {
                    running--;
                }
                pump();
            }
        }
    }

    private final class SendTask extends FutureTask<AvoNetworkCallsHandler.SendResult> {
        final int eventCount;

        SendTask(final String apiKey, final List<Map<String, Object>> events) {
            super(new Callable<AvoNetworkCallsHandler.SendResult>() {
                @Override
                public AvoNetworkCallsHandler.SendResult call() {
                    return sender.send(events, apiKey);
                }
            });
            this.eventCount = events.size();
        }

        @Override
        protected void done() {
            try {
                inFlight.remove(this);
                synchronized (lock) {
                    releaseIfDrained();
                }
            } catch (Throwable e) {
                Util.logInternalError(e);
            }
        }
    }

    @Nullable
    static AvoNetworkCallsHandler.SendResult await(Future<AvoNetworkCallsHandler.SendResult> send, long timeoutNanos) {
        try {
            return send.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | CancellationException ignored) {
        }
        return null;
    }

    // Threads exit after 5 idle seconds, so an idle JVM holds no send thread.
    private static ThreadPoolExecutor newSharedSendPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(SHARED_SEND_THREADS, SHARED_SEND_THREADS, 5, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(), daemonThreads("avo-inspector-send"));
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static ScheduledThreadPoolExecutor newSharedTimer() {
        return newSharedScheduler("avo-inspector-flush-timer");
    }

    // A shared single-thread scheduler whose thread exits after 5 idle seconds (it stays while a
    // task is scheduled), so an idle JVM holds no Avo Inspector thread.
    static ScheduledThreadPoolExecutor newSharedScheduler(String name) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, daemonThreads(name));
        executor.setRemoveOnCancelPolicy(true);
        executor.setKeepAliveTime(5, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    static ThreadFactory daemonThreads(final String name) {
        final AtomicInteger count = new AtomicInteger();
        return new ThreadFactory() {
            @Override
            public Thread newThread(@NotNull Runnable runnable) {
                Thread thread = new Thread(runnable, name + "-" + count.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
