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

    @Test(timeout = 30_000)
    public void anUndestroyedInstanceStaysGarbageCollectable() throws Exception {
        java.lang.ref.WeakReference<AvoInspector> ref = new java.lang.ref.WeakReference<>(
                new AvoInspector("key", "1.0.0", "App", AvoInspectorEnv.Staging));
        for (int i = 0; i < 100 && ref.get() != null; i++) {
            System.gc();
            byte[][] pressure = new byte[16][];
            for (int j = 0; j < pressure.length; j++) {
                pressure[j] = new byte[1 << 20];
            }
            Thread.sleep(20);
        }
        assertTrue("the shutdown hook keeps the instance reachable", ref.get() == null);
    }

    @Test
    public void destroyUnregistersFromTheShutdownFlush() {
        AvoInspector inspector = new AvoInspector("key", "1.0.0", "App", AvoInspectorEnv.Staging);
        assertTrue(AvoBatcher.isRegisteredForShutdownFlush(inspector.batcher));
        inspector.destroy();
        assertFalse(AvoBatcher.isRegisteredForShutdownFlush(inspector.batcher));
    }

    @Test(timeout = 60_000)
    public void destroyedInstanceSendsNothingAtExit() throws Exception {
        assertEquals(0, runChild(server.url(), "destroy"));

        assertEquals(0, server.requests().size());
    }
}
