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
}
