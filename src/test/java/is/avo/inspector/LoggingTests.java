package is.avo.inspector;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// SPEC.md §7.5 / §4.2: failures are reported whatever the logging flag; the rest stays opt-in.
public class LoggingTests {

    private static final String API_KEY = "secret-key-4f2a";

    private MockInspectorServer server;
    private final List<AvoInspector> inspectors = new ArrayList<>();
    private PrintStream originalErr;
    private ByteArrayOutputStream captured;

    @Before
    public void setUp() throws Exception {
        server = new MockInspectorServer();
        originalErr = System.err;
        captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, "UTF-8"));
        AvoLog.resetForTesting();
        now = 0;
        AvoLog.clockForTesting = new AvoLog.Clock() {
            @Override
            public long nanoTime() {
                return now;
            }
        };
    }

    // The limiter's clock, stepped by the tests instead of sleeping.
    private volatile long now;

    private void pastTheWindow() {
        now += java.util.concurrent.TimeUnit.SECONDS.toNanos(11);
    }

    @After
    public void tearDown() {
        System.setErr(originalErr);
        AvoLog.clockForTesting = null;
        AvoLog.resetForTesting();
        for (AvoInspector inspector : inspectors) {
            inspector.destroy();
        }
        server.close();
        AvoInspector.enableLogging(false);
    }

    private String stderr() {
        return new String(captured.toByteArray(), StandardCharsets.UTF_8);
    }

    private AvoInspector inspector(AvoInspectorEnv env) {
        AvoInspector inspector = new AvoInspector(AvoInspectorOptions.builder()
                .apiKey(API_KEY).appVersion("1.0.0").env(env).batchSize(1).build());
        inspector.networkCallsHandler.endpointForTesting = server.url();
        inspectors.add(inspector);
        AvoInspector.enableLogging(false);
        return inspector;
    }

    private static String closedPortUrl() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/";
        }
    }

    @Test
    public void networkErrorIsLoggedWithLoggingOff() throws Exception {
        AvoInspector inspector = inspector(AvoInspectorEnv.Prod);
        inspector.networkCallsHandler.endpointForTesting = closedPortUrl();

        inspector.trackSchemaFromEvent("Event", Collections.<String, Object>emptyMap());
        inspector.flush();

        assertTrue(stderr(), stderr().contains("Avo Inspector: schema sending failed: Request failed."));
        assertFalse(stderr().contains(API_KEY));
    }

    @Test
    public void headerGuardFailureIsLoggedWithLoggingOff() {
        AvoNetworkCallsHandler handler = new AvoNetworkCallsHandler("prod");
        handler.endpointForTesting = server.url();

        handler.send(Collections.singletonList(Collections.<String, Object>emptyMap()), "secret\r\nkey");

        assertTrue(stderr(), stderr().contains("Avo Inspector: schema sending failed: Request failed."));
        assertFalse(stderr().contains("secret"));
        assertEquals(0, server.requests().size());
    }

    @Test
    public void internalErrorIsLoggedWithLoggingOff() {
        AvoInspector inspector = inspector(AvoInspectorEnv.Prod);
        Map<String, Object> broken = new java.util.AbstractMap<String, Object>() {
            @Override
            public java.util.Set<Entry<String, Object>> entrySet() {
                throw new IllegalStateException("boom");
            }
        };

        assertTrue(inspector.trackSchemaFromEvent("Event", broken).isEmpty());

        assertTrue(stderr(), stderr().contains("Avo Inspector: something went wrong. Please report to support@avo.app."));
        assertFalse(stderr().contains(API_KEY));
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    public void aQueueFullBurstLogsOneLineThenTheCarriedCount() throws Exception {
        AvoInspector inspector = new AvoInspector(AvoInspectorOptions.builder().apiKey(API_KEY).appVersion("1.0.0")
                .env(AvoInspectorEnv.Prod).batchSize(1000).maxQueueSize(1).disableBatchTimer(true).build());
        inspectors.add(inspector);
        AvoInspector.enableLogging(false);
        captured.reset();

        for (int i = 0; i < 20; i++) {
            inspector.trackSchemaFromEvent("Event", Collections.singletonMap("email", "alice@example.com"));
        }
        assertEquals(stderr(), "Avo Inspector: dropped 1 event(s) (queue full) in the last 10s.\n", stderr());

        pastTheWindow();
        inspector.trackSchemaFromEvent("Event", Collections.<String, Object>emptyMap());
        assertTrue(stderr(), stderr().endsWith("Avo Inspector: dropped 19 event(s) (queue full) in the last 10s.\n"));
        assertFalse(stderr().contains(API_KEY));
        assertFalse(stderr().contains("alice@example.com"));
    }

    @Test
    public void aBacklogOverflowReportsItsWholeCountInTheFirstLine() {
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AvoBatcher.maxWaitingEvents = 2;
        AvoBatcher batcher = new AvoBatcher(new AvoBatcher.Sender() {
            @Override
            public AvoNetworkCallsHandler.SendResult send(java.util.List<Map<String, Object>> events, String apiKey) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return AvoNetworkCallsHandler.SendResult.OK;
            }
        }, 10, 30, 1000, true);
        try {
            // 4 sends of 10 run; the fifth batch of 10 exceeds the backlog of 2 by 8.
            for (int i = 0; i < 50; i++) {
                batcher.enqueue(AvoBatcherTests.event("E" + i));
            }
            assertEquals(stderr(), "Avo Inspector: dropped 8 event(s) (send backlog full) in the last 10s.\n", stderr());
        } finally {
            AvoBatcher.maxWaitingEvents = AvoBatcher.MAX_WAITING_EVENTS;
            release.countDown();
            batcher.destroy();
        }
    }

    @Test
    public void non200IsLoggedOncePerStatusPerWindow() throws Exception {
        AvoInspector inspector = inspector(AvoInspectorEnv.Prod);
        server.respond(500, "{}");
        for (int i = 0; i < 5; i++) {
            inspector.trackSchemaFromEvent("Event", Collections.<String, Object>emptyMap());
            inspector.flush();
        }
        server.respond(400, "{}");
        inspector.trackSchemaFromEvent("Event", Collections.<String, Object>emptyMap());
        inspector.flush();
        assertEquals(stderr(), 1, count(stderr(), "Avo Inspector: 1 batch(es) rejected with HTTP 500 in the last 10s."));
        assertEquals(stderr(), 1, count(stderr(), "Avo Inspector: 1 batch(es) rejected with HTTP 400 in the last 10s."));

        pastTheWindow();
        server.respond(500, "{}");
        inspector.trackSchemaFromEvent("Event", Collections.<String, Object>emptyMap());
        inspector.flush();
        assertTrue(stderr(), stderr().contains("Avo Inspector: 5 batch(es) rejected with HTTP 500 in the last 10s."));
        assertFalse(stderr().contains(API_KEY));
    }

    @Test
    public void failedSendsLogOneLinePerWindowWithTheSuppressedCount() throws Exception {
        AvoInspector inspector = inspector(AvoInspectorEnv.Prod);
        inspector.networkCallsHandler.endpointForTesting = closedPortUrl();
        for (int i = 0; i < 5; i++) {
            inspector.trackSchemaFromEvent("Event", Collections.<String, Object>emptyMap());
            inspector.flush();
        }
        assertEquals(stderr(), "Avo Inspector: schema sending failed: Request failed.\n", stderr());

        pastTheWindow();
        inspector.trackSchemaFromEvent("Event", Collections.<String, Object>emptyMap());
        inspector.flush();
        assertTrue(stderr(), stderr().endsWith("Avo Inspector: schema sending failed: Request failed. (4 more in the last 10s)\n"));
    }

    @Test
    public void internalErrorsLogOneLinePerWindowWithTheSuppressedCount() {
        AvoInspector inspector = inspector(AvoInspectorEnv.Prod);
        Map<String, Object> broken = new java.util.AbstractMap<String, Object>() {
            @Override
            public java.util.Set<Entry<String, Object>> entrySet() {
                throw new IllegalStateException("boom");
            }
        };
        for (int i = 0; i < 5; i++) {
            inspector.trackSchemaFromEvent("Event", broken);
        }
        assertEquals(stderr(), 1, count(stderr(), "something went wrong"));

        pastTheWindow();
        inspector.trackSchemaFromEvent("Event", broken);
        assertTrue(stderr(), stderr().contains(
                "Avo Inspector: something went wrong. Please report to support@avo.app. (4 more in the last 10s) (java.lang.IllegalStateException)"));
    }

    @Test
    public void theStreamIdColonWarningIsRateLimited() {
        AvoInspector inspector = inspector(AvoInspectorEnv.Prod);
        for (int i = 0; i < 5; i++) {
            inspector.trackSchemaFromEvent("Event", Collections.<String, Object>emptyMap(), "user:42", null);
        }
        assertEquals(stderr(), 1, count(stderr(), "streamId contains ':'"));
        assertFalse(stderr(), stderr().contains("more in the last"));

        pastTheWindow();
        inspector.trackSchemaFromEvent("Event", Collections.<String, Object>emptyMap(), "user:42", null);
        assertTrue(stderr(), stderr().contains("streamId contains ':'; using the value verbatim. (4 more in the last 10s)"));
        assertFalse(stderr(), stderr().contains("user:42"));
    }

    @Test
    public void internalErrorsLogTheExceptionTypeNeverItsText() {
        AvoInspector inspector = inspector(AvoInspectorEnv.Prod);
        Map<String, Object> throwingMap = new java.util.AbstractMap<String, Object>() {
            @Override
            public java.util.Set<Entry<String, Object>> entrySet() {
                throw new IllegalStateException("MARKER-map-alice@example.com");
            }
        };
        Map<Object, Object> throwingKey = new java.util.HashMap<>();
        throwingKey.put(new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("MARKER-key-alice@example.com");
            }
        }, "value");

        inspector.trackSchemaFromEvent("Event", throwingMap);
        pastTheWindow();
        inspector.extractSchema(throwingKey);
        pastTheWindow();
        @SuppressWarnings("unchecked")
        Map<String, ?> keyMap = (Map<String, ?>) (Map<?, ?>) throwingKey;
        inspector.trackSchemaFromEvent("Event", keyMap);

        assertEquals(stderr(), 3, count(stderr(), "Avo Inspector: something went wrong. Please report to support@avo.app. (java.lang.IllegalStateException)"));
        assertFalse(stderr(), stderr().contains("MARKER"));
    }

    @Test
    @SuppressWarnings("ConstantConditions")
    public void aMissingEventNameIsSentAsAPlaceholder() throws Exception {
        String line = "Avo Inspector: %d event(s) tracked without an event name in the last 10s, sent as \"Missing Event Name\".";
        AvoInspector prod = inspector(AvoInspectorEnv.Prod);
        Map<String, AvoEventSchemaType> schema = prod.trackSchemaFromEvent(null, Collections.<String, Object>singletonMap("a", 1));
        prod.trackSchemaFromEvent("", Collections.<String, Object>singletonMap("b", "x"));
        prod.trackSchemaFromEvent("  ", Collections.<String, Object>singletonMap("c", true));
        prod.flush();

        assertEquals(1, schema.size());
        // Sends run concurrently, so compare the set of events, not their arrival order.
        assertEquals(3, server.requests().size());
        java.util.Set<String> sentEvents = new java.util.TreeSet<>();
        for (MockInspectorServer.Request request : server.requests()) {
            org.json.JSONObject event = request.body.getJSONObject(0);
            org.json.JSONObject property = event.getJSONArray("eventProperties").getJSONObject(0);
            sentEvents.add(event.getString("eventName") + "|" + property.getString("propertyName") + "|" + property.getString("propertyType"));
        }
        assertEquals(new java.util.TreeSet<>(java.util.Arrays.asList(
                "Missing Event Name|a|int", "Missing Event Name|b|string", "Missing Event Name|c|boolean")), sentEvents);
        assertEquals(stderr(), String.format(line, 1) + "\n", stderr());

        pastTheWindow();
        prod.trackSchema(null, Collections.<String, AvoEventSchemaType>singletonMap("d", new AvoEventSchemaType.AvoInt()));
        prod.flush();
        assertEquals(4, server.requests().size());
        assertTrue(stderr(), stderr().endsWith(String.format(line, 3) + "\n"));

        // Never throws, dev included; a valid name keeps its surrounding whitespace.
        AvoInspector dev = inspector(AvoInspectorEnv.Dev);
        assertEquals(1, dev.trackSchemaFromEvent(null, Collections.<String, Object>singletonMap("e", 1)).size());
        dev.trackSchemaFromEvent("  Signed Up ", Collections.<String, Object>emptyMap());
        dev.flush();
        assertEquals(6, server.requests().size());
        java.util.Set<String> devNames = new java.util.TreeSet<>();
        for (MockInspectorServer.Request request : server.requests().subList(4, 6)) {
            devNames.add(request.body.getJSONObject(0).getString("eventName"));
        }
        assertEquals(new java.util.TreeSet<>(java.util.Arrays.asList("Missing Event Name", "  Signed Up ")), devNames);
    }

    @Test
    public void samplingDropsPrintNothingWithLoggingOff() {
        AvoInspector inspector = inspector(AvoInspectorEnv.Prod);
        inspector.setSamplingRateForTesting(0.0);
        for (int i = 0; i < 10; i++) {
            inspector.trackSchemaFromEvent("Event", Collections.<String, Object>emptyMap());
        }
        assertEquals("", stderr());
    }

    @Test
    public void successfulSendIsSilentWithLoggingOff() throws Exception {
        AvoInspector inspector = inspector(AvoInspectorEnv.Prod);

        inspector.trackSchemaFromEvent("Event", Collections.<String, Object>singletonMap("a", 1));
        inspector.flush();

        assertEquals(1, server.requests().size());
        assertEquals("", stderr());
    }

    @Test
    public void queuedEventLogOnlyForEventsThatAreQueued() throws Exception {
        AvoInspector inspector = inspector(AvoInspectorEnv.Staging);
        AvoInspector.enableLogging(true);
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, "UTF-8"));
        try {
            inspector.setSamplingRateForTesting(0.0);
            inspector.trackSchemaFromEvent("Dropped", Collections.<String, Object>emptyMap());
            inspector.setSamplingRateForTesting(1.0);
            inspector.trackSchemaFromEvent("Kept", Collections.<String, Object>emptyMap());
        } finally {
            System.setOut(originalOut);
        }
        String stdout = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(stdout, stdout.contains("Avo Inspector: Queued event Kept"));
        assertFalse(stdout, stdout.contains("event Dropped with schema"));
        assertFalse(stdout, stdout.contains("Saved event"));
    }

    @Test
    public void loggedSchemasAreValidJson() throws Exception {
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("l", java.util.Arrays.asList(1, "a"));
        props.put("o", Collections.<String, Object>singletonMap("n", 1.5));
        props.put("s", "x");
        JSONArrayAssert expected = new JSONArrayAssert(Util.remapProperties(new AvoSchemaExtractor().extractSchema(props, false)));

        AvoInspector inspector = inspector(AvoInspectorEnv.Staging);
        AvoInspector.enableLogging(true);
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, "UTF-8"));
        try {
            inspector.trackSchemaFromEvent("Event", props);
            inspector.extractSchema(props);
        } finally {
            System.setOut(originalOut);
        }
        String stdout = new String(out.toByteArray(), StandardCharsets.UTF_8);

        expected.assertLogged(stdout, "Avo Inspector: Queued event Event with schema ");
        expected.assertLogged(stdout, "Avo Inspector: Parsed schema ");
        assertFalse(stdout, stdout.contains("list(int)[\"int\""));
    }

    // The schema logged after a prefix must be JSON equal to the wire eventProperties.
    private static final class JSONArrayAssert {
        final org.json.JSONArray expected;

        JSONArrayAssert(org.json.JSONArray expected) {
            this.expected = expected;
        }

        void assertLogged(String output, String prefix) {
            int start = output.indexOf(prefix);
            assertTrue(output, start >= 0);
            int end = output.indexOf('\n', start);
            String json = output.substring(start + prefix.length(), end < 0 ? output.length() : end);
            assertTrue("logged: " + json, expected.similar(new org.json.JSONArray(json)));
        }
    }

    @Test
    public void batchSizeAboveMaxQueueSizeAlwaysWarnsAndIsNotClamped() {
        AvoInspector inspector = new AvoInspector(AvoInspectorOptions.builder().apiKey(API_KEY).appVersion("1.0.0")
                .env(AvoInspectorEnv.Prod).batchSize(30).maxQueueSize(2).build());
        inspectors.add(inspector);

        // Not clamped: batch-4 requires FIFO overflow in this configuration.
        assertEquals(30, inspector.batchSize);
        assertTrue(stderr(), stderr().contains("Avo Inspector: batchSize 30 is larger than maxQueueSize 2"));
    }

    @Test
    public void batchSizeWithinMaxQueueSizeDoesNotWarn() {
        inspectors.add(new AvoInspector(AvoInspectorOptions.builder().apiKey(API_KEY).appVersion("1.0.0")
                .env(AvoInspectorEnv.Prod).batchSize(2).maxQueueSize(2).build()));
        inspectors.add(new AvoInspector(AvoInspectorOptions.builder().apiKey(API_KEY).appVersion("1.0.0")
                .env(AvoInspectorEnv.Dev).batchSize(30).maxQueueSize(2).build()));
        assertEquals("", stderr());
    }

    @Test(timeout = 20_000)
    public void aValueWhoseToStringThrowsDoesNotBreakTrackingInDev() throws Exception {
        AvoInspector inspector = inspector(AvoInspectorEnv.Dev);
        AvoInspector.enableLogging(true);
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("bad", new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("toString failed");
            }
        });
        props.put("ok", 1);
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"));
        Map<String, AvoEventSchemaType> schema;
        try {
            schema = inspector.trackSchemaFromEvent("Event", props);
        } finally {
            System.setOut(originalOut);
        }
        inspector.flush();

        assertEquals(2, schema.size());
        assertEquals(1, server.requests().size());
    }

    @Test(timeout = 20_000)
    public void logsShowTypesNeverPropertyValues() throws Exception {
        // A dev instance turns logging on for the whole process, prod instances included.
        inspectors.add(new AvoInspector("key", "1.0.0", "App", AvoInspectorEnv.Dev));
        AvoInspector prod = inspector(AvoInspectorEnv.Prod);
        AvoInspector.enableLogging(true);
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("email", "alice@example.com");
        props.put("nested", Collections.<String, Object>singletonMap("phone", "+44 7700 900123"));
        props.put("list", java.util.Arrays.asList("secret-token-1"));

        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, "UTF-8"));
        try {
            prod.trackSchemaFromEvent("Signed Up", props);
            prod.extractSchema(props);
            prod.flush();
        } finally {
            System.setOut(originalOut);
        }
        String logs = new String(out.toByteArray(), StandardCharsets.UTF_8) + stderr();

        assertTrue(logs, logs.contains("Avo Inspector: Queued event Signed Up with schema "));
        assertTrue(logs, logs.contains("\"propertyName\":\"email\""));
        for (String value : new String[]{"alice@example.com", "+44 7700 900123", "secret-token-1"}) {
            assertFalse(logs, logs.contains(value));
        }
    }

    @Test
    public void requestsAbandonedByDestroyAreNotReportedAsFailures() throws Exception {
        server.delayResponses(1000);
        AvoInspector inspector = inspector(AvoInspectorEnv.Dev);
        inspector.trackSchemaFromEvent("Event", Collections.<String, Object>emptyMap());
        server.awaitRequest(0, 5000);

        inspector.destroy();
        Thread.sleep(1500);

        assertFalse(stderr(), stderr().contains("schema sending failed"));
    }
}
