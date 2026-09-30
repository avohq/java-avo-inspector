package is.avo.inspector;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}
