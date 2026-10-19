package is.avo.inspector;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// No test may reach api.avo.app.
public class NoRealHostTests {

    @Test
    public void sendsWithoutAMockGoToADeadLocalPort() {
        for (AvoInspectorEnv env : AvoInspectorEnv.values()) {
            AvoInspector inspector = new AvoInspector("key", "1.0.0", "App", env);
            try {
                assertEquals(NoRealHostListener.DEAD_ENDPOINT, inspector.networkCallsHandler.endpoint());
            } finally {
                inspector.destroy();
            }
        }
    }

    @Test
    public void childJvmsInheritADeadMockEndpoint() {
        assertEquals("http://127.0.0.1:1", System.getenv(AvoNetworkCallsHandler.MOCK_ENDPOINT_ENV_VAR));
    }

    @Test(timeout = 20_000)
    public void aProdInstanceSendsToTheClosedPortAndFailsFast() throws Exception {
        // prod ignores AVO_INSPECTOR_MOCK_ENDPOINT, so only the in-suite guard keeps this send local.
        AvoInspector prod = new AvoInspector("key", "1.0.0", "App", AvoInspectorEnv.Prod);
        java.io.PrintStream originalErr = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true, "UTF-8"));
        AvoLog.resetForTesting();
        long start = System.nanoTime();
        try {
            String endpoint = prod.networkCallsHandler.endpoint();
            assertEquals("prod send would go to " + endpoint, NoRealHostListener.DEAD_ENDPOINT, endpoint);
            prod.trackSchemaFromEvent(InspectorEvent.builder().eventName("Guarded").eventProperties(Collections.<String, Object>singletonMap("a", 1)).build());
            prod.flush();
        } finally {
            System.setErr(originalErr);
            prod.destroy();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        String stderr = new String(captured.toByteArray(), "UTF-8");
        assertTrue("took " + elapsedMs + " ms", elapsedMs < 5_000);
        assertTrue(stderr, stderr.contains("Avo Inspector: schema sending failed: Request failed."));
        assertEquals(0, prod.batcher.pendingCount());
    }
}
