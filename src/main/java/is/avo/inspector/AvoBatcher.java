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
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
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

    private final Sender sender;
    private final int batchSize;
    private final int maxQueueSize;
    private final long flushMillis;

    private final Object lock = new Object();
    // Guarded by lock.
    private final ArrayDeque<Map<String, Object>> buffer = new ArrayDeque<>();
    private long generation;
    private boolean destroyed;

    @Nullable private final ScheduledThreadPoolExecutor timer;
    private final ThreadPoolExecutor sendExecutor;
    private final Set<Future<AvoNetworkCallsHandler.SendResult>> inFlight =
            Collections.newSetFromMap(new ConcurrentHashMap<Future<AvoNetworkCallsHandler.SendResult>, Boolean>());

    AvoBatcher(@NotNull Sender sender, int batchSize, double batchFlushSeconds, int maxQueueSize, boolean disableBatchTimer) {
        this.sender = sender;
        this.batchSize = batchSize;
        this.maxQueueSize = maxQueueSize;
        this.flushMillis = Math.max(1L, (long) (batchFlushSeconds * 1000.0));

        // Daemon threads: neither the timer nor a pending send holds the JVM open (SPEC.md §11.4).
        if (!disableBatchTimer && batchSize > 1) {
            timer = new ScheduledThreadPoolExecutor(1, daemonThreads("avo-inspector-flush-timer"));
            timer.setRemoveOnCancelPolicy(true);
        } else {
            timer = null;
        }
        sendExecutor = new ThreadPoolExecutor(SEND_THREADS, SEND_THREADS, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(), daemonThreads("avo-inspector-send"));
        sendExecutor.allowCoreThreadTimeOut(true);
    }

    /**
     * Appends one event and fires the size trigger. Returns the sends this call dispatched, which
     * is the event's own send when {@code batchSize == 1}.
     */
    List<Future<AvoNetworkCallsHandler.SendResult>> enqueue(@NotNull Map<String, Object> event) {
        List<Map<String, Object>> batch = null;
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
                batch = swap();
            } else if (buffer.size() == 1 && timer != null) {
                armTimer(generation);
            }
        }

        if (dropped > 0 && AvoInspector.isLogging()) {
            System.err.println("Avo Inspector: maxQueueSize exceeded; dropped " + dropped + " oldest event(s).");
        }
        return batch != null ? dispatch(batch) : Collections.<Future<AvoNetworkCallsHandler.SendResult>>emptyList();
    }

    /** Sends everything buffered, then waits for every in-flight send or the timeout. Never throws. */
    void flush(long timeoutMs) {
        List<Map<String, Object>> batch;
        synchronized (lock) {
            if (destroyed) {
                return;
            }
            batch = buffer.isEmpty() ? null : swap();
        }
        if (batch != null) {
            dispatch(batch);
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
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
            destroyed = true;
            buffer.clear();
            generation++;
        }
        if (timer != null) {
            timer.shutdownNow();
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
        return timer != null && !timer.isShutdown();
    }

    // Caller holds lock. The atomic swap-and-clear of SPEC.md §3.1.
    private List<Map<String, Object>> swap() {
        List<Map<String, Object>> batch = new ArrayList<>(buffer);
        buffer.clear();
        generation++;
        return batch;
    }

    // Caller holds lock. One-shot flush of this buffer generation once its oldest event is due.
    private void armTimer(final long armedGeneration) {
        try {
            timer.schedule(new Runnable() {
                @Override
                public void run() {
                    List<Map<String, Object>> batch;
                    synchronized (lock) {
                        if (destroyed || generation != armedGeneration || buffer.isEmpty()) {
                            return;
                        }
                        batch = swap();
                    }
                    dispatch(batch);
                }
            }, flushMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Destroyed concurrently.
        }
    }

    // Outside the lock. apiKey is a header, so events for different AvoInspectorTargets travel in
    // separate requests: one per (apiKey, appName), each keeping enqueue order.
    private List<Future<AvoNetworkCallsHandler.SendResult>> dispatch(List<Map<String, Object>> batch) {
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

        List<Future<AvoNetworkCallsHandler.SendResult>> sends = new ArrayList<>();
        for (Map.Entry<List<Object>, List<Map<String, Object>>> partition : partitions.entrySet()) {
            final String apiKey = String.valueOf(partition.getKey().get(0));
            final List<Map<String, Object>> events = partition.getValue();
            FutureTask<AvoNetworkCallsHandler.SendResult> send = new FutureTask<AvoNetworkCallsHandler.SendResult>(
                    new Callable<AvoNetworkCallsHandler.SendResult>() {
                        @Override
                        public AvoNetworkCallsHandler.SendResult call() {
                            return sender.send(events, apiKey);
                        }
                    }) {
                @Override
                protected void done() {
                    inFlight.remove(this);
                }
            };
            // Tracked before it can run, so done() always finds it.
            inFlight.add(send);
            try {
                sendExecutor.execute(send);
                sends.add(send);
            } catch (RejectedExecutionException e) {
                // Destroyed concurrently: the batch is abandoned.
                inFlight.remove(send);
            }
        }
        return sends;
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
