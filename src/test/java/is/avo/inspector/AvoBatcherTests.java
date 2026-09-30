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
    public void waitingSendsHaveTheirOwnAllowanceNotMaxQueueSize() throws Exception {
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
        // maxQueueSize bounds only the unsent buffer: 6 sends waiting behind the 4 running are kept.
        AvoBatcher batcher = batcher(blocking, 1, 2, true);
        for (int i = 1; i <= 10; i++) {
            batcher.enqueue(event("E" + i));
        }
        release.countDown();
        batcher.flush(5000);
        assertEquals(10, sent.size());
    }

    @Test
    public void eachTargetFillsItsOwnBatches() {
        AvoBatcher batcher = batcher(recordingSender, 5, 1000, true);
        for (int i = 0; i < 20; i++) {
            Map<String, Object> event = event("E" + i);
            event.put("apiKey", "key-" + (i % 2));
            batcher.enqueue(event);
        }
        batcher.flush(5000);

        assertEquals(4, sent.size());
        synchronized (sent) {
            for (List<Map<String, Object>> batch : sent) {
                assertEquals(5, batch.size());
                for (Map<String, Object> event : batch) {
                    assertEquals(batch.get(0).get("apiKey"), event.get("apiKey"));
                }
            }
        }
    }

    @Test
    public void maxQueueSizeBoundsTheTotalAcrossTargetsDroppingTheOldest() {
        AvoBatcher batcher = batcher(recordingSender, 30, 3, true);
        String[][] events = {{"key-a", "A1"}, {"key-b", "B1"}, {"key-a", "A2"}, {"key-b", "B2"}};
        for (String[] e : events) {
            Map<String, Object> event = event(e[1]);
            event.put("apiKey", e[0]);
            batcher.enqueue(event);
        }
        assertEquals(3, batcher.bufferedCount());
        batcher.flush(5000);

        List<String> names = new ArrayList<>();
        synchronized (sent) {
            for (List<Map<String, Object>> batch : sent) {
                for (Map<String, Object> event : batch) {
                    names.add((String) event.get("eventName"));
                }
            }
        }
        Collections.sort(names);
        assertEquals(java.util.Arrays.asList("A2", "B1", "B2"), names);
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
        // Up to 4 sends run; at most 2 events may wait for a slot, the oldest dropped first.
        AvoBatcher.maxWaitingEvents = 2;
        AvoBatcher batcher = batcher(blocking, 1, 1000, true);
        try {
            for (int i = 1; i <= 10; i++) {
                batcher.enqueue(event("E" + i));
            }
        } finally {
            AvoBatcher.maxWaitingEvents = AvoBatcher.MAX_WAITING_EVENTS;
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

    @Test
    public void timerIsArmedOncePerBufferGeneration() {
        // maxQueueSize 1: every enqueue drops the previous event, leaving one event buffered.
        AvoBatcher batcher = batcher(recordingSender, 30, 1, false);
        for (int i = 0; i < 5; i++) {
            batcher.enqueue(event("E" + i));
        }
        assertEquals(1, batcher.timerArmsForTesting);

        batcher.flush(1000);
        batcher.enqueue(event("E5"));
        assertEquals(2, batcher.timerArmsForTesting);
    }

    @Test(timeout = 10_000)
    public void aFlushSpanningManyTargetsDropsNothingOnAHealthyNetwork() throws Exception {
        final List<Integer> delivered = Collections.synchronizedList(new ArrayList<Integer>());
        AvoBatcher.Sender slowButWorking = new AvoBatcher.Sender() {
            @Override
            public AvoNetworkCallsHandler.SendResult send(List<Map<String, Object>> events, String apiKey) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                delivered.add(events.size());
                return AvoNetworkCallsHandler.SendResult.OK;
            }
        };
        // 30 events over 6 targets: one flush becomes 6 requests, 5 events each.
        AvoBatcher batcher = batcher(slowButWorking, 30, 30, true);
        for (int i = 0; i < 30; i++) {
            Map<String, Object> event = event("E" + i);
            event.put("apiKey", "key-" + (i % 6));
            batcher.enqueue(event);
        }
        batcher.flush(5000);

        int total = 0;
        synchronized (delivered) {
            for (int size : delivered) {
                total += size;
            }
        }
        assertEquals(30, total);
        assertEquals(6, delivered.size());
    }
}
