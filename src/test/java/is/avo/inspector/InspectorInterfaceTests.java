package is.avo.inspector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// The 2.0 Inspector interface: one track call taking an InspectorEvent, and no default methods.
public class InspectorInterfaceTests {

    // A wrapper, as a custom implementation is written against 2.0: it implements every method.
    static final class ForwardingInspector implements Inspector {
        final Inspector inner;
        final List<InspectorEvent> tracked = new ArrayList<>();

        ForwardingInspector(Inspector inner) {
            this.inner = inner;
        }

        @Override
        public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@Nullable InspectorEvent event) {
            tracked.add(event);
            return inner.trackSchemaFromEvent(event);
        }

        @Override
        public void trackSchema(@NotNull String eventName, @Nullable Map<String, AvoEventSchemaType> eventSchema) {
            inner.trackSchema(eventName, eventSchema);
        }

        @Override
        public @NotNull Map<String, AvoEventSchemaType> extractSchema(@Nullable Object eventProperties) {
            return inner.extractSchema(eventProperties);
        }

        @Override
        public boolean flush() {
            return inner.flush();
        }

        @Override
        public boolean flush(long timeoutMs) {
            return inner.flush(timeoutMs);
        }

        @Override
        public void destroy() {
            inner.destroy();
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

    private AvoInspector staging() {
        AvoInspector inspector = new AvoInspector("test-key", "1.0.0", "App", AvoInspectorEnv.Staging);
        inspector.networkCallsHandler.endpointForTesting = server.url();
        return inspector;
    }

    @Test
    public void theInterfaceHasNoDefaultMethodsAndOneTrackCall() {
        List<String> tracks = new ArrayList<>();
        for (Method method : Inspector.class.getDeclaredMethods()) {
            assertFalse(method.toString(), method.isDefault());
            assertTrue(method.toString(), Modifier.isAbstract(method.getModifiers()));
            if (method.getName().equals("trackSchemaFromEvent")) {
                tracks.add(java.util.Arrays.toString(method.getParameterTypes()));
            }
        }
        assertEquals(Collections.singletonList("[class is.avo.inspector.InspectorEvent]"), tracks);

        // No positional form is left on the implementation either.
        tracks.clear();
        for (Method method : AvoInspector.class.getMethods()) {
            if (method.getName().equals("trackSchemaFromEvent")) {
                tracks.add(java.util.Arrays.toString(method.getParameterTypes()));
            }
        }
        assertEquals(Collections.singletonList("[class is.avo.inspector.InspectorEvent]"), tracks);
    }

    @Test
    public void aBuiltEventKeepsEveryValue() {
        AvoInspectorTarget target = new AvoInspectorTarget("other-key", "Other", "9.9.9");
        Map<String, Object> props = Collections.<String, Object>singletonMap("a", 1);
        InspectorEvent event = InspectorEvent.builder().eventName("E").eventProperties(props).streamId("s")
                .outputReference("out").originHint("web").originAppVersion("2.0").target(target).build();

        assertEquals("E", event.getEventName());
        assertSame(props, event.getEventProperties());
        assertEquals("s", event.getStreamId());
        assertEquals("out", event.getOutputReference());
        assertEquals("web", event.getOriginHint());
        assertEquals("2.0", event.getOriginAppVersion());
        assertSame(target, event.getTarget());

        JSONObject json = new JSONObject().put("a", 1);
        assertSame(json, InspectorEvent.builder().eventProperties(json).build().getEventProperties());

        InspectorEvent empty = InspectorEvent.builder().build();
        assertNull(empty.getEventName());
        assertNull(empty.getEventProperties());
        assertNull(empty.getStreamId());
        assertNull(empty.getOutputReference());
        assertNull(empty.getOriginHint());
        assertNull(empty.getOriginAppVersion());
        assertNull(empty.getTarget());
    }

    @Test
    public void aWrapperReceivesTheEventAndForwardsIt() throws Exception {
        ForwardingInspector wrapper = new ForwardingInspector(staging());
        Inspector inspector = wrapper;
        InspectorEvent event = InspectorEvent.builder().eventName("Wrapped")
                .eventProperties(Collections.<String, Object>singletonMap("a", 1)).streamId("s").build();

        assertEquals("int", inspector.trackSchemaFromEvent(event).get("a").toString());
        assertTrue(inspector.flush());
        inspector.destroy();

        assertEquals(Collections.singletonList(event), wrapper.tracked);
        assertEquals("Wrapped", server.requests().get(0).body.getJSONObject(0).getString("eventName"));
    }

    @Test
    public void theTargetStreamIdAndGatewayValuesReachTheWire() throws Exception {
        AvoInspector avoInspector = staging();
        Inspector inspector = avoInspector;

        inspector.trackSchemaFromEvent(InspectorEvent.builder().eventName("Targeted")
                .eventProperties(Collections.<String, Object>singletonMap("a", 1))
                .target(new AvoInspectorTarget("other-key", "Other", "9.9.9")).streamId("stream-1")
                .outputReference(" out ").build());
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
        inspector.trackSchemaFromEvent(InspectorEvent.builder().eventName("AfterDestroy").build());
        inspector.flush();
        assertEquals(1, server.requests().size());
    }

    @Test
    public void aStreamIdAloneSendsNoGatewayFields() throws Exception {
        AvoInspector avoInspector = staging();
        avoInspector.trackSchemaFromEvent(InspectorEvent.builder().eventName("Streamed")
                .eventProperties(Collections.<String, Object>singletonMap("a", 1)).streamId("stream-2").build());
        avoInspector.flush();
        avoInspector.destroy();

        JSONObject event = server.requests().get(0).body.getJSONObject(0);
        assertEquals("stream-2", event.getString("streamId"));
        assertEquals("1.0.0", event.getString("appVersion"));
        assertEquals("App", event.getString("appName"));
        assertTrue(event.toString(), !event.has("outputReference") && !event.has("originHint"));
    }

    @Test
    public void mapAndJsonObjectPropertiesGiveTheSameWireEvent() throws Exception {
        AvoInspector avoInspector = staging();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("n", 1);
        avoInspector.trackSchemaFromEvent(InspectorEvent.builder().eventName("E").eventProperties(map).build());
        avoInspector.trackSchemaFromEvent(InspectorEvent.builder().eventName("E").eventProperties(new JSONObject().put("n", 1)).build());
        avoInspector.flush();
        avoInspector.destroy();

        List<String> bodies = new ArrayList<>();
        for (MockInspectorServer.Request request : server.requests()) {
            for (int i = 0; i < request.body.length(); i++) {
                bodies.add(request.body.getJSONObject(i).getJSONArray("eventProperties").toString());
            }
        }
        assertEquals(java.util.Arrays.asList("[{\"propertyName\":\"n\",\"propertyType\":\"int\"}]",
                "[{\"propertyName\":\"n\",\"propertyType\":\"int\"}]"), bodies);
    }
}
