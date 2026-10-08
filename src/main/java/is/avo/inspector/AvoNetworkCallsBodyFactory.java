package is.avo.inspector;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;

// One self-contained event object of the wire body (SPEC.md §7.3).
class AvoNetworkCallsBodyFactory {

    String envName;
    String libVersion;

    AvoNetworkCallsBodyFactory(String envName, String libVersion) {
        this.envName = envName;
        this.libVersion = libVersion;
    }

    Map<String, Object> bodyForEventSchemaCall(@NotNull String eventName,
                                               @NotNull Map<String, AvoEventSchemaType> schema,
                                               @NotNull AvoInspectorTarget avoInspectorTarget,
                                               @NotNull String streamId,
                                               @Nullable GatewayOptions options,
                                               double samplingRate) {
        String outputReference = options != null ? normalize(options.getOutputReference()) : null;
        String originHint = options != null ? normalize(options.getOriginHint()) : null;
        String originAppVersion = options != null ? normalize(options.getOriginAppVersion()) : null;

        // SPEC.md §7.3.6: a source-scoped event never carries the instance's version.
        String appVersion;
        if (originAppVersion != null) {
            appVersion = originAppVersion;
        } else if (originHint != null) {
            appVersion = null;
        } else {
            appVersion = avoInspectorTarget.getAppVersion();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("apiKey", avoInspectorTarget.getApiKey());
        result.put("appName", avoInspectorTarget.getAppName());
        result.put("appVersion", appVersion != null ? appVersion : JSONObject.NULL);
        result.put("libVersion", libVersion);
        result.put("env", envName);
        result.put("libPlatform", AvoNetworkCallsHandler.LIB_PLATFORM);
        result.put("messageId", UUID.randomUUID().toString());
        result.put("streamId", streamId);
        result.put("createdAt", Util.currentTimeAsISO8601UTCString());
        result.put("samplingRate", samplingRate);
        result.put("type", "event");
        result.put("eventName", eventName);
        if (outputReference != null) {
            result.put("outputReference", outputReference);
        }
        if (originHint != null) {
            result.put("originHint", originHint);
        }
        result.put("eventProperties", Util.remapProperties(schema));

        return result;
    }

    @Nullable
    static String normalize(@Nullable String value) {
        if (value == null) {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end && isWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return start == end ? null : value.substring(start, end);
    }

    // Unicode whitespace, like String.prototype.trim (String.trim would also strip NUL and other controls).
    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r'
                || c == '\uFEFF' || Character.isSpaceChar(c);
    }
}
