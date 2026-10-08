package is.avo.inspector;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// The conformance harness, run as the suite runner runs it: one JSON line in, one out.
public class HarnessTests {

    private static String runHarness(String envelope) throws Exception {
        return runHarness(envelope, new int[1]);
    }

    private static String runHarness(String envelope, int[] exitCode) throws Exception {
        return runHarness(envelope, exitCode, null);
    }

    private static String runHarness(String envelope, int[] exitCode, String mockEndpoint) throws Exception {
        List<String> command = Arrays.asList(
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                // A platform whose default charset cannot encode the output.
                "-Dfile.encoding=US-ASCII", "-Dstdout.encoding=US-ASCII", "-Dsun.stdout.encoding=US-ASCII",
                "-cp", System.getProperty("java.class.path") + File.pathSeparator + System.getProperty("avo.conformance.classpath"),
                "is.avo.inspector.ConformanceHarness");
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().put("LC_ALL", "C");
        builder.environment().put("LANG", "C");
        if (mockEndpoint != null) {
            builder.environment().put("AVO_INSPECTOR_MOCK_ENDPOINT", mockEndpoint);
        }
        final Process process = builder.start();
        try {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write((envelope + "\n").getBytes(StandardCharsets.UTF_8));
            }
            // Drained on another thread so a harness that hangs with stdout open still hits the timeout.
            FutureTask<byte[]> stdout = new FutureTask<>(new Callable<byte[]>() {
                @Override
                public byte[] call() throws Exception {
                    return MockInspectorServer.readAll(process.getInputStream());
                }
            });
            new Thread(stdout).start();
            assertTrue("harness did not exit", process.waitFor(30, TimeUnit.SECONDS));
            exitCode[0] = process.exitValue();
            return new String(stdout.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim();
        } finally {
            process.destroyForcibly();
        }
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

    @Test(timeout = 60_000)
    public void inputThatIsNotStrictJsonIsAConfigError() throws Exception {
        String ctor = "\"constructor\":{\"apiKey\":\"k\",\"env\":\"dev\",\"version\":\"1.0.0\"}";
        for (String input : Arrays.asList("{\"a\":\"\\u+041\"}", "{\"a\":01}", "{\"a\":1.}", "{\"a\":+1}")) {
            int[] exitCode = new int[1];
            String output = runHarness("{\"suite\":\"schema-extraction\",\"fixture_id\":\"lenient\"," + ctor + ",\"input\":" + input + "}", exitCode);

            assertEquals(input, 2, exitCode[0]);
            assertTrue(input, new JSONObject(output).getString("error").startsWith("could not parse input envelope"));
        }
    }

    @Test(timeout = 60_000)
    public void strictJsonNumbersAndEscapesStillParse() throws Exception {
        String output = runHarness("{\"suite\":\"schema-extraction\",\"fixture_id\":\"strict\","
                + "\"constructor\":{\"apiKey\":\"k\",\"env\":\"dev\",\"version\":\"1.0.0\"},"
                + "\"input\":{\"\\u0041b\":0,\"f\":-1.5e+3,\"g\":10E2}}");

        JSONObject envelope = new JSONObject(output);
        assertEquals("Ab", envelope.getJSONArray("actual").getJSONObject(0).getString("propertyName"));
        assertEquals("int", envelope.getJSONArray("actual").getJSONObject(0).getString("propertyType"));
        assertEquals("float", envelope.getJSONArray("actual").getJSONObject(1).getString("propertyType"));
        assertEquals("float", envelope.getJSONArray("actual").getJSONObject(2).getString("propertyType"));
    }

    @Test(timeout = 60_000)
    public void aPreconditionThatIsNotAnObjectIsAConfigError() throws Exception {
        int[] exitCode = new int[1];
        String output = runHarness("{\"suite\":\"schema-extraction\",\"fixture_id\":\"bad-precondition\","
                + "\"constructor\":{\"apiKey\":\"k\",\"env\":\"dev\",\"version\":\"1.0.0\"},\"precondition\":[],\"input\":{}}", exitCode);

        assertEquals(2, exitCode[0]);
        assertEquals("precondition must be an object", new JSONObject(output).getString("error"));
    }

    @Test(timeout = 60_000)
    public void integerOptionsThatDoNotFitAnIntAreConfigErrors() throws Exception {
        String sequence = "{\"suite\":\"batching\",\"fixture_id\":\"narrowing\",\"operation\":\"sequence\","
                + "\"constructor\":{\"apiKey\":\"k\",\"env\":\"prod\",\"version\":\"1.0.0\"%s},\"steps\":[%s]}";
        String[][] cases = {
                {",\"batchSize\":4294967297", "", "constructor.batchSize must be an integer"},
                {",\"batchSize\":1.5", "", "constructor.batchSize must be an integer"},
                {",\"maxQueueSize\":4294967297", "", "constructor.maxQueueSize must be an integer"},
                {"", "{\"action\":\"trackN\",\"count\":4294967296}", "trackN requires an integer count >= 1"},
        };
        for (String[] c : cases) {
            int[] exitCode = new int[1];
            String output = runHarness(String.format(sequence, c[0], c[1]), exitCode);

            assertEquals(c[2], 2, exitCode[0]);
            assertEquals(c[2], new JSONObject(output).getString("error"));
        }
    }

    @Test(timeout = 60_000)
    public void anUnescapedControlCharacterInAStringIsAConfigError() throws Exception {
        int[] exitCode = new int[1];
        String output = runHarness("{\"suite\":\"schema-extraction\",\"fixture_id\":\"tab\","
                + "\"constructor\":{\"apiKey\":\"k\",\"env\":\"dev\",\"version\":\"1.0.0\"},\"input\":{\"a\\tb\":\"x\ty\"}}", exitCode);

        assertEquals(2, exitCode[0]);
        assertTrue(new JSONObject(output).getString("error").startsWith("could not parse input envelope"));
    }

    @Test(timeout = 60_000)
    public void malformedSequenceStepFieldsAreConfigErrors() throws Exception {
        String sequence = "{\"suite\":\"batching\",\"fixture_id\":\"steps\",\"operation\":\"sequence\","
                + "\"constructor\":{\"apiKey\":\"k\",\"env\":\"prod\",\"version\":\"1.0.0\"},\"steps\":[%s]}";
        String[][] cases = {
                {"{\"action\":\"flush\",\"timeoutMs\":\"100\"}", "flush timeoutMs must be an integer"},
                {"{\"action\":\"flush\",\"timeoutMs\":1.5}", "flush timeoutMs must be an integer"},
                {"{\"action\":\"trackN\",\"count\":1,\"eventNamePrefix\":7}", "trackN eventNamePrefix must be a string"},
                {"{\"action\":\"trackN\",\"count\":1,\"streamId\":7}", "trackN streamId must be a string"},
                {"{\"action\":\"track\",\"eventName\":\"E\",\"eventProperties\":{},\"options\":\"x\"}", "options must be an object"},
        };
        for (String[] c : cases) {
            int[] exitCode = new int[1];
            String output = runHarness(String.format(sequence, c[0]), exitCode);

            assertEquals(c[1], 2, exitCode[0]);
            assertEquals(c[1], new JSONObject(output).getString("error"));
        }
    }

    @Test(timeout = 60_000)
    public void aFlushStepReportsWhetherTheInstanceDrained() throws Exception {
        // flush(0) starts the buffered send and returns before it completes: not drained.
        try (MockInspectorServer server = new MockInspectorServer()) {
            int[] exitCode = new int[1];
            String output = runHarness("{\"suite\":\"batching\",\"fixture_id\":\"drained\",\"operation\":\"sequence\","
                    + "\"constructor\":{\"apiKey\":\"k\",\"env\":\"staging\",\"version\":\"1.0.0\",\"disableBatchTimer\":true},"
                    + "\"steps\":[{\"action\":\"track\",\"eventName\":\"E\",\"eventProperties\":{}},"
                    + "{\"action\":\"flush\",\"timeoutMs\":0},{\"action\":\"flush\"},{\"action\":\"destroy\"}]}",
                    exitCode, server.url());

            assertEquals(output, 0, exitCode[0]);
            org.json.JSONArray records = new JSONObject(output).getJSONArray("actual");
            assertEquals(false, records.getJSONObject(1).get("value"));
            assertEquals(true, records.getJSONObject(2).get("value"));
            assertEquals(JSONObject.NULL, records.getJSONObject(3).get("value"));
        }
    }

    @Test(timeout = 60_000)
    public void trackNMakesEveryCallWhenCountExceedsTheWorkerPool() throws Exception {
        // Well above the harness's pool of 64 workers.
        int count = 500;
        try (MockInspectorServer server = new MockInspectorServer()) {
            int[] exitCode = new int[1];
            String output = runHarness("{\"suite\":\"batching\",\"fixture_id\":\"fan-out\",\"operation\":\"sequence\","
                    + "\"constructor\":{\"apiKey\":\"k\",\"env\":\"staging\",\"version\":\"1.0.0\",\"batchSize\":50,\"disableBatchTimer\":true},"
                    + "\"steps\":[{\"action\":\"trackN\",\"count\":" + count + ",\"eventNamePrefix\":\"E\"},{\"action\":\"flush\"}]}",
                    exitCode, server.url());

            assertEquals(output, 0, exitCode[0]);
            Set<String> names = new HashSet<>();
            int events = 0;
            for (MockInspectorServer.Request request : server.requests()) {
                for (int i = 0; i < request.body.length(); i++) {
                    names.add(request.body.getJSONObject(i).getString("eventName"));
                    events++;
                }
            }
            assertEquals(count, events);
            assertEquals(count, names.size());
            assertTrue(names.contains("E0") && names.contains("E" + (count - 1)));
        }
    }
}
