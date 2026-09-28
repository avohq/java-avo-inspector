package is.avo.inspector;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

// Before any test runs, sends that no test points at a mock go to a dead local port instead of
// api.avo.app, whatever the instance's env. Child JVMs get AVO_INSPECTOR_MOCK_ENDPOINT from the
// Gradle test task instead (build.gradle).
public class NoRealHostListener implements LauncherSessionListener {
    static final String DEAD_ENDPOINT = "http://127.0.0.1:1/";

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        AvoNetworkCallsHandler.endpointForAllTests = DEAD_ENDPOINT;
    }
}
