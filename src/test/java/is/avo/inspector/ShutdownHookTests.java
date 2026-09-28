package is.avo.inspector;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The per-instance shutdown hook flushes what is buffered when the JVM exits normally.
public class ShutdownHookTests {

    private MockInspectorServer server;

    @Before
    public void setUp() throws Exception {
        server = new MockInspectorServer();
    }

    @After
    public void tearDown() {
        server.close();
    }

    // Runs in a child JVM: tracks one event and returns from main without flush().
    public static final class TrackAndExit {
        public static void main(String[] args) {
            AvoInspector inspector = new AvoInspector(AvoInspectorOptions.builder()
                    .apiKey("test-key").appVersion("1.0.0").env(AvoInspectorEnv.Staging).build());
            inspector.networkCallsHandler.endpointForTesting = args[0];
            inspector.trackSchemaFromEvent("Buffered At Exit", Collections.<String, Object>singletonMap("a", 1));
            if (args.length > 1 && "destroy".equals(args[1])) {
                inspector.destroy();
            }
            if (args.length > 1 && "wait".equals(args[1])) {
                System.out.println("ready");
                System.out.flush();
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    // Runs in a child JVM: uses and destroys instances, idles past the keep-alive, then reports.
    public static final class UseDestroyAndIdle {
        public static void main(String[] args) throws Exception {
            for (AvoInspectorEnv env : new AvoInspectorEnv[]{AvoInspectorEnv.Dev, AvoInspectorEnv.Staging}) {
                AvoInspector inspector = new AvoInspector("test-key", "1.0.0", "App", env);
                inspector.networkCallsHandler.endpointForTesting = args[0];
                inspector.trackSchemaFromEvent("E", Collections.<String, Object>singletonMap("a", 1));
                inspector.flush();
                inspector.destroy();
            }
            Thread.sleep(Long.parseLong(args[1]));
            int threads = 0;
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.getName().startsWith("avo-inspector")) {
                    threads++;
                }
            }
            System.out.println("threads=" + threads + " hook=" + AvoBatcher.isShutdownHookInstalledForTesting());
        }
    }

    private static List<String> childCommand(String... args) {
        List<String> command = new java.util.ArrayList<>(Arrays.asList(
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                "-cp", System.getProperty("java.class.path"),
                TrackAndExit.class.getName()));
        command.addAll(Arrays.asList(args));
        return command;
    }

    private int runChild(String... args) throws Exception {
        Process process = new ProcessBuilder(childCommand(args)).inheritIO().start();
        assertTrue("child JVM did not exit", process.waitFor(30, TimeUnit.SECONDS));
        return process.exitValue();
    }

    @Test(timeout = 60_000)
    public void bufferedEventIsSentWhenMainReturnsWithoutFlush() throws Exception {
        assertEquals(0, runChild(server.url()));

        assertEquals(1, server.requests().size());
        assertEquals("Buffered At Exit", server.requests().get(0).body.getJSONObject(0).getString("eventName"));
    }

    @Test(timeout = 60_000)
    public void bufferedEventIsSentOnSigterm() throws Exception {
        Process process = new ProcessBuilder(childCommand(server.url(), "wait"))
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        java.io.BufferedReader stdout = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
        assertEquals("ready", stdout.readLine());
        assertEquals(0, server.requests().size());

        process.destroy(); // SIGTERM on Unix-like systems
        assertTrue("child JVM did not exit", process.waitFor(30, TimeUnit.SECONDS));

        assertEquals(1, server.requests().size());
    }

    private static void awaitCollected(java.lang.ref.WeakReference<?> ref) throws InterruptedException {
        for (int i = 0; i < 100 && ref.get() != null; i++) {
            System.gc();
            byte[][] pressure = new byte[16][];
            for (int j = 0; j < pressure.length; j++) {
                pressure[j] = new byte[1 << 20];
            }
            Thread.sleep(20);
        }
    }

    @Test(timeout = 30_000)
    public void anUndestroyedIdleInstanceStaysGarbageCollectable() throws Exception {
        java.lang.ref.WeakReference<AvoInspector> ref = new java.lang.ref.WeakReference<>(
                new AvoInspector("key", "1.0.0", "App", AvoInspectorEnv.Staging));
        awaitCollected(ref);
        assertTrue("the shutdown flush keeps an idle instance reachable", ref.get() == null);
    }

    @Test(timeout = 30_000)
    public void aDrainedInstanceStaysGarbageCollectable() throws Exception {
        AvoInspector inspector = new AvoInspector("key", "1.0.0", "App", AvoInspectorEnv.Staging);
        inspector.networkCallsHandler.endpointForTesting = server.url();
        inspector.trackSchemaFromEvent("E", Collections.<String, Object>emptyMap());
        inspector.flush();
        java.lang.ref.WeakReference<AvoBatcher> ref = new java.lang.ref.WeakReference<>(inspector.batcher);
        inspector = null;
        awaitCollected(ref);
        assertTrue("a drained batcher is still registered", ref.get() == null);
    }

    @Test(timeout = 30_000)
    public void bufferedEventsSurviveTheirInstanceBeingCollected() throws Exception {
        AvoInspector inspector = new AvoInspector(AvoInspectorOptions.builder().apiKey("test-key").appVersion("1.0.0")
                .env(AvoInspectorEnv.Staging).disableBatchTimer(true).build());
        inspector.networkCallsHandler.endpointForTesting = server.url();
        inspector.trackSchemaFromEvent("Orphaned", Collections.<String, Object>emptyMap());
        java.lang.ref.WeakReference<AvoInspector> ref = new java.lang.ref.WeakReference<>(inspector);
        inspector = null;
        // Give the collector every chance: with pending events the instance must stay reachable.
        awaitCollected(ref);

        // What the JVM shutdown hook runs.
        AvoBatcher.flushAllAtShutdown(5000);

        assertEquals(1, server.requests().size());
        assertEquals("Orphaned", server.requests().get(0).body.getJSONObject(0).getString("eventName"));
    }

    @Test(timeout = 30_000)
    public void backToBackSendsDoNotChurnTheShutdownHook() {
        AvoInspector inspector = new AvoInspector("test-key", "1.0.0", "App", AvoInspectorEnv.Dev);
        inspector.networkCallsHandler.endpointForTesting = server.url();
        int adds;
        int removals;
        synchronized (AvoBatcher.class) {
            adds = AvoBatcher.hookAddsForTesting;
            removals = AvoBatcher.hookRemovalsForTesting;
        }
        // Each dev event registers and then drains the batcher.
        for (int i = 0; i < 20; i++) {
            inspector.trackSchemaFromEvent("E" + i, Collections.<String, Object>emptyMap());
            inspector.flush();
        }
        assertTrue("hook adds: " + (AvoBatcher.hookAddsForTesting - adds), AvoBatcher.hookAddsForTesting - adds <= 1);
        assertEquals(0, AvoBatcher.hookRemovalsForTesting - removals);

        // destroy() with nothing left to flush removes it at once.
        inspector.destroy();
        assertFalse(AvoBatcher.isShutdownHookInstalledForTesting());
        assertEquals(20, server.requests().size());
    }

    @Test
    public void destroyUnregistersFromTheShutdownFlush() {
        AvoInspector inspector = new AvoInspector("key", "1.0.0", "App", AvoInspectorEnv.Staging);
        inspector.trackSchemaFromEvent("E", Collections.<String, Object>emptyMap());
        assertTrue(AvoBatcher.isRegisteredForShutdownFlush(inspector.batcher));
        inspector.destroy();
        assertFalse(AvoBatcher.isRegisteredForShutdownFlush(inspector.batcher));
    }

    @Test(timeout = 60_000)
    public void anIdleJvmWithAllInstancesDestroyedHoldsNoThreadOrHook() throws Exception {
        List<String> command = new java.util.ArrayList<>(Arrays.asList(
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                "-cp", System.getProperty("java.class.path"),
                UseDestroyAndIdle.class.getName(), server.url(), "7000"));
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        java.io.BufferedReader stdout = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
        String report = null;
        for (String line = stdout.readLine(); line != null; line = stdout.readLine()) {
            if (line.startsWith("threads=")) {
                report = line;
            }
        }
        assertTrue("child JVM did not exit", process.waitFor(30, TimeUnit.SECONDS));
        assertEquals("threads=0 hook=false", report);
    }

    @Test(timeout = 60_000)
    public void destroyedInstanceSendsNothingAtExit() throws Exception {
        assertEquals(0, runChild(server.url(), "destroy"));

        assertEquals(0, server.requests().size());
    }
}
