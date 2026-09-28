package is.avo.inspector;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// The conformance harness, run as the suite runner runs it: one JSON line in, one out.
public class HarnessTests {

    private static String runHarness(String envelope) throws Exception {
        return runHarness(envelope, new int[1]);
    }

    private static String runHarness(String envelope, int[] exitCode) throws Exception {
        List<String> command = Arrays.asList(
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                // A platform whose default charset cannot encode the output.
                "-Dfile.encoding=US-ASCII", "-Dstdout.encoding=US-ASCII", "-Dsun.stdout.encoding=US-ASCII",
                "-cp", System.getProperty("java.class.path") + File.pathSeparator + System.getProperty("avo.conformance.classpath"),
                "is.avo.inspector.ConformanceHarness");
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().put("LC_ALL", "C");
        builder.environment().put("LANG", "C");
        Process process = builder.start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write((envelope + "\n").getBytes(StandardCharsets.UTF_8));
        }
        byte[] stdout = MockInspectorServer.readAll(process.getInputStream());
        assertTrue("harness did not exit", process.waitFor(30, TimeUnit.SECONDS));
        exitCode[0] = process.exitValue();
        return new String(stdout, StandardCharsets.UTF_8).trim();
    }

    @Test(timeout = 60_000)
    public void stdoutIsUtf8WhateverThePlatformCharset() throws Exception {
        String output = runHarness("{\"suite\":\"schema-extraction\",\"fixture_id\":\"utf8\","
                + "\"constructor\":{\"apiKey\":\"k\",\"env\":\"dev\",\"version\":\"1.0.0\"},"
                + "\"input\":{\"prénom ✓ 名前\":\"x\"}}");

        JSONObject envelope = new JSONObject(output);
        assertEquals("utf8", envelope.getString("fixture_id"));
        assertEquals("prénom ✓ 名前", envelope.getJSONArray("actual").getJSONObject(0).getString("propertyName"));
    }

    @Test(timeout = 60_000)
    public void aSequenceStepThatIsNotAnObjectIsAConfigError() throws Exception {
        int[] exitCode = new int[1];
        String output = runHarness("{\"suite\":\"batching\",\"fixture_id\":\"bad-step\",\"operation\":\"sequence\","
                + "\"constructor\":{\"apiKey\":\"k\",\"env\":\"dev\",\"version\":\"1.0.0\"},"
                + "\"steps\":[42]}", exitCode);

        JSONObject envelope = new JSONObject(output);
        assertEquals(2, exitCode[0]);
        assertEquals("bad-step", envelope.getString("fixture_id"));
        assertEquals("sequence step is not an object", envelope.getString("error"));
    }

    @Test(timeout = 60_000)
    public void aConstructorFieldThatIsNotAStringIsAConfigError() throws Exception {
        int[] exitCode = new int[1];
        String output = runHarness("{\"suite\":\"schema-extraction\",\"fixture_id\":\"bad-ctor\","
                + "\"constructor\":{\"apiKey\":42,\"env\":\"dev\",\"version\":\"1.0.0\"},\"input\":{}}", exitCode);

        assertEquals(2, exitCode[0]);
        assertEquals("constructor.apiKey must be a string", new JSONObject(output).getString("error"));
    }

    @Test(timeout = 60_000)
    public void aNullConstructorFieldStillReachesTheSdk() throws Exception {
        int[] exitCode = new int[1];
        String output = runHarness("{\"suite\":\"schema-extraction\",\"fixture_id\":\"null-ctor\","
                + "\"constructor\":{\"apiKey\":null,\"env\":\"dev\",\"version\":\"1.0.0\"},\"input\":{}}", exitCode);

        assertEquals(1, exitCode[0]);
        assertTrue(new JSONObject(output).getString("error").startsWith("Constructor threw: "));
    }

    @Test(timeout = 60_000)
    public void aTrackOptionThatIsNotAStringIsAConfigError() throws Exception {
        int[] exitCode = new int[1];
        String output = runHarness("{\"suite\":\"wire-protocol\",\"fixture_id\":\"bad-option\",\"operation\":\"trackSchemaFromEvent\","
                + "\"constructor\":{\"apiKey\":\"k\",\"env\":\"dev\",\"version\":\"1.0.0\"},"
                + "\"input\":{\"eventName\":\"E\",\"eventProperties\":{},\"options\":{\"originHint\":7}}}", exitCode);

        assertEquals(2, exitCode[0]);
        assertEquals("options.originHint must be a string", new JSONObject(output).getString("error"));
    }
}
