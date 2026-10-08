package is.avo.inspector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

// The Inspector interface grew default methods: implementations written against 1.x still compile.
public class InspectorInterfaceTests {

    // Implements only the six 1.x methods.
    static final class OneXInspector implements Inspector {
        final List<String> calls = new ArrayList<>();

        @Override
        public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable JSONObject eventProperties) {
            calls.add("json:" + eventName);
            return Collections.emptyMap();
        }

        @Override
        public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable JSONObject eventProperties, @NotNull AvoInspectorTarget target) {
            calls.add("json+target:" + eventName);
            return Collections.emptyMap();
        }

        @Override
        public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties) {
            calls.add("map:" + eventName);
            return Collections.emptyMap();
        }

        @Override
        public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties, @NotNull AvoInspectorTarget target) {
            calls.add("map+target:" + eventName);
            return Collections.emptyMap();
        }

        @Override
        public void trackSchema(@NotNull String eventName, @Nullable Map<String, AvoEventSchemaType> eventSchema) {
        }

        @Override
        public @NotNull Map<String, AvoEventSchemaType> extractSchema(@Nullable Object eventProperties) {
            return Collections.emptyMap();
        }
    }

    private MockInspectorServer server;

    @Before
    public void setUp() throws Exception {
        server = new MockInspectorServer();
    }

    @After
    public void tearDown() {
        server.close();
    }

    @Test
    public void defaultsDelegateToThe1xMethods() {
        OneXInspector oneX = new OneXInspector();
        Inspector inspector = oneX;
        AvoInspectorTarget target = new AvoInspectorTarget("key", "App", "1.0.0");
        GatewayOptions options = GatewayOptions.builder().outputReference("out").build();

        inspector.trackSchemaFromEvent("A", Collections.<String, Object>emptyMap(), "stream", options);
        inspector.trackSchemaFromEvent("B", new JSONObject(), "stream", options);
        inspector.trackSchemaFromEvent("C", Collections.<String, Object>emptyMap(), target, "stream", options);
        inspector.trackSchemaFromEvent("D", new JSONObject(), target, "stream", options);
        inspector.trackSchemaFromEvent("E", Collections.<String, Object>emptyMap(), "stream");
        inspector.trackSchemaFromEvent("F", new JSONObject(), "stream");
        // Nothing of its own to send: the defaults report it drained.
        assertTrue(inspector.flush());
        assertTrue(inspector.flush(100));
        inspector.destroy();

        assertEquals(java.util.Arrays.asList("map:A", "json:B", "map+target:C", "json+target:D", "map:E", "json:F"),
                oneX.calls);
    }

    // Forwards only the GatewayOptions overloads, as a wrapper written before the stream-id-only
    // overloads existed would.
    static final class GatewayForwarder implements Inspector {
        final List<String> calls = new ArrayList<>();
        final OneXInspector inner = new OneXInspector();

        @Override
        public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties,
                                                                             @Nullable String streamId, @Nullable GatewayOptions options) {
            calls.add("map:" + eventName + ":" + streamId + ":" + options);
            return Collections.emptyMap();
        }

        @Override
        public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable JSONObject eventProperties,
                                                                             @Nullable String streamId, @Nullable GatewayOptions options) {
            calls.add("json:" + eventName + ":" + streamId + ":" + options);
            return Collections.emptyMap();
        }

        @Override
        public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable JSONObject eventProperties) {
            return inner.trackSchemaFromEvent(eventName, eventProperties);
        }

        @Override
        public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable JSONObject eventProperties, @NotNull AvoInspectorTarget target) {
            return inner.trackSchemaFromEvent(eventName, eventProperties, target);
        }

        @Override
        public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties) {
            return inner.trackSchemaFromEvent(eventName, eventProperties);
        }

        @Override
        public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties, @NotNull AvoInspectorTarget target) {
            return inner.trackSchemaFromEvent(eventName, eventProperties, target);
        }

        @Override
        public void trackSchema(@NotNull String eventName, @Nullable Map<String, AvoEventSchemaType> eventSchema) {
        }

        @Override
        public @NotNull Map<String, AvoEventSchemaType> extractSchema(@Nullable Object eventProperties) {
            return Collections.emptyMap();
        }
    }

    @Test
    public void theStreamIdOverloadsPassNullGatewayOptions() {
        GatewayForwarder forwarder = new GatewayForwarder();
        Inspector inspector = forwarder;

        inspector.trackSchemaFromEvent("A", Collections.<String, Object>emptyMap(), "stream");
        inspector.trackSchemaFromEvent("B", new JSONObject(), "stream");
        inspector.trackSchemaFromEvent("C", Collections.<String, Object>emptyMap(), (String) null);

        assertEquals(java.util.Arrays.asList("map:A:stream:null", "json:B:stream:null", "map:C:null:null"), forwarder.calls);
        assertTrue(forwarder.inner.calls.isEmpty());
    }

    @Test
    public void avoInspectorSendsTheStreamIdWithoutGatewayFields() throws Exception {
        AvoInspector avoInspector = new AvoInspector("test-key", "1.0.0", "App", AvoInspectorEnv.Staging);
        avoInspector.networkCallsHandler.endpointForTesting = server.url();

        avoInspector.trackSchemaFromEvent("Streamed", Collections.<String, Object>singletonMap("a", 1), "stream-2");
        avoInspector.flush();
        avoInspector.destroy();

        MockInspectorServer.Request request = server.awaitRequest(0, 5000);
        assertNotNull(request);
        JSONObject event = request.body.getJSONObject(0);
        assertEquals("stream-2", event.getString("streamId"));
        assertEquals("1.0.0", event.getString("appVersion"));
        assertTrue(event.toString(), !event.has("outputReference") && !event.has("originHint"));
    }

    @Test
    public void avoInspectorImplementsTheNewMethods() throws Exception {
        AvoInspector avoInspector = new AvoInspector("test-key", "1.0.0", "App", AvoInspectorEnv.Staging);
        avoInspector.networkCallsHandler.endpointForTesting = server.url();
        Inspector inspector = avoInspector;

        inspector.trackSchemaFromEvent("Targeted", Collections.<String, Object>singletonMap("a", 1),
                new AvoInspectorTarget("other-key", "Other", "9.9.9"), "stream-1",
                GatewayOptions.builder().outputReference(" out ").build());
        inspector.flush();

        MockInspectorServer.Request request = server.awaitRequest(0, 5000);
        assertNotNull(request);
        JSONObject event = request.body.getJSONObject(0);
        assertEquals("other-key", request.headers.get("api-key"));
        assertEquals("Other", event.getString("appName"));
        assertEquals("9.9.9", event.getString("appVersion"));
        assertEquals("stream-1", event.getString("streamId"));
        assertEquals("out", event.getString("outputReference"));

        inspector.destroy();
        inspector.trackSchemaFromEvent("AfterDestroy", Collections.<String, Object>emptyMap());
        inspector.flush();
        assertEquals(1, server.requests().size());
    }
}
