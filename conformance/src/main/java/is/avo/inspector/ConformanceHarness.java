package is.avo.inspector;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Conformance harness for avohq/spec-first-inspector-server-sdk, implementing
 * conformance/runner-contract.md. Reads one JSON envelope from stdin, drives one AvoInspector and
 * writes one JSON envelope to stdout. SDK diagnostics go to stderr.
 *
 * <p>It lives in the SDK's package to reach two test seams that are not public API: the
 * sampling-rate override (precondition.samplingRate) and the immediate-send outcome of a track
 * call (SPEC.md §7.5: a non-200 resolves []).
 */
public final class ConformanceHarness {

    static final String HARNESS_CONTRACT_VERSION = "1.1.0";

    private static final int EXIT_OK = 0;
    private static final int EXIT_RUNTIME_FAILURE = 1;
    private static final int EXIT_CONFIG_ERROR = 2;

    private static PrintStream stdout;

    public static void main(String[] args) throws java.io.UnsupportedEncodingException {
        // UTF-8 whatever the platform charset: the runner parses stdout as UTF-8 JSON.
        stdout = new PrintStream(System.out, true, "UTF-8");
        // Anything the SDK prints goes to stderr; stdout carries only the envelope.
        System.setOut(System.err);
        int exitCode = run();
        stdout.flush();
        System.err.flush();
        // halt, not exit: the SDK's exit-time flush hook would otherwise send what the steps left
        // buffered, an implicit flush the runner contract forbids (wire-8 asserts zero requests).
        Runtime.getRuntime().halt(exitCode);
    }

    private static int run() {
        String fixtureId = "";
        Map<String, Object> envelope;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) {
                return configError(fixtureId, "no input on stdin");
            }
            Object parsed = ConformanceJson.parse(line);
            if (!(parsed instanceof Map)) {
                return configError(fixtureId, "input envelope is not a JSON object");
            }
            envelope = asMap(parsed);
        } catch (Exception e) {
            return configError(fixtureId, "could not parse input envelope: " + e.getMessage());
        }

        fixtureId = envelope.get("fixture_id") instanceof String ? (String) envelope.get("fixture_id") : "";
        Object suite = envelope.get("suite");
        Object operation = envelope.get("operation");
        if ("schema-extraction".equals(suite) && operation == null) {
            operation = "extractSchema";
        }
        if (!(envelope.get("constructor") instanceof Map)) {
            return configError(fixtureId, "missing constructor object");
        }
        if (!"extractSchema".equals(operation) && !"trackSchemaFromEvent".equals(operation) && !"sequence".equals(operation)) {
            return configError(fixtureId, "unsupported operation: " + operation);
        }

        String constructorError = constructorError(asMap(envelope.get("constructor")));
        if (constructorError != null) {
            return configError(fixtureId, constructorError);
        }
        if (envelope.get("precondition") != null && !(envelope.get("precondition") instanceof Map)) {
            return configError(fixtureId, "precondition must be an object");
        }

        AvoInspector inspector;
        try {
            inspector = construct(asMap(envelope.get("constructor")));
        } catch (RuntimeException e) {
            write(fixtureId, false, null, "resolve", "Constructor threw: " + e.getMessage());
            return EXIT_RUNTIME_FAILURE;
        }

        if (envelope.get("precondition") instanceof Map) {
            for (Map.Entry<String, Object> field : asMap(envelope.get("precondition")).entrySet()) {
                if ("samplingRate".equals(field.getKey()) && field.getValue() instanceof Number) {
                    inspector.setSamplingRateForTesting(((Number) field.getValue()).doubleValue());
                } else {
                    return configError(fixtureId, "unsupported precondition: " + field.getKey());
                }
            }
        }

        try {
            if ("extractSchema".equals(operation)) {
                Object schema = Util.remapProperties(inspector.extractSchema(envelope.get("input")));
                write(fixtureId, true, schema, "resolve", null);
            } else if ("trackSchemaFromEvent".equals(operation)) {
                if (!(envelope.get("input") instanceof Map)) {
                    return configError(fixtureId, "trackSchemaFromEvent requires an input object");
                }
                String inputError = trackInputError(asMap(envelope.get("input")));
                if (inputError != null) {
                    return configError(fixtureId, inputError);
                }
                Object[] outcome = track(inspector, asMap(envelope.get("input")));
                write(fixtureId, true, outcome[1], (String) outcome[0], null);
            } else {
                if (!(envelope.get("steps") instanceof List)) {
                    return configError(fixtureId, "sequence requires a steps array");
                }
                JSONArray records = new JSONArray();
                for (Object rawStep : (List<?>) envelope.get("steps")) {
                    if (!(rawStep instanceof Map)) {
                        return configError(fixtureId, "sequence step is not an object");
                    }
                    Map<String, Object> step = asMap(rawStep);
                    Object action = step.get("action");
                    if ("track".equals(action)) {
                        String inputError = trackInputError(step);
                        if (inputError != null) {
                            return configError(fixtureId, inputError);
                        }
                        Object[] outcome = track(inspector, step);
                        records.put(record("track", (String) outcome[0], outcome[1]));
                    } else if ("trackN".equals(action)) {
                        Object count = step.get("count");
                        if (!isInt(count) || (Long) count < 1) {
                            return configError(fixtureId, "trackN requires an integer count >= 1");
                        }
                        String badField = firstNonString(step, "eventNamePrefix", "streamId");
                        if (badField != null) {
                            return configError(fixtureId, "trackN " + badField + " must be a string");
                        }
                        trackN(inspector, (int) (long) (Long) count, string(step.get("eventNamePrefix"), ""),
                                string(step.get("streamId"), ""));
                        records.put(record("trackN", "resolve", count));
                    } else if ("flush".equals(action)) {
                        Object timeoutMs = step.get("timeoutMs");
                        if (timeoutMs != null && !(timeoutMs instanceof Long)) {
                            return configError(fixtureId, "flush timeoutMs must be an integer");
                        }
                        // The value is whether the instance drained, as flush() reports it.
                        boolean drained = timeoutMs != null ? inspector.flush((Long) timeoutMs) : inspector.flush();
                        records.put(record("flush", "resolve", drained));
                    } else if ("destroy".equals(action)) {
                        inspector.destroy();
                        records.put(record("destroy", "resolve", null));
                    } else {
                        return configError(fixtureId, "unsupported sequence action: " + action);
                    }
                }
                write(fixtureId, true, records, "resolve", null);
            }
        } catch (RuntimeException e) {
            write(fixtureId, false, null, "resolve", e.getClass().getSimpleName() + ": " + e.getMessage());
            return EXIT_RUNTIME_FAILURE;
        }
        return EXIT_OK;
    }

    private static AvoInspector construct(Map<String, Object> options) {
        AvoInspectorOptions.Builder builder = AvoInspectorOptions.builder()
                .apiKey((String) options.get("apiKey"))
                .env((String) options.get("env"))
                .appVersion((String) options.get("version"))
                .appName((String) options.get("appName"));
        if (options.get("batchSize") instanceof Number) {
            builder.batchSize(((Number) options.get("batchSize")).intValue());
        }
        if (options.get("batchFlushSeconds") instanceof Number) {
            builder.batchFlushSeconds(((Number) options.get("batchFlushSeconds")).doubleValue());
        }
        if (options.get("maxQueueSize") instanceof Number) {
            builder.maxQueueSize(((Number) options.get("maxQueueSize")).intValue());
        }
        if (options.get("disableBatchTimer") instanceof Boolean) {
            builder.disableBatchTimer((Boolean) options.get("disableBatchTimer"));
        }
        return new AvoInspector(builder.build());
    }

    // The constructor fields construct() casts or narrows; JSON null passes through as absent.
    private static String constructorError(Map<String, Object> options) {
        String badField = firstNonString(options, "apiKey", "env", "version", "appName");
        if (badField != null) {
            return "constructor." + badField + " must be a string";
        }
        for (String key : new String[]{"batchSize", "maxQueueSize"}) {
            if (options.get(key) != null && !isInt(options.get(key))) {
                return "constructor." + key + " must be an integer";
            }
        }
        if (options.get("batchFlushSeconds") != null && !(options.get("batchFlushSeconds") instanceof Number)) {
            return "constructor.batchFlushSeconds must be a number";
        }
        if (options.get("disableBatchTimer") != null && !(options.get("disableBatchTimer") instanceof Boolean)) {
            return "constructor.disableBatchTimer must be a boolean";
        }
        return null;
    }

    // An integer literal that fits in an int (ConformanceJson parses integer literals as Long or BigInteger).
    private static boolean isInt(Object value) {
        return value instanceof Long && (Long) value >= Integer.MIN_VALUE && (Long) value <= Integer.MAX_VALUE;
    }

    // The fields track() casts to String; JSON null passes through as null for the SDK to handle.
    private static String trackInputError(Map<String, Object> input) {
        String badField = firstNonString(input, "eventName", "streamId");
        if (badField != null) {
            return badField + " must be a string";
        }
        if (input.get("options") != null && !(input.get("options") instanceof Map)) {
            return "options must be an object";
        }
        if (input.get("options") instanceof Map) {
            badField = firstNonString(asMap(input.get("options")), "outputReference", "originHint", "originAppVersion");
            if (badField != null) {
                return "options." + badField + " must be a string";
            }
        }
        return null;
    }

    // Returns {outcome, value}. A thrown SDK error is the Java form of a rejected promise.
    private static Object[] track(AvoInspector inspector, Map<String, Object> input) {
        TrackOptions options = null;
        if (input.get("options") instanceof Map) {
            // Passed verbatim: normalization is the SDK's job.
            Map<String, Object> raw = asMap(input.get("options"));
            options = TrackOptions.builder()
                    .outputReference((String) raw.get("outputReference"))
                    .originHint((String) raw.get("originHint"))
                    .originAppVersion((String) raw.get("originAppVersion"))
                    .build();
        }
        try {
            Map<String, AvoEventSchemaType> schema = inspector.trackSchemaFromEventAwaitingSend(
                    (String) input.get("eventName"), input.get("eventProperties"), (String) input.get("streamId"), options);
            return new Object[]{"resolve", Util.remapProperties(schema)};
        } catch (RuntimeException e) {
            return new Object[]{"reject", e.getMessage()};
        }
    }

    // At most this many trackN worker threads, so a large count cannot exhaust the thread limit.
    static final int MAX_TRACKN_WORKERS = 64;

    // Real threads, released together, each taking the next call index until all count calls have
    // run; joined before the step resolves.
    private static void trackN(final AvoInspector inspector, final int count, final String prefix, final String streamId) {
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger next = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        try {
            for (int w = 0; w < Math.min(count, MAX_TRACKN_WORKERS); w++) {
                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        for (int i = next.getAndIncrement(); i < count; i = next.getAndIncrement()) {
                            inspector.trackSchemaFromEventAwaitingSend(prefix + i, Collections.emptyMap(), streamId, null);
                        }
                    }
                });
                thread.start();
                threads.add(thread);
            }
        } finally {
            // Released even if a thread failed to start, so the started ones finish and the step can report.
            start.countDown();
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static JSONObject record(String action, String outcome, Object value) {
        JSONObject record = new JSONObject();
        record.put("action", action);
        record.put("outcome", outcome);
        record.put("value", value == null ? JSONObject.NULL : value);
        return record;
    }

    private static int configError(String fixtureId, String message) {
        write(fixtureId, false, null, "resolve", message);
        return EXIT_CONFIG_ERROR;
    }

    private static void write(String fixtureId, boolean passed, Object actual, String outcome, String error) {
        JSONObject output = new JSONObject();
        output.put("fixture_id", fixtureId);
        output.put("passed", passed);
        output.put("actual", actual == null ? JSONObject.NULL : actual);
        output.put("outcome", outcome);
        output.put("error", error == null ? JSONObject.NULL : error);
        stdout.println(output.toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    // The first key whose value is present and not a string, or null when there is none.
    private static String firstNonString(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !(value instanceof String)) {
                return key;
            }
        }
        return null;
    }

    private static String string(Object value, String fallback) {
        return value instanceof String ? (String) value : fallback;
    }

    private ConformanceHarness() {
    }
}
