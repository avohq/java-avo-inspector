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

    Map<String, Object> bodyForSessionStartedCall(@NotNull AvoInspectorTarget avoInspectorTarget) {
        Map<String, Object> sessionBody = createBaseCallBody(avoInspectorTarget);
        sessionBody.put("type", "sessionStarted");
        return sessionBody;
    }

    @SuppressWarnings("SameParameterValue")
    Map<String, Object> bodyForEventSchemaCall(String eventName,
                                               Map<String, AvoEventSchemaType> schema,
                                               @Nullable String eventId, @Nullable String eventHash,
                                               @NotNull AvoInspectorTarget avoInspectorTarget) {
        JSONArray properties = Util.remapProperties(schema);

        Map<String, Object> eventSchemaBody = createBaseCallBody(avoInspectorTarget);

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

    private Map<String, Object> createBaseCallBody(@NotNull AvoInspectorTarget avoInspectorTarget) {
        Map<String, Object> result = new HashMap<>();

        result.put("apiKey", avoInspectorTarget.apiKey());
        result.put("appName", avoInspectorTarget.appName());
        result.put("appVersion", avoInspectorTarget.appVersion());
        result.put("libVersion", libVersion);
        result.put("env", envName);
        result.put("libPlatform", "java-jvm");
        result.put("messageId", UUID.randomUUID().toString());
        result.put("createdAt", Util.currentTimeAsISO8601UTCString());
        result.put("sessionId", UUID.randomUUID().toString());

        return result;
    }
}
