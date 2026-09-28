package is.avo.inspector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
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

    private static final int SEND_THREADS = 4;

    // Every live batcher, weakly held so an instance that is never destroyed can still be
    // collected. One JVM shutdown hook flushes them all at exit (normal exit or SIGTERM; not
    // SIGKILL or Runtime.halt). It runs only once the JVM is already shutting down, so it never
    // holds the process open (SPEC.md §3.4).
    private static final Set<AvoBatcher> liveBatchers = Collections.newSetFromMap(new WeakHashMap<AvoBatcher, Boolean>());
    private static boolean shutdownHookInstalled;

    private final Sender sender;
    private final int batchSize;
    private final int maxQueueSize;
    private final long flushMillis;

    private final Object lock = new Object();
    // Guarded by lock.
    private final ArrayDeque<Map<String, Object>> buffer = new ArrayDeque<>();
    private long generation;
    private boolean destroyed;

    // One daemon timer thread for all instances, so an idle instance holds no timer thread.
    private static final ScheduledThreadPoolExecutor sharedTimer = newSharedTimer();

    private final boolean timerEnabled;
    // Guarded by lock: the pending scheduled flush, cancelled on destroy().
    @Nullable private ScheduledFuture<?> pendingTimer;
    // Guarded by lock. Test-only count of scheduled flushes.
    int timerArmsForTesting;
    private final ThreadPoolExecutor sendExecutor;
    // Test-only: runs after a size-triggered swap, outside the lock, before the batch is sent.
    @Nullable volatile Runnable afterSwapForTesting;

    private final Object sendQueueLock = new Object();
    // Guarded by sendQueueLock: events in sends waiting for a send thread.
    private int queuedEvents;

    private final Set<Future<AvoNetworkCallsHandler.SendResult>> inFlight =
            Collections.newSetFromMap(new ConcurrentHashMap<Future<AvoNetworkCallsHandler.SendResult>, Boolean>());

    AvoBatcher(@NotNull Sender sender, int batchSize, double batchFlushSeconds, int maxQueueSize, boolean disableBatchTimer) {
        this.sender = sender;
        this.batchSize = batchSize;
        this.maxQueueSize = maxQueueSize;
        this.flushMillis = Math.max(1L, (long) (batchFlushSeconds * 1000.0));

        // Daemon threads: neither the timer nor a pending send holds the JVM open (SPEC.md §11.4).
        timerEnabled = !disableBatchTimer && batchSize > 1;
        // Send threads exit after 5 idle seconds, so an idle instance soon holds no thread. The
        // queue itself is bounded by queued events in submit(), not by its capacity.
        sendExecutor = new ThreadPoolExecutor(SEND_THREADS, SEND_THREADS, 5, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(), daemonThreads("avo-inspector-send"));
        sendExecutor.allowCoreThreadTimeOut(true);

        registerForShutdownFlush(this);
    }

    private static void registerForShutdownFlush(AvoBatcher batcher) {
        synchronized (liveBatchers) {
            if (!shutdownHookInstalled) {
                try {
                    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                        @Override
                        public void run() {
                            flushAllAtShutdown(AvoInspector.DEFAULT_FLUSH_TIMEOUT_MS);
                        }
                    }, "avo-inspector-shutdown-flush"));
                    shutdownHookInstalled = true;
                } catch (IllegalStateException | SecurityException e) {
                    // Already shutting down, or hooks are not permitted: rely on explicit flush().
                }
            }
            liveBatchers.add(batcher);
        }
    }

    static boolean isRegisteredForShutdownFlush(AvoBatcher batcher) {
        synchronized (liveBatchers) {
            return liveBatchers.contains(batcher);
        }
    }

    // Sends every live batcher's buffer, then waits for all their sends, within one shared timeout.
    static void flushAllAtShutdown(long timeoutMs) {
        List<AvoBatcher> batchers;
        synchronized (liveBatchers) {
            batchers = new ArrayList<>(liveBatchers);
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
        synchronized (liveBatchers) {
            liveBatchers.remove(this);
        }
        synchronized (lock) {
            destroyed = true;
            buffer.clear();
            generation++;
            if (pendingTimer != null) {
                pendingTimer.cancel(false);
                pendingTimer = null;
            }
        }
        sendExecutor.shutdownNow();
        for (Future<AvoNetworkCallsHandler.SendResult> send : inFlight) {
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
                }
            }, flushMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // The shared timer never shuts down; nothing to do if it ever refuses.
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

    // Outside the lock: the HTTP send runs on the send executor. Sends waiting for a thread hold
    // at most maxQueueSize events in total, so an outage cannot pile them up without limit; past
    // that the oldest waiting sends are dropped (at-most-once, like the maxQueueSize bound). A
    // flush split across several targets fits, because one flush never exceeds maxQueueSize.
    private List<Future<AvoNetworkCallsHandler.SendResult>> submit(List<SendTask> sends) {
        List<Future<AvoNetworkCallsHandler.SendResult>> submitted = new ArrayList<>(sends.size());
        int dropped = 0;
        synchronized (sendQueueLock) {
            for (SendTask send : sends) {
                send.markQueued();
                try {
                    sendExecutor.execute(send);
                    submitted.add(send);
                } catch (RejectedExecutionException e) {
                    // Destroyed concurrently: the batch is abandoned.
                    send.unqueue();
                    send.cancel(false);
                }
            }
            while (queuedEvents > maxQueueSize) {
                Runnable oldest = sendExecutor.getQueue().poll();
                if (oldest == null) {
                    break;
                }
                SendTask oldestSend = (SendTask) oldest;
                if (oldestSend.unqueue()) {
                    dropped += oldestSend.eventCount;
                }
                oldestSend.cancel(false);
            }
        }
        if (dropped > 0 && AvoInspector.isLogging()) {
            System.err.println("Avo Inspector: send queue full; dropped " + dropped + " oldest event(s).");
        }
        return submitted;
    }

    private final class SendTask extends FutureTask<AvoNetworkCallsHandler.SendResult> {
        final int eventCount;

        // Guarded by sendQueueLock: counted in queuedEvents while waiting for a send thread.
        private boolean queued;

        SendTask(final String apiKey, final List<Map<String, Object>> events) {
            super(new Callable<AvoNetworkCallsHandler.SendResult>() {
                @Override
                public AvoNetworkCallsHandler.SendResult call() {
                    return sender.send(events, apiKey);
                }
            });
            this.eventCount = events.size();
        }

        // Caller holds sendQueueLock.
        void markQueued() {
            queued = true;
            queuedEvents += eventCount;
        }

        // Stops counting this send as waiting; false if it was not.
        boolean unqueue() {
            synchronized (sendQueueLock) {
                if (!queued) {
                    return false;
                }
                queued = false;
                queuedEvents -= eventCount;
                return true;
            }
        }

        @Override
        public void run() {
            unqueue();
            super.run();
        }

        @Override
        protected void done() {
            inFlight.remove(this);
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

    private static ScheduledThreadPoolExecutor newSharedTimer() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, daemonThreads("avo-inspector-flush-timer"));
        executor.setRemoveOnCancelPolicy(true);
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
