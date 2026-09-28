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
}
