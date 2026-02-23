package is.avo.inspector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;

class AvoNetworkCallsBodyFactory {

    String envName;
    String libVersion;

    AvoNetworkCallsBodyFactory(String envName, String libVersion) {
        this.envName = envName;
        this.libVersion = libVersion;
    }

    @SuppressWarnings("SameParameterValue")
    Map<String, Object> bodyForEventSchemaCall(String eventName,
                                               Map<String, AvoEventSchemaType> schema,
                                               @Nullable String eventId, @Nullable String eventHash,
                                               @NotNull AvoInspectorTarget avoInspectorTarget,
                                               @NotNull String anonymousId) {
        JSONArray properties = Util.remapProperties(schema);

        Map<String, Object> eventSchemaBody = createBaseCallBody(avoInspectorTarget, anonymousId);

        if (eventId != null) {
            eventSchemaBody.put("avoFunction", true);
            eventSchemaBody.put("eventId", eventId);
            eventSchemaBody.put("eventHash", eventHash);
        } else {
            eventSchemaBody.put("avoFunction", false);
        }

        eventSchemaBody.put("type", "event");
        eventSchemaBody.put("eventName", eventName);
        eventSchemaBody.put("eventProperties", properties);

        return eventSchemaBody;
    }

    private Map<String, Object> createBaseCallBody(@NotNull AvoInspectorTarget avoInspectorTarget,
                                                   @NotNull String anonymousId) {
        Map<String, Object> result = new HashMap<>();

        result.put("apiKey", avoInspectorTarget.getApiKey());
        result.put("appName", avoInspectorTarget.getAppName());
        result.put("appVersion", avoInspectorTarget.getAppVersion());
        result.put("libVersion", libVersion);
        result.put("env", envName);
        result.put("libPlatform", "java-jvm");
        result.put("messageId", UUID.randomUUID().toString());
        result.put("createdAt", Util.currentTimeAsISO8601UTCString());
        result.put("anonymousId", anonymousId);

        return result;
    }
}
