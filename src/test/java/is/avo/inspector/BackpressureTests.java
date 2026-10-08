package is.avo.inspector;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// blockWhenBacklogged(true): a track call waits while 1,000 or more events wait for a send slot.
public class BackpressureTests {

    private final List<AvoInspector> inspectors = new ArrayList<>();
    private MockInspectorServer server;

    @Before
    public void setUp() throws Exception {
        server = new MockInspectorServer(true);
    }

    @After
    public void tearDown() {
        AvoBatcher.backlogWaitMs = AvoNetworkCallsHandler.TIMEOUT_MS;
        server.releaseResponses();
        for (AvoInspector inspector : inspectors) {
            inspector.destroy();
        }
        server.close();
    }

    private AvoInspector inspector(boolean block, int batchSize) {
        AvoInspector inspector = new AvoInspector(AvoInspectorOptions.builder()
                .apiKey("test-key").appVersion("1.0.0").appName("TestApp").env(AvoInspectorEnv.Staging)
                .batchSize(batchSize).disableBatchTimer(true).blockWhenBacklogged(block).build());
        inspector.networkCallsHandler.endpointForTesting = server.url();
        inspectors.add(inspector);
        return inspector;
    }

    private static void track(AvoInspector inspector, int i) {
        inspector.trackSchemaFromEvent(InspectorEvent.builder().eventName("E").eventProperties(Collections.<String, Object>singletonMap("i", i)).build());
    }

    private int eventsReceived() {
        int events = 0;
        for (MockInspectorServer.Request request : server.requests()) {
            events += request.body.length();
        }
        return events;
    }

    // 4 sends of 10 run and hang; each later batch waits. The 1,040th event leaves 100 batches
    // (1,000 events) waiting.
    private static void fillToJustBelowTheThreshold(AvoInspector inspector) {
        for (int i = 0; i < 1_039; i++) {
            track(inspector, i);
        }
    }

    @Test(timeout = 60_000)
    public void aTightLoopDeliversEveryEventWhenBlocking() throws Exception {
        server.delayResponses(50);
        AvoInspector inspector = inspector(true, 100);
        for (int i = 0; i < 20_000; i++) {
            track(inspector, i);
        }
        assertTrue(inspector.flush());
        assertEquals(20_000, eventsReceived());
    }

    // The same loop without the option outruns the sends, and the backlog drops events.
    @Test(timeout = 60_000)
    public void theSameLoopDropsEventsWithoutBlocking() throws Exception {
        server.delayResponses(50);
        AvoInspector inspector = inspector(false, 100);
        for (int i = 0; i < 20_000; i++) {
            track(inspector, i);
        }
        assertTrue(inspector.flush(30_000));
        int received = eventsReceived();
        assertTrue("received " + received, received < 20_000);
    }

    @Test(timeout = 30_000)
    public void offByDefaultTrackNeverWaits() {
        server.holdResponses();
        AvoInspector inspector = new AvoInspector(AvoInspectorOptions.builder()
                .apiKey("test-key").appVersion("1.0.0").env(AvoInspectorEnv.Staging)
                .batchSize(10).disableBatchTimer(true).build());
        inspector.networkCallsHandler.endpointForTesting = server.url();
        inspectors.add(inspector);
        long start = System.nanoTime();
        for (int i = 0; i < 2_000; i++) {
            track(inspector, i);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue("elapsed " + elapsedMs, elapsedMs < 2_000);
    }

    @Test(timeout = 30_000)
    public void aHungEndpointBoundsEachWait() {
        AvoBatcher.backlogWaitMs = 300;
        server.holdResponses();
        AvoInspector inspector = inspector(true, 10);
        long start = System.nanoTime();
        fillToJustBelowTheThreshold(inspector);
        long belowMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue("below the threshold took " + belowMs, belowMs < 2_000);

        start = System.nanoTime();
        track(inspector, 1_039);
        long waitedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue("waited " + waitedMs, waitedMs >= 250 && waitedMs < 2_000);
    }

    private Thread blockedTrack(final AvoInspector inspector, final AtomicLong returnedAt, final AtomicBoolean interruptedAfter) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                track(inspector, 1_039);
                returnedAt.set(System.nanoTime());
                interruptedAfter.set(Thread.currentThread().isInterrupted());
            }
        });
        thread.start();
        return thread;
    }

    @Test(timeout = 30_000)
    public void destroyReleasesAWaitingTrack() throws Exception {
        server.holdResponses();
        AvoInspector inspector = inspector(true, 10);
        fillToJustBelowTheThreshold(inspector);
        AtomicLong returnedAt = new AtomicLong();
        Thread thread = blockedTrack(inspector, returnedAt, new AtomicBoolean());
        Thread.sleep(300);
        assertTrue("the track did not wait", thread.isAlive());

        long destroyedAt = System.nanoTime();
        inspector.destroy();
        thread.join(2_000);
        assertFalse(thread.isAlive());
        assertTrue((returnedAt.get() - destroyedAt) / 1_000_000 < 1_000);
    }

    @Test(timeout = 30_000)
    public void anInterruptEndsTheWaitAndKeepsTheFlag() throws Exception {
        server.holdResponses();
        AvoInspector inspector = inspector(true, 10);
        fillToJustBelowTheThreshold(inspector);
        AtomicBoolean interruptedAfter = new AtomicBoolean();
        Thread thread = blockedTrack(inspector, new AtomicLong(), interruptedAfter);
        Thread.sleep(300);
        assertTrue("the track did not wait", thread.isAlive());

        thread.interrupt();
        thread.join(2_000);
        assertFalse(thread.isAlive());
        assertTrue(interruptedAfter.get());
    }
}
