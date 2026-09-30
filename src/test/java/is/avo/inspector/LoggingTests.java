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
    }

    @After
    public void tearDown() {
        System.setErr(originalErr);
        AvoLog.windowMsForTesting = 0;
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

    @Test(timeout = 20_000)
    public void non200IsAlwaysLoggedAndRateLimited() throws Exception {
        AvoLog.windowMsForTesting = 500;
        server.respond(500, "{}");
        AvoInspector inspector = inspector(AvoInspectorEnv.Prod);

        for (int i = 0; i < 5; i++) {
            inspector.trackSchemaFromEvent("Event", Collections.<String, Object>emptyMap());
            inspector.flush();
        }
        assertEquals(5, server.requests().size());
        assertEquals(stderr(), 1, count(stderr(), "status 500"));

        Thread.sleep(1200);
        assertTrue(stderr(), stderr().contains("Avo Inspector: 4 more non-200 response(s)"));
        assertFalse(stderr().contains(API_KEY));
    }

    @Test(timeout = 20_000)
    public void droppedEventsAreAlwaysLoggedAndRateLimited() throws Exception {
        AvoLog.windowMsForTesting = 500;
        AvoInspector inspector = new AvoInspector(AvoInspectorOptions.builder().apiKey(API_KEY).appVersion("1.0.0")
                .env(AvoInspectorEnv.Prod).maxQueueSize(1).disableBatchTimer(true).build());
        inspectors.add(inspector);
        AvoInspector.enableLogging(false);
        captured.reset();

        for (int i = 0; i < 20; i++) {
            inspector.trackSchemaFromEvent("Event", Collections.<String, Object>emptyMap());
        }
        assertEquals(stderr(), 1, count(stderr(), "dropped 1 oldest"));

        Thread.sleep(1200);
        assertTrue(stderr(), stderr().contains("Avo Inspector: 18 more event(s) dropped"));
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
