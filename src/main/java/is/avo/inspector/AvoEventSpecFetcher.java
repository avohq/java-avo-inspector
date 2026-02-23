package is.avo.inspector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP GET fetcher for event specs with in-flight de-duplication.
 *
 * If a request for a given key is already in-flight, additional requests
 * for the same key will queue their callbacks and share the single HTTP response.
 *
 * On success: all queued callbacks are called with the parsed result.
 * On failure: all queued callbacks are called with null.
 */
class AvoEventSpecFetcher {

    private static final String SPEC_ENDPOINT = "https://api.avo.app/inspector/v1/spec";

    // In-flight deduplication map: key -> list of pending callbacks
    private final Map<String, List<AvoEventSpecFetchTypes.FetchCallback>> inFlightRequests = new HashMap<>();

    // Injectable HTTP fetcher for testing
    interface HttpFetcher {
        @Nullable String fetch(@NotNull String url) throws Exception;
    }

    @Nullable
    private HttpFetcher httpFetcher;

    AvoEventSpecFetcher() {
        this.httpFetcher = null;
    }

    AvoEventSpecFetcher(@Nullable HttpFetcher httpFetcher) {
        this.httpFetcher = httpFetcher;
    }

    /**
     * Fetch event spec asynchronously with in-flight de-duplication.
     *
     * @param apiKey    The API key
     * @param streamId  The stream ID
     * @param eventName The event name
     * @param callback  Callback to receive the result (or null on failure)
     */
    void fetchSpec(@NotNull String apiKey, @NotNull String streamId,
                   @NotNull String eventName, @NotNull AvoEventSpecFetchTypes.FetchCallback callback) {
        String key = EventSpecCache.buildKey(apiKey, streamId, eventName);

        synchronized (inFlightRequests) {
            if (inFlightRequests.containsKey(key)) {
                // Already in-flight, queue callback
                inFlightRequests.get(key).add(callback);
                return;
            }

            // New request
            List<AvoEventSpecFetchTypes.FetchCallback> callbacks = new ArrayList<>();
            callbacks.add(callback);
            inFlightRequests.put(key, callbacks);
        }

        // Execute fetch on a new thread
        new Thread(() -> {
            AvoEventSpecFetchTypes.EventSpecResponse result = null;
            try {
                String urlStr = SPEC_ENDPOINT
                        + "?apiKey=" + encode(apiKey)
                        + "&streamId=" + encode(streamId)
                        + "&eventName=" + encode(eventName);

                String responseBody;
                if (httpFetcher != null) {
                    responseBody = httpFetcher.fetch(urlStr);
                } else {
                    responseBody = defaultFetch(urlStr);
                }

                if (responseBody != null) {
                    result = parseResponse(responseBody);
                }
            } catch (Exception e) {
                if (AvoInspector.isLogging()) {
                    System.err.println("AvoInspector: Failed to fetch event spec: " + e.getMessage());
                }
                result = null;
            } finally {
                // Resolve all callbacks
                List<AvoEventSpecFetchTypes.FetchCallback> callbacks;
                synchronized (inFlightRequests) {
                    callbacks = inFlightRequests.remove(key);
                }
                if (callbacks != null) {
                    for (AvoEventSpecFetchTypes.FetchCallback cb : callbacks) {
                        cb.onResult(result);
                    }
                }
            }
        }).start();
    }

    /**
     * Check if a request for the given key is currently in-flight.
     */
    boolean isInFlight(@NotNull String key) {
        synchronized (inFlightRequests) {
            return inFlightRequests.containsKey(key);
        }
    }

    @Nullable
    private String defaultFetch(@NotNull String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                if (AvoInspector.isLogging()) {
                    System.err.println("AvoInspector: Spec fetch failed with code " + responseCode);
                }
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            try {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                return response.toString();
            } finally {
                reader.close();
            }
        } finally {
            connection.disconnect();
        }
    }

    @Nullable
    static AvoEventSpecFetchTypes.EventSpecResponse parseResponse(@NotNull String responseBody) {
        try {
            JSONObject json = new JSONObject(responseBody);

            AvoEventSpecFetchTypes.EventSpecMetadata metadata = null;
            if (json.has("metadata") && !json.isNull("metadata")) {
                JSONObject metaJson = json.getJSONObject("metadata");
                metadata = new AvoEventSpecFetchTypes.EventSpecMetadata(
                        metaJson.optString("schemaId", ""),
                        metaJson.optString("branchId", ""),
                        metaJson.optString("latestActionId", ""),
                        metaJson.optString("sourceId", "")
                );
            }

            List<AvoEventSpecFetchTypes.PropertyRule> rules = null;
            if (json.has("propertyRules") && !json.isNull("propertyRules")) {
                JSONArray rulesJson = json.getJSONArray("propertyRules");
                rules = new ArrayList<>();
                for (int i = 0; i < rulesJson.length(); i++) {
                    JSONObject ruleJson = rulesJson.getJSONObject(i);
                    rules.add(new AvoEventSpecFetchTypes.PropertyRule(
                            ruleJson.getString("propertyName"),
                            ruleJson.getString("typePattern")
                    ));
                }
            }

            return new AvoEventSpecFetchTypes.EventSpecResponse(metadata, rules);
        } catch (Exception e) {
            if (AvoInspector.isLogging()) {
                System.err.println("AvoInspector: Failed to parse spec response: " + e.getMessage());
            }
            return null;
        }
    }

    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
