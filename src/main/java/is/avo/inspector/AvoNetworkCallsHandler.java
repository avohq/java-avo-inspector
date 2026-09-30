package is.avo.inspector;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;

// Sends one batch to the Inspector API (SPEC.md §7). Never throws and never retries.
class AvoNetworkCallsHandler {

    static final String DEFAULT_ENDPOINT = "https://api.avo.app/inspector/v2/track";
    static final String MOCK_ENDPOINT_ENV_VAR = "AVO_INSPECTOR_MOCK_ENDPOINT";
    static final String LIB_PLATFORM = "java-jvm";
    static final int TIMEOUT_MS = 10_000;
    static final int GZIP_THRESHOLD_BYTES = 1024;

    enum SendResult { OK, NON_200, FAILED }

    // Enforces the 10 s budget on the whole request, not just on each connect/read.
    private static final ScheduledExecutorService watchdog = newWatchdog();

    final String envName;

    // Test-only endpoint override for unit tests, which cannot set environment variables.
    @Nullable volatile String endpointForTesting;

    // Test-only, for every handler in the JVM: the unit tests point it at a dead local port so no
    // test can reach api.avo.app, whatever its env. Only code in this package can set it.
    @Nullable static volatile String endpointForAllTests;

    // SPEC.md §7.7: last-write-wins; a volatile double write is atomic.
    volatile double samplingRate = 1.0;

    // Set by abortAll(): failures of abandoned requests are expected and not reported.
    private volatile boolean aborted = false;

    private final Set<HttpURLConnection> activeConnections =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<HttpURLConnection, Boolean>());

    AvoNetworkCallsHandler(String envName) {
        this.envName = envName;
    }

    String endpoint() {
        String override = endpointForTesting;
        if (override != null) {
            return override;
        }
        String testGuard = endpointForAllTests;
        if (testGuard != null) {
            return testGuard;
        }
        return resolveEndpoint(envName, System.getenv(MOCK_ENDPOINT_ENV_VAR));
    }

    // SPEC.md §7.1: fail closed, a prod instance never honours the mock endpoint.
    static String resolveEndpoint(String envName, @Nullable String mockEndpoint) {
        if (!AvoInspectorEnv.Prod.getName().equals(envName) && mockEndpoint != null && !mockEndpoint.isEmpty()) {
            return mockEndpoint;
        }
        return DEFAULT_ENDPOINT;
    }

    SendResult send(List<Map<String, Object>> events, String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("api-key", apiKey);
        headers.put("env", envName);
        headers.put("X-Avo-Client", LIB_PLATFORM);
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");

        // SPEC.md §7.2: refuse to send a header value that could split the request.
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (containsControlCharacter(header.getValue())) {
                logError("send failed (" + header.getKey() + " header contains a control character)");
                return failed("Request failed");
            }
        }

        byte[] body;
        try {
            JSONArray json = new JSONArray();
            for (Map<String, Object> event : events) {
                json.put(new JSONObject(event));
            }
            body = json.toString().getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            logError("request serialization failed: " + e.getClass().getSimpleName());
            return failed("Request failed");
        }

        if (body.length >= GZIP_THRESHOLD_BYTES) {
            byte[] compressed = gzip(body);
            if (compressed != null) {
                body = compressed;
                headers.put("Content-Encoding", "gzip");
            }
        }

        return post(body, headers, events.size());
    }

    private SendResult post(byte[] body, Map<String, String> headers, int eventCount) {
        HttpURLConnection connection = null;
        final AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> deadline = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint()).openConnection();
            final HttpURLConnection finalConnection = connection;
            activeConnections.add(connection);
            // abortAll() sets the flag before disconnecting the registered connections, so a
            // connection registered after that pass is caught here, before anything is sent.
            if (aborted) {
                return SendResult.FAILED;
            }
            deadline = watchdog.schedule(new Runnable() {
                @Override
                public void run() {
                    timedOut.set(true);
                    finalConnection.disconnect();
                }
            }, TIMEOUT_MS, TimeUnit.MILLISECONDS);

            connection.setRequestMethod("POST");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            // A 3xx is a non-200: following it could turn the POST into a GET and forward the
            // api-key header to another host.
            connection.setInstanceFollowRedirects(false);
            connection.setDoInput(true);
            connection.setDoOutput(true);
            // Fixed-length streaming sends Content-Length with the exact byte count (never chunked).
            connection.setFixedLengthStreamingMode(body.length);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }

            try (OutputStream os = connection.getOutputStream()) {
                os.write(body);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                drain(connection.getErrorStream());
                // Always reported, at most once per window; never the key or the body.
                AvoLog.NON_200.report("Avo Inspector: Inspector API returned status " + responseCode + "; "
                        + eventCount + " event(s) not stored.", 1);
                return SendResult.NON_200;
            }

            updateSamplingRate(readFully(connection.getInputStream()));
            return SendResult.OK;
        } catch (SocketTimeoutException e) {
            return failed("Request timed out");
        } catch (IOException e) {
            return failed(timedOut.get() ? "Request timed out" : "Request failed");
        } catch (RuntimeException e) {
            logError("request error: " + e.getClass().getSimpleName());
            return failed("Request failed");
        } finally {
            if (deadline != null) {
                deadline.cancel(false);
            }
            if (connection != null) {
                activeConnections.remove(connection);
                connection.disconnect();
            }
        }
    }

    // SPEC.md §7.4: only a numeric samplingRate in [0, 1] on a 200 changes the rate.
    void updateSamplingRate(String responseBody) {
        Object rate;
        try {
            rate = new JSONObject(responseBody).opt("samplingRate");
        } catch (RuntimeException e) {
            return;
        }
        if (rate instanceof Number) {
            double value = ((Number) rate).doubleValue();
            if (value >= 0.0 && value <= 1.0) {
                samplingRate = value;
            }
        }
    }

    // Abandons in-flight requests (SPEC.md §4.5).
    void abortAll() {
        aborted = true;
        for (HttpURLConnection connection : activeConnections) {
            connection.disconnect();
        }
        activeConnections.clear();
    }

    static boolean containsControlCharacter(@Nullable String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // Any Unicode control character (C0, DEL, C1) except horizontal tab. CR, LF and NUL can
            // split the request; none of the others belongs in a header value either.
            if (Character.isISOControl(c) && c != '\t') {
                return true;
            }
        }
        return false;
    }

    @Nullable
    static byte[] gzip(byte[] body) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(body.length / 2 + 32);
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(body);
            }
            return bytes.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String readFully(@Nullable InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void drain(@Nullable InputStream stream) {
        try {
            readFully(stream);
        } catch (IOException ignored) {
        }
    }

    // SPEC.md §7.5: a failed send is always reported, whatever the logging flag. Like every log
    // here it never includes the apiKey or the request body (SPEC.md §7.5.1).
    private SendResult failed(String reason) {
        if (!aborted) {
            System.err.println("Avo Inspector: schema sending failed: " + reason + ".");
        }
        return SendResult.FAILED;
    }

    // Diagnostic detail, only when logging is enabled. Never includes the apiKey or request bodies.
    private static void logError(String message) {
        if (AvoInspector.isLogging()) {
            System.err.println("Avo Inspector: " + message);
        }
    }

    private static ScheduledExecutorService newWatchdog() {
        return AvoBatcher.newSharedScheduler("avo-inspector-request-watchdog");
    }
}
