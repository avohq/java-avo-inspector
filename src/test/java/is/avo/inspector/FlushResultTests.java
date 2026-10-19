package is.avo.inspector;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

// flush() reports whether the instance drained: nothing buffered, waiting or in flight.
public class FlushResultTests {

    private final List<AvoInspector> inspectors = new ArrayList<>();
    private MockInspectorServer server;

    @Before
    public void setUp() throws Exception {
        server = new MockInspectorServer();
    }

    @After
    public void tearDown() {
        server.releaseResponses();
        for (AvoInspector inspector : inspectors) {
            inspector.destroy();
        }
        server.close();
    }

    private AvoInspector inspector() {
        AvoInspector inspector = new AvoInspector(AvoInspectorOptions.builder()
                .apiKey("test-key").appVersion("1.0.0").appName("TestApp").env(AvoInspectorEnv.Staging).build());
        inspector.networkCallsHandler.endpointForTesting = server.url();
        inspectors.add(inspector);
        return inspector;
    }

    private static void track(AvoInspector inspector) {
        inspector.trackSchemaFromEvent(InspectorEvent.builder().eventName("Event").eventProperties(Collections.<String, Object>singletonMap("a", 1)).build());
    }

    @Test
    public void anEmptyInstanceIsDrained() {
        AvoInspector inspector = inspector();
        assertTrue(inspector.flush());
        assertTrue(inspector.flush(0));
    }

    @Test(timeout = 10_000)
    public void aCompletedSendIsDrained() {
        AvoInspector inspector = inspector();
        track(inspector);
        assertTrue(inspector.flush());
        assertEquals(1, server.requests().size());
    }

    @Test(timeout = 10_000)
    public void aHungSendRunsOutTheTimeoutThenDrainsOnceAnswered() throws Exception {
        server.holdResponses();
        AvoInspector inspector = inspector();
        track(inspector);
        long start = System.nanoTime();
        assertFalse(inspector.flush(100));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue("elapsed " + elapsedMs, elapsedMs >= 90 && elapsedMs < 2_000);
        assertNotNull(server.awaitRequest(0, 5000));

        server.releaseResponses();
        assertTrue(inspector.flush());
        assertEquals(1, server.requests().size());
    }

    @Test(timeout = 10_000)
    public void flushZeroStartsTheSendsAndReportsThemPending() throws Exception {
        server.holdResponses();
        AvoInspector inspector = inspector();
        track(inspector);
        assertFalse(inspector.flush(0));
        // The send was started: it reaches the server without another flush.
        assertNotNull(server.awaitRequest(0, 5000));
        server.releaseResponses();
        assertTrue(inspector.flush());
    }

    @Test(timeout = 10_000)
    public void aDestroyedInstanceIsDrained() {
        server.holdResponses();
        AvoInspector inspector = inspector();
        track(inspector);
        assertFalse(inspector.flush(0));
        inspector.destroy();
        assertTrue(inspector.flush());
        assertTrue(inspector.flush(0));
    }
}
