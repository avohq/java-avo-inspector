package is.avo.inspector;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

// SPEC.md §12, §4.5, §4.6: buffering, triggers, flush, destroy.
public class BatchingTests {

    private static final Map<String, Object> NO_PROPS = Collections.emptyMap();

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

    private AvoInspector inspector(AvoInspectorOptions.Builder options) {
        AvoInspector inspector = new AvoInspector(options.apiKey("test-key").appVersion("1.0.0").appName("TestApp").build());
        inspector.networkCallsHandler.endpointForTesting = server.url();
        inspectors.add(inspector);
        return inspector;
    }

    private static List<String> eventNames(MockInspectorServer.Request request) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < request.body.length(); i++) {
            names.add(request.body.getJSONObject(i).getString("eventName"));
        }
        return names;
    }

    @Test
    public void devForcesImmediateSend() throws Exception {
        AvoInspector inspector = inspector(AvoInspectorOptions.builder().env(AvoInspectorEnv.Dev).batchSize(30));

        inspector.trackSchemaFromEvent("E1", NO_PROPS);

        assertNotNull(server.awaitRequest(0, 5000));
        assertEquals(Collections.singletonList("E1"), eventNames(server.requests().get(0)));
        assertFalse(inspector.batcher.isTimerRunning());
    }

    @Test
    public void defaultsBufferThirtyEventsOutsideDev() throws Exception {
        AvoInspector inspector = inspector(AvoInspectorOptions.builder().env(AvoInspectorEnv.Prod));
        assertEquals(30, inspector.batchSize);
        for (int i = 0; i < 29; i++) {
            inspector.trackSchemaFromEvent("E" + i, NO_PROPS);
        }
        assertEquals(29, inspector.batcher.bufferedCount());
        inspector.trackSchemaFromEvent("E29", NO_PROPS);
        assertNotNull(server.awaitRequest(0, 5000));
        assertEquals(30, server.requests().get(0).body.length());
    }

    @Test
    public void invalidBatchOptionsFallBackToDefaults() {
        AvoInspector inspector = inspector(AvoInspectorOptions.builder().env(AvoInspectorEnv.Staging)
                .batchSize(0).batchFlushSeconds(-1).maxQueueSize(0));
        assertEquals(30, inspector.batchSize);
    }

    @Test
    public void sizeTriggerSendsExactlyBatchSizeEvents() throws Exception {
        AvoInspector inspector = inspector(AvoInspectorOptions.builder().env(AvoInspectorEnv.Staging).batchSize(3));
        for (int i = 1; i <= 4; i++) {
            inspector.trackSchemaFromEvent("E" + i, NO_PROPS);
        }
        assertNotNull(server.awaitRequest(0, 5000));
        assertEquals(1, inspector.batcher.bufferedCount());
        inspector.flush();

        assertEquals(2, server.requests().size());
    }

    @Test(timeout = 10_000)
    public void timerFlushesTheOldestEventWithoutFurtherTraffic() throws Exception {
        AvoInspector inspector = inspector(AvoInspectorOptions.builder().env(AvoInspectorEnv.Staging).batchFlushSeconds(0.2));

        inspector.trackSchemaFromEvent("E1", NO_PROPS);
        inspector.trackSchemaFromEvent("E2", NO_PROPS);

        MockInspectorServer.Request request = server.awaitRequest(0, 5000);
        assertNotNull(request);
        assertEquals(2, request.body.length());

        // A later event starts a new window.
        inspector.trackSchemaFromEvent("E3", NO_PROPS);
        assertNotNull(server.awaitRequest(1, 5000));
        assertEquals(Collections.singletonList("E3"), eventNames(server.requests().get(1)));
    }

    @Test
    public void disableBatchTimerLeavesEventsBufferedUntilFlush() throws Exception {
        AvoInspector inspector = inspector(AvoInspectorOptions.builder().env(AvoInspectorEnv.Staging)
                .batchFlushSeconds(0.1).disableBatchTimer(true));
        assertFalse(inspector.batcher.isTimerRunning());

        inspector.trackSchemaFromEvent("E1", NO_PROPS);
        Thread.sleep(600);
        assertEquals(0, server.requests().size());

        inspector.flush();
        assertEquals(1, server.requests().size());
    }

    @Test
    public void flushAfterFlushIsANoOpAndInstanceStaysUsable() throws Exception {
        AvoInspector inspector = inspector(AvoInspectorOptions.builder().env(AvoInspectorEnv.Staging));
        inspector.flush();
        assertEquals(0, server.requests().size());

        inspector.trackSchemaFromEvent("E1", NO_PROPS);
        inspector.flush();
        inspector.flush();
        inspector.trackSchemaFromEvent("E2", NO_PROPS);
        inspector.flush();
        assertEquals(2, server.requests().size());
    }

    @Test(timeout = 10_000)
    public void flushReturnsAtItsTimeoutWhileASendIsStillInFlight() throws Exception {
        server.delayResponses(3000);
        AvoInspector inspector = inspector(AvoInspectorOptions.builder().env(AvoInspectorEnv.Staging));
        inspector.trackSchemaFromEvent("E1", NO_PROPS);

        long start = System.currentTimeMillis();
        inspector.flush(200);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue("elapsed " + elapsed, elapsed < 2000);
        assertEquals(1, inspector.batcher.pendingCount());
    }

    @Test
    public void maxQueueSizeDropsTheOldestEvents() throws Exception {
        AvoInspector inspector = inspector(AvoInspectorOptions.builder().env(AvoInspectorEnv.Staging).maxQueueSize(2));
        for (int i = 1; i <= 5; i++) {
            inspector.trackSchemaFromEvent("E" + i, NO_PROPS);
        }
        inspector.flush();
        assertEquals(1, server.requests().size());
        assertEquals(java.util.Arrays.asList("E4", "E5"), eventNames(server.requests().get(0)));
    }

    @Test
    public void destroyDiscardsBufferStopsTimerAndTerminatesTheInstance() throws Exception {
        AvoInspector inspector = inspector(AvoInspectorOptions.builder().env(AvoInspectorEnv.Staging));
        inspector.setSamplingRateForTesting(0.75);
        inspector.trackSchemaFromEvent("E1", NO_PROPS);
        assertTrue(inspector.batcher.isTimerRunning());

        inspector.destroy();

        assertEquals(0, inspector.batcher.bufferedCount());
        assertEquals(0, inspector.batcher.pendingCount());
        assertFalse(inspector.batcher.isTimerRunning());
        assertEquals(0.75, inspector.networkCallsHandler.samplingRate, 0.0);
        assertEquals("test-key", inspector.defaultAvoInspectorTarget.getApiKey());

        Map<String, AvoEventSchemaType> schema = inspector.trackSchemaFromEvent("E2", Collections.<String, Object>singletonMap("a", 1));
        assertTrue(schema.isEmpty());
        inspector.flush();
        assertEquals(0, server.requests().size());
    }

    @Test
    public void destroyAbandonsInFlightSends() throws Exception {
        server.delayResponses(2000);
        AvoInspector inspector = inspector(AvoInspectorOptions.builder().env(AvoInspectorEnv.Dev));
        inspector.trackSchemaFromEvent("E1", NO_PROPS);
        assertNotNull(server.awaitRequest(0, 5000));

        inspector.destroy();
        assertEquals(0, inspector.batcher.pendingCount());
    }

    @Test
    public void overrideTargetsTravelInSeparateRequests() throws Exception {
        AvoInspector inspector = inspector(AvoInspectorOptions.builder().env(AvoInspectorEnv.Staging));
        AvoInspectorTarget other = new AvoInspectorTarget("other-key", "OtherApp", "9.9.9");

        inspector.trackSchemaFromEvent("Default1", NO_PROPS);
        inspector.trackSchemaFromEvent("Other1", NO_PROPS, other);
        inspector.trackSchemaFromEvent("Default2", NO_PROPS);
        inspector.flush();

        assertEquals(2, server.requests().size());
        for (MockInspectorServer.Request request : server.requests()) {
            for (int i = 0; i < request.body.length(); i++) {
                JSONObject event = request.body.getJSONObject(i);
                assertEquals(request.headers.get("api-key"), event.getString("apiKey"));
            }
            if ("other-key".equals(request.headers.get("api-key"))) {
                assertEquals(Collections.singletonList("Other1"), eventNames(request));
                assertEquals("9.9.9", request.body.getJSONObject(0).getString("appVersion"));
                assertEquals("OtherApp", request.body.getJSONObject(0).getString("appName"));
            } else {
                assertEquals(java.util.Arrays.asList("Default1", "Default2"), eventNames(request));
            }
        }
    }

    @Test
    public void gatewayOptionsAreResolvedPerEvent() throws Exception {
        AvoInspector inspector = inspector(AvoInspectorOptions.builder().env(AvoInspectorEnv.Staging));
        Map<String, Object> props = Collections.<String, Object>singletonMap("a", 1);

        inspector.trackSchemaFromEvent("P", props, "s1", TrackOptions.builder().outputReference(" meta ").originAppVersion("4.2.0").build());
        inspector.trackSchemaFromEvent("P", props, "s1", TrackOptions.builder().outputReference("ga4").originHint(" android ").build());
        inspector.trackSchemaFromEvent("P", props, null, TrackOptions.builder().originHint("   ").originAppVersion("").build());
        inspector.flush();

        assertEquals(1, server.requests().size());
        JSONObject first = server.requests().get(0).body.getJSONObject(0);
        JSONObject second = server.requests().get(0).body.getJSONObject(1);
        JSONObject third = server.requests().get(0).body.getJSONObject(2);

        assertEquals("meta", first.getString("outputReference"));
        assertEquals("4.2.0", first.getString("appVersion"));
        assertFalse(first.has("originHint"));

        assertEquals("ga4", second.getString("outputReference"));
        assertEquals("android", second.getString("originHint"));
        assertTrue(second.isNull("appVersion"));

        assertFalse(third.has("outputReference"));
        assertFalse(third.has("originHint"));
        assertEquals("1.0.0", third.getString("appVersion"));
        assertEquals("", third.getString("streamId"));
    }
}
