package is.avo.inspector;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// SPEC.md §7: endpoint, headers, gzip, header guard, failures, sampling-rate updates.
public class NetworkTests {

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

    private AvoInspector inspector(AvoInspectorEnv env, int batchSize) {
        AvoInspector inspector = new AvoInspector(AvoInspectorOptions.builder()
                .apiKey("test-key").appVersion("1.0.0").appName("TestApp").env(env).batchSize(batchSize).build());
        inspector.networkCallsHandler.endpointForTesting = server.url();
        inspectors.add(inspector);
        return inspector;
    }

    private static List<Map<String, Object>> eventWithPadding(int padding) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("k", new String(new char[padding]).replace('\0', 'x'));
        return Collections.singletonList(event);
    }

    @Test
    public void defaultEndpointIsV2() {
        assertEquals("https://api.avo.app/inspector/v2/track", AvoNetworkCallsHandler.DEFAULT_ENDPOINT);
    }

    @Test
    public void mockEndpointIsHonouredOutsideProdOnly() {
        String mock = "http://localhost:9876";
        assertEquals(mock, AvoNetworkCallsHandler.resolveEndpoint("dev", mock));
        assertEquals(mock, AvoNetworkCallsHandler.resolveEndpoint("staging", mock));
        assertEquals(AvoNetworkCallsHandler.DEFAULT_ENDPOINT, AvoNetworkCallsHandler.resolveEndpoint("prod", mock));
        assertEquals(AvoNetworkCallsHandler.DEFAULT_ENDPOINT, AvoNetworkCallsHandler.resolveEndpoint("dev", ""));
        assertEquals(AvoNetworkCallsHandler.DEFAULT_ENDPOINT, AvoNetworkCallsHandler.resolveEndpoint("dev", null));
    }

    @Test
    public void sendsRequiredHeadersAndV3Body() throws Exception {
        AvoInspector inspector = inspector(AvoInspectorEnv.Staging, 1);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("zero", 0.0);
        inspector.trackSchemaFromEvent("Event", props);
        inspector.flush();

        MockInspectorServer.Request request = server.awaitRequest(0, 5000);
        assertNotNull(request);
        assertEquals("test-key", request.headers.get("api-key"));
        assertEquals("staging", request.headers.get("env"));
        assertEquals("java-jvm", request.headers.get("x-avo-client"));
        assertEquals("application/json", request.headers.get("content-type"));
        assertEquals(String.valueOf(request.rawBody.length), request.headers.get("content-length"));
        assertNull(request.headers.get("content-encoding"));

        assertEquals(1, request.body.length());
        JSONObject event = request.body.getJSONObject(0);
        assertEquals("java-jvm", event.getString("libPlatform"));
        assertEquals(AvoInspectorVersion.VERSION, event.getString("libVersion"));
        assertEquals("", event.getString("streamId"));
        assertEquals("1.0.0", event.getString("appVersion"));
        assertEquals("float", event.getJSONArray("eventProperties").getJSONObject(0).getString("propertyType"));
        assertFalse(event.has("sessionId"));
        assertFalse(event.has("trackingId"));
        assertFalse(event.has("avoFunction"));
        assertFalse(event.has("outputReference"));
        assertFalse(event.has("originHint"));
    }

    @Test
    public void gzipStartsAtExactly1024Bytes() throws Exception {
        AvoNetworkCallsHandler handler = new AvoNetworkCallsHandler("dev");
        handler.endpointForTesting = server.url();

        // [{"k":"x..."}] is 10 bytes plus the padding.
        assertEquals(AvoNetworkCallsHandler.SendResult.OK, handler.send(eventWithPadding(1013), "test-key"));
        assertEquals(AvoNetworkCallsHandler.SendResult.OK, handler.send(eventWithPadding(1014), "test-key"));

        MockInspectorServer.Request small = server.requests().get(0);
        MockInspectorServer.Request large = server.requests().get(1);
        assertEquals(1023, small.rawBody.length);
        assertNull(small.headers.get("content-encoding"));
        assertEquals("gzip", large.headers.get("content-encoding"));
        assertEquals(String.valueOf(large.rawBody.length), large.headers.get("content-length"));
        assertTrue(large.rawBody.length < 1024);
        assertEquals(1014, large.body.getJSONObject(0).getString("k").length());
        assertEquals("application/json", large.headers.get("content-type"));
    }

    @Test
    public void aSendThatStartsAfterDestroyNeverConnects() {
        // destroy() aborts connections that are registered; a send that registers after it must
        // see the abort before connecting.
        AvoNetworkCallsHandler handler = new AvoNetworkCallsHandler("dev");
        handler.endpointForTesting = server.url();
        handler.abortAll();

        assertEquals(AvoNetworkCallsHandler.SendResult.FAILED, handler.send(eventWithPadding(1), "test-key"));
        assertEquals(0, server.requests().size());
    }

    @Test
    public void refusesToSendAnApiKeyWithControlCharacters() {
        AvoNetworkCallsHandler handler = new AvoNetworkCallsHandler("dev");
        handler.endpointForTesting = server.url();

        assertEquals(AvoNetworkCallsHandler.SendResult.FAILED, handler.send(eventWithPadding(1), "key\r\nX-Injected: 1"));
        assertEquals(AvoNetworkCallsHandler.SendResult.FAILED, handler.send(eventWithPadding(1), "key\nmore"));
        assertEquals(AvoNetworkCallsHandler.SendResult.FAILED, handler.send(eventWithPadding(1), "key\0"));
        assertEquals(AvoNetworkCallsHandler.SendResult.FAILED, handler.send(eventWithPadding(1), "key\u0007"));
        assertEquals(AvoNetworkCallsHandler.SendResult.FAILED, handler.send(eventWithPadding(1), "key\u007f"));
        assertEquals(AvoNetworkCallsHandler.SendResult.FAILED, handler.send(eventWithPadding(1), "key\u0085"));
        assertEquals(0, server.requests().size());
    }

    @Test
    public void overrideTargetWithControlCharacterIsDroppedNotRewritten() throws Exception {
        AvoInspector inspector = inspector(AvoInspectorEnv.Dev, 1);
        Map<String, Object> props = Collections.<String, Object>singletonMap("a", 1);

        Map<String, AvoEventSchemaType> schema = inspector.trackSchemaFromEvent("Event", props,
                new AvoInspectorTarget("bad\rkey", "Other", "2.0.0"));
        inspector.flush();

        assertEquals(1, schema.size());
        assertEquals(0, server.requests().size());
    }

    @Test
    public void samplingRateOnlyChangesOnA200WithAValidRate() throws Exception {
        AvoNetworkCallsHandler handler = new AvoNetworkCallsHandler("dev");
        handler.endpointForTesting = server.url();
        handler.samplingRate = 0.5;

        server.respond(200, "{\"success\":false}");
        assertEquals(AvoNetworkCallsHandler.SendResult.OK, handler.send(eventWithPadding(1), "test-key"));
        assertEquals(0.5, handler.samplingRate, 0.0);

        server.respond(200, "{\"ok\":false}");
        handler.send(eventWithPadding(1), "test-key");
        assertEquals(0.5, handler.samplingRate, 0.0);

        server.respond(200, "{\"samplingRate\":2}");
        handler.send(eventWithPadding(1), "test-key");
        assertEquals(0.5, handler.samplingRate, 0.0);

        server.respond(200, "{\"samplingRate\":\"0.3\"}");
        handler.send(eventWithPadding(1), "test-key");
        assertEquals(0.5, handler.samplingRate, 0.0);

        server.respond(500, "{\"samplingRate\":0.1}");
        assertEquals(AvoNetworkCallsHandler.SendResult.NON_200, handler.send(eventWithPadding(1), "test-key"));
        assertEquals(0.5, handler.samplingRate, 0.0);

        server.respond(200, "not json");
        handler.send(eventWithPadding(1), "test-key");
        assertEquals(0.5, handler.samplingRate, 0.0);

        server.respond(200, "{\"samplingRate\":0.25,\"success\":true}");
        handler.send(eventWithPadding(1), "test-key");
        assertEquals(0.25, handler.samplingRate, 0.0);

        server.respond(200, "{\"samplingRate\":0}");
        handler.send(eventWithPadding(1), "test-key");
        assertEquals(0.0, handler.samplingRate, 0.0);
    }

    @Test
    public void successFalseLeavesTheRateSoLaterEventsStillSend() throws Exception {
        // Main read getDouble("samplingRate") and threw on {"success":false}.
        server.respond(200, "{\"success\":false}");
        AvoInspector inspector = inspector(AvoInspectorEnv.Dev, 1);

        inspector.trackSchemaFromEvent("Event", Collections.<String, Object>singletonMap("a", 1));
        inspector.flush();
        inspector.trackSchemaFromEvent("Event", Collections.<String, Object>singletonMap("a", 1));
        inspector.flush();

        assertEquals(2, server.requests().size());
    }

    @Test
    public void samplingIsPerEventAtEnqueue() throws Exception {
        AvoInspector dropping = inspector(AvoInspectorEnv.Staging, 10);
        dropping.setSamplingRateForTesting(0.0);
        for (int i = 0; i < 100; i++) {
            dropping.trackSchemaFromEvent("Event", Collections.<String, Object>emptyMap());
        }
        assertEquals(0, dropping.batcher.bufferedCount());
        dropping.flush();
        assertEquals(0, server.requests().size());

        AvoInspector keeping = inspector(AvoInspectorEnv.Staging, 10);
        keeping.setSamplingRateForTesting(1.0);
        for (int i = 0; i < 100; i++) {
            keeping.trackSchemaFromEvent("Event", Collections.<String, Object>emptyMap());
        }
        keeping.flush();
        int events = 0;
        for (MockInspectorServer.Request request : server.requests()) {
            events += request.body.length();
            assertEquals(1.0, request.body.getJSONObject(0).getDouble("samplingRate"), 0.0);
        }
        assertEquals(100, events);
    }

    @Test
    public void connectionFailureDropsTheBatchWithoutRequeue() throws Exception {
        AvoInspector inspector = inspector(AvoInspectorEnv.Staging, 2);
        String unreachable;
        try (ServerSocket socket = new ServerSocket(0)) {
            unreachable = "http://127.0.0.1:" + socket.getLocalPort() + "/";
        }
        inspector.networkCallsHandler.endpointForTesting = unreachable;

        inspector.trackSchemaFromEvent("E1", Collections.<String, Object>emptyMap());
        inspector.trackSchemaFromEvent("E2", Collections.<String, Object>emptyMap());
        inspector.flush();

        inspector.networkCallsHandler.endpointForTesting = server.url();
        inspector.trackSchemaFromEvent("E3", Collections.<String, Object>emptyMap());
        inspector.flush();

        assertEquals(1, server.requests().size());
        assertEquals(1, server.requests().get(0).body.length());
        assertEquals("E3", server.requests().get(0).body.getJSONObject(0).getString("eventName"));
    }

    @Test
    public void droppedConnectionMidRequestIsAFailedSend() throws Exception {
        try (final ServerSocket socket = new ServerSocket(0)) {
            Thread acceptor = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (Socket client = socket.accept()) {
                        client.getInputStream().read(new byte[64]);
                    } catch (Exception ignored) {
                    }
                }
            });
            acceptor.start();

            AvoNetworkCallsHandler handler = new AvoNetworkCallsHandler("dev");
            handler.endpointForTesting = "http://127.0.0.1:" + socket.getLocalPort() + "/";
            assertEquals(AvoNetworkCallsHandler.SendResult.FAILED, handler.send(eventWithPadding(1), "test-key"));
            acceptor.join();
        }
    }

    @Test(timeout = 20_000)
    public void unansweredRequestTimesOutAfterTenSeconds() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            AvoNetworkCallsHandler handler = new AvoNetworkCallsHandler("dev");
            handler.endpointForTesting = "http://127.0.0.1:" + socket.getLocalPort() + "/";

            java.io.PrintStream originalErr = System.err;
            java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
            System.setErr(new java.io.PrintStream(captured, true, "UTF-8"));
            long start = System.currentTimeMillis();
            try {
                // The backlog completes the TCP handshake; nobody ever answers.
                assertEquals(AvoNetworkCallsHandler.SendResult.FAILED, handler.send(eventWithPadding(1), "test-key"));
            } finally {
                System.setErr(originalErr);
            }
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("elapsed " + elapsed, elapsed >= 9_000 && elapsed < 15_000);
            // Reported whatever the logging flag (SPEC.md §7.5).
            assertTrue(captured.toString("UTF-8").contains("Avo Inspector: schema sending failed: Request timed out."));
        }
    }

    @Test
    public void bodyIsUtf8() throws Exception {
        AvoInspector inspector = inspector(AvoInspectorEnv.Dev, 1);
        inspector.trackSchemaFromEvent("Événement ✓", Collections.<String, Object>emptyMap());
        inspector.flush();
        MockInspectorServer.Request request = server.awaitRequest(0, 5000);
        assertNotNull(request);
        assertEquals("Événement ✓", request.body.getJSONObject(0).getString("eventName"));
        assertTrue(new String(request.rawBody, StandardCharsets.UTF_8).contains("Événement ✓"));
    }
}
