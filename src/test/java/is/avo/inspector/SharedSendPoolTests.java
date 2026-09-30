package is.avo.inspector;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// Many instances must not multiply send threads: one bounded pool serves them all.
public class SharedSendPoolTests {

    private MockInspectorServer server;
    private final List<AvoInspector> inspectors = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        server = new MockInspectorServer();
    }

    @After
    public void tearDown() {
        for (AvoInspector inspector : inspectors) {
            inspector.destroy();
        }
        server.close();
    }

    private static int sendThreads() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("avo-inspector-send")) {
                count++;
            }
        }
        return count;
    }

    @Test(timeout = 60_000)
    public void manyInstancesShareABoundedSendPool() throws Exception {
        server.delayResponses(100);
        int peak = 0;
        for (int i = 0; i < 100; i++) {
            AvoInspector inspector = new AvoInspector(AvoInspectorOptions.builder().apiKey("key").appVersion("1.0.0")
                    .env(AvoInspectorEnv.Staging).batchSize(1).build());
            inspector.networkCallsHandler.endpointForTesting = server.url();
            inspectors.add(inspector);
            inspector.trackSchemaFromEvent("E" + i, Collections.<String, Object>emptyMap());
            peak = Math.max(peak, sendThreads());
        }
        assertTrue("send threads: " + peak, peak <= AvoBatcher.SHARED_SEND_THREADS);

        for (AvoInspector inspector : inspectors) {
            inspector.flush();
        }
        assertEquals(100, server.requests().size());
    }

    @Test(timeout = 30_000)
    public void aSendThatCannotStartIsDroppedNotPinned() {
        // What the JVM does when no native thread can be created.
        AvoBatcher.sendExecutorForTesting = new java.util.concurrent.Executor() {
            @Override
            public void execute(Runnable command) {
                throw new OutOfMemoryError("unable to create native thread");
            }
        };
        try {
            AvoInspector inspector = new AvoInspector(AvoInspectorOptions.builder().apiKey("key").appVersion("1.0.0")
                    .env(AvoInspectorEnv.Staging).batchSize(1).build());
            inspector.networkCallsHandler.endpointForTesting = server.url();
            inspectors.add(inspector);

            // Must not throw out of track.
            inspector.trackSchemaFromEvent("E", Collections.<String, Object>emptyMap());

            assertEquals(0, inspector.batcher.pendingCount());
            assertTrue(!AvoBatcher.isRegisteredForShutdownFlush(inspector.batcher));
            long start = System.nanoTime();
            inspector.flush();
            assertTrue("flush took too long", System.nanoTime() - start < 1_000_000_000L);
        } finally {
            AvoBatcher.sendExecutorForTesting = null;
        }
    }
}
