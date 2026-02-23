package is.avo.inspector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;

class AvoNetworkCallsBodyFactory {

    String envName;
    String libVersion;
    @Nullable String publicEncryptionKey;

    AvoNetworkCallsBodyFactory(String envName, String libVersion) {
        this(envName, libVersion, null);
    }

    AvoNetworkCallsBodyFactory(String envName, String libVersion, @Nullable String publicEncryptionKey) {
        this.envName = envName;
        this.libVersion = libVersion;
        this.publicEncryptionKey = publicEncryptionKey;
    }

    @SuppressWarnings("SameParameterValue")
    Map<String, Object> bodyForEventSchemaCall(String eventName,
                                               Map<String, AvoEventSchemaType> schema,
                                               @Nullable String eventId, @Nullable String eventHash,
                                               @NotNull AvoInspectorTarget avoInspectorTarget,
                                               @NotNull String anonymousId) {
        return bodyForEventSchemaCall(eventName, schema, eventId, eventHash, avoInspectorTarget, anonymousId, null);
    }

    @SuppressWarnings("SameParameterValue")
    Map<String, Object> bodyForEventSchemaCall(String eventName,
                                               Map<String, AvoEventSchemaType> schema,
                                               @Nullable String eventId, @Nullable String eventHash,
                                               @NotNull AvoInspectorTarget avoInspectorTarget,
                                               @NotNull String anonymousId,
                                               @Nullable Map<String, ?> rawEventProperties) {
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

        // Add encrypted property values when encryption is enabled
        if (AvoEncryption.shouldEncrypt(envName, publicEncryptionKey) && rawEventProperties != null) {
            Map<String, String> encryptedValues = encryptPropertyValues(rawEventProperties, schema);
            if (!encryptedValues.isEmpty()) {
                eventSchemaBody.put("encryptedPropertyValues", encryptedValues);
            }
        }

        return eventSchemaBody;
    }

    /**
     * Encrypts individual property values, omitting list-type properties entirely (AC #7).
     * On encryption failure for a single property, logs a warning and omits that property's value (AC #8).
     */
    private Map<String, String> encryptPropertyValues(@NotNull Map<String, ?> rawEventProperties,
                                                       @NotNull Map<String, AvoEventSchemaType> schema) {
        Map<String, String> encrypted = new HashMap<>();

        for (Map.Entry<String, ?> entry : rawEventProperties.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // AC #7: List-type property values omitted entirely
            AvoEventSchemaType schemaType = schema.get(key);
            if (schemaType instanceof AvoEventSchemaType.AvoList) {
                continue;
            }
            if (value instanceof List || value instanceof JSONArray) {
                continue;
            }

            // Convert value to string representation for encryption
            String valueStr = propertyValueToString(value);
            if (valueStr == null) {
                continue;
            }

            // AC #8: Encryption failure logs warning and omits that property's value
            String encryptedValue = AvoEncryption.encrypt(valueStr, publicEncryptionKey);
            if (encryptedValue != null) {
                encrypted.put(key, encryptedValue);
            }
            // If encryptedValue is null, AvoEncryption.encrypt already logged the warning
        }

        return encrypted;
    }

    /**
     * Converts a property value to its string representation for encryption.
     * Strings are JSON-quoted; numbers, booleans are converted directly.
     */
    @Nullable
    private String propertyValueToString(@Nullable Object value) {
        if (value == null) {
            return "null";
        } else if (value instanceof String) {
            return "\"" + value + "\"";
        } else if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        } else if (value instanceof Map) {
            // Object types — convert to JSON string
            try {
                return new org.json.JSONObject((Map<?, ?>) value).toString();
            } catch (Exception e) {
                return null;
            }
        } else {
            return value.toString();
        }
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

        // AC #6: Include publicEncryptionKey when non-null and non-empty
        if (publicEncryptionKey != null && !publicEncryptionKey.isEmpty()) {
            result.put("publicEncryptionKey", publicEncryptionKey);
        }

        return result;
    }
}
