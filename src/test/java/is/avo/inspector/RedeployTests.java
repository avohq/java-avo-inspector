package is.avo.inspector;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

// A webapp that uses the SDK and is undeployed without destroy(): once its class loader is closed
// the SDK's threads must not load classes from it, and nothing may keep it reachable.
public class RedeployTests {

    private MockInspectorServer server;
    private java.io.PrintStream originalErr;
    // The undeployed instance's network handler, for tests that do not check it is collected.
    private Object lastHandler;
    private java.io.ByteArrayOutputStream captured;
    private final List<String> uncaught = Collections.synchronizedList(new ArrayList<String>());
    private Thread.UncaughtExceptionHandler previousHandler;

    @Before
    public void setUp() throws Exception {
        server = new MockInspectorServer();
        originalErr = System.err;
        captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true, "UTF-8"));
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                uncaught.add(thread.getName() + ": " + error);
            }
        });
    }

    @After
    public void tearDown() {
        System.setErr(originalErr);
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        server.close();
    }

    private static URL jarOf(Class<?> type) throws Exception {
        return type.getProtectionDomain().getCodeSource().getLocation();
    }

    // flush: undeploy after flush(); otherwise the 0.3 s flush timer sends after the loader is closed.
    private WeakReference<ClassLoader> deployUseAndUndeploy(boolean flush) throws Exception {
        return deployUseAndUndeploy("Staging", 5, flush);
    }

    private WeakReference<ClassLoader> deployUseAndUndeploy(String env, int events, boolean flush) throws Exception {
        String sdkJar = System.getProperty("avo.sdk.jar");
        assertNotNull("avo.sdk.jar is set by the Gradle test task", sdkJar);
        URLClassLoader loader = new URLClassLoader(new URL[]{new File(sdkJar).toURI().toURL(), jarOf(org.json.JSONObject.class)}, null);
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        current.setContextClassLoader(loader);
        try {
            Class<?> inspectorClass = loader.loadClass("is.avo.inspector.AvoInspector");
            Class<?> envClass = loader.loadClass("is.avo.inspector.AvoInspectorEnv");
            Class<?> optionsClass = loader.loadClass("is.avo.inspector.AvoInspectorOptions");
            Object staging = envClass.getMethod("valueOf", String.class).invoke(null, env);
            Object builder = optionsClass.getMethod("builder").invoke(null);
            Class<?> builderClass = builder.getClass();
            builderClass.getMethod("apiKey", String.class).invoke(builder, "key");
            builderClass.getMethod("appVersion", String.class).invoke(builder, "1.0.0");
            builderClass.getMethod("appName", String.class).invoke(builder, "webapp");
            builderClass.getMethod("env", envClass).invoke(builder, staging);
            builderClass.getMethod("batchFlushSeconds", double.class).invoke(builder, 0.3);
            Object inspector = inspectorClass.getConstructor(optionsClass).newInstance(builderClass.getMethod("build").invoke(builder));
            Object handler = field(inspector, "networkCallsHandler");
            java.lang.reflect.Field endpoint = handler.getClass().getDeclaredField("endpointForTesting");
            endpoint.setAccessible(true);
            endpoint.set(handler, server.url());
            lastHandler = handler;
            Method track = inspectorClass.getMethod("trackSchemaFromEvent", String.class, Map.class);
            for (int i = 0; i < events; i++) {
                track.invoke(inspector, "Redeploy", Collections.<String, Object>singletonMap("a", i));
            }
            if (flush) {
                inspectorClass.getMethod("flush").invoke(inspector);
            }
        } finally {
            current.setContextClassLoader(previous);
        }
        WeakReference<ClassLoader> ref = new WeakReference<ClassLoader>(loader);
        loader.close();
        return ref;
    }

    private static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @Test(timeout = 60_000)
    public void anUndeployedWebappWithoutDestroyLeavesNothingBehind() throws Exception {
        assertUndeployLeavesNothingBehind(deployUseAndUndeploy(true));
    }

    @Test(timeout = 60_000)
    public void aTimerFlushAfterUndeployNeedsNoClassFromTheClosedLoader() throws Exception {
        assertUndeployLeavesNothingBehind(deployUseAndUndeploy(false));
    }

    // Dev sends each event at once; the loader is closed while those sends (and their responses)
    // are still being handled.
    @Test(timeout = 60_000)
    public void devSendsAnsweredAfterUndeployAreDeliveredWithoutErrors() throws Exception {
        // Every response arrives after the loader is closed, so the first one is parsed then.
        server.holdResponses();
        WeakReference<ClassLoader> loader = deployUseAndUndeploy("Dev", 50, false);
        server.releaseResponses();
        assertUndeployLeavesNothingBehind(loader, 50);
    }

    // The response parser runs for the first time after the loader is closed, and still works.
    @Test(timeout = 60_000)
    public void aResponseParsedAfterUndeployStillSetsTheSamplingRate() throws Exception {
        server.holdResponses();
        server.respond(200, "{\"samplingRate\":0.7}");
        deployUseAndUndeploy("Dev", 3, false);
        server.releaseResponses();
        long sentBy = System.currentTimeMillis() + 5_000;
        while (eventsReceived() < 3 && System.currentTimeMillis() < sentBy) {
            Thread.sleep(50);
        }
        Thread.sleep(500);
        java.lang.reflect.Field rate = lastHandler.getClass().getDeclaredField("samplingRate");
        rate.setAccessible(true);
        assertEquals(0.7, rate.getDouble(lastHandler), 0.0);
        lastHandler = null;
    }

    private void assertUndeployLeavesNothingBehind(WeakReference<ClassLoader> loader) throws Exception {
        assertUndeployLeavesNothingBehind(loader, 5);
    }

    private int eventsReceived() {
        int events = 0;
        for (MockInspectorServer.Request request : server.requests()) {
            events += request.body.length();
        }
        return events;
    }

    private void assertUndeployLeavesNothingBehind(WeakReference<ClassLoader> loader, int expected) throws Exception {
        long sentBy = System.currentTimeMillis() + 5_000;
        while (eventsReceived() < expected && System.currentTimeMillis() < sentBy) {
            Thread.sleep(50);
        }
        // Let the last responses be handled.
        Thread.sleep(500);
        assertEquals(expected, eventsReceived());
        String stderr = captured.toString("UTF-8");
        org.junit.Assert.assertFalse(stderr, stderr.contains("dropped"));
        org.junit.Assert.assertFalse(stderr, stderr.contains("something went wrong"));
        lastHandler = null;

        long deadline = System.currentTimeMillis() + 30_000;
        while (loader.get() != null && System.currentTimeMillis() < deadline) {
            System.gc();
            Thread.sleep(250);
        }
        assertEquals(Collections.emptyList(), new ArrayList<>(uncaught));
        assertNull("the undeployed webapp's class loader is still reachable", loader.get());
    }
}
