package is.avo.inspector;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// AvoBatcher against an in-memory sender: concurrency, bounds and lifecycle.
public class AvoBatcherTests {

    private final List<List<Map<String, Object>>> sent = Collections.synchronizedList(new ArrayList<List<Map<String, Object>>>());
    private final List<AvoBatcher> batchers = new ArrayList<>();

    private final AvoBatcher.Sender recordingSender = new AvoBatcher.Sender() {
        @Override
        public AvoNetworkCallsHandler.SendResult send(List<Map<String, Object>> events, String apiKey) {
            sent.add(events);
            return AvoNetworkCallsHandler.SendResult.OK;
        }
    };

    @After
    public void tearDown() {
        for (AvoBatcher batcher : batchers) {
            batcher.destroy();
        }
    }

    private AvoBatcher batcher(AvoBatcher.Sender sender, int batchSize, int maxQueueSize, boolean disableTimer) {
        AvoBatcher batcher = new AvoBatcher(sender, batchSize, 30, maxQueueSize, disableTimer);
        batchers.add(batcher);
        return batcher;
    }

    static Map<String, Object> event(String name) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("apiKey", "key");
        event.put("appName", "app");
        event.put("eventName", name);
        return event;
    }

    @Test(timeout = 10_000)
    public void flushWaitsForABatchSwappedOutButNotYetDispatched() throws Exception {
        final AvoBatcher batcher = batcher(recordingSender, 2, 1000, true);
        final CountDownLatch swapped = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        batcher.afterSwapForTesting = new Runnable() {
            @Override
            public void run() {
                swapped.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        Thread tracker = new Thread(new Runnable() {
            @Override
            public void run() {
                batcher.enqueue(event("E1"));
                batcher.enqueue(event("E2"));
            }
        });
        tracker.start();
        assertTrue(swapped.await(5, TimeUnit.SECONDS));

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
                release.countDown();
            }
        }).start();

        // The buffer is empty now, but E1+E2 are on their way: flush must wait for them.
        batcher.flush(5000);
        assertEquals(1, sent.size());
        tracker.join();
    }

    @Test(timeout = 10_000)
    public void sendQueueIsBoundedAndDropsTheOldestBatches() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        AvoBatcher.Sender blocking = new AvoBatcher.Sender() {
            @Override
            public AvoNetworkCallsHandler.SendResult send(List<Map<String, Object>> events, String apiKey) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                sent.add(events);
                return AvoNetworkCallsHandler.SendResult.OK;
            }
        };
        // batchSize 1, maxQueueSize 2: at most ceil(2 / 1) = 2 batches wait behind the 4 senders.
        AvoBatcher batcher = batcher(blocking, 1, 2, true);
        for (int i = 1; i <= 10; i++) {
            batcher.enqueue(event("E" + i));
        }
        release.countDown();
        batcher.flush(5000);

        List<String> names = new ArrayList<>();
        synchronized (sent) {
            for (List<Map<String, Object>> batch : sent) {
                names.add((String) batch.get(0).get("eventName"));
            }
        }
        Collections.sort(names);
        assertEquals(java.util.Arrays.asList("E1", "E10", "E2", "E3", "E4", "E9"), names);
    }

    private static int threadsNamed(String prefix) {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    @Test
    public void instancesShareOneTimerThread() {
        for (int i = 0; i < 5; i++) {
            // Each enqueue into an empty buffer arms that instance's flush timer.
            batcher(recordingSender, 30, 1000, false).enqueue(event("E" + i));
        }
        int timerThreads = threadsNamed("avo-inspector-flush-timer");
        assertTrue("timer threads: " + timerThreads, timerThreads <= 1);
    }
}
