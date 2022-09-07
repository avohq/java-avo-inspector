package is.avo.inspector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;

import java.util.*;

import static is.avo.inspector.Util.handleException;

public class AvoInspector implements Inspector {

    private static boolean logsEnabled = false;

    String apiKey;
    String libVersion;

    String env;

    AvoSchemaExtractor avoSchemaExtractor;

    AvoNetworkCallsHandler networkCallsHandler;

    boolean isHidden = true;

    public AvoInspector(@NotNull String apiKey, @NotNull String appVersion, @NotNull String appName, @NotNull AvoInspectorEnv env) {
        avoSchemaExtractor = new AvoSchemaExtractor();

        this.env = env.getName();
        this.apiKey = apiKey;
        this.libVersion = ResourceBundle.getBundle("version").getString("version");

        this.networkCallsHandler = new AvoNetworkCallsHandler(apiKey, env.getName(), appName, appVersion, libVersion + "");

        enableLogging(env == AvoInspectorEnv.Dev);
    }

    @Override
    public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable JSONObject eventProperties) {
        try {
            logPreExtract(eventName, eventProperties);

            Map<String, AvoEventSchemaType> schema = avoSchemaExtractor.extractSchema(eventProperties, false);

            trackSchemaInternal(eventName, schema);

            return schema;

        } catch (Exception e) {
            handleException(e, AvoInspector.this.env);
            return new HashMap<>();
        }
    }

    @Override
    public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties) {
        try {

            logPreExtract(eventName, eventProperties);

            Map<String, AvoEventSchemaType> schema = avoSchemaExtractor.extractSchema(eventProperties, false);

            trackSchemaInternal(eventName, schema);

            return schema;

        } catch (Exception e) {
            handleException(e, AvoInspector.this.env);
            return new HashMap<>();
        }
    }

    private void logPreExtract(@NotNull String eventName, @Nullable Object eventProperties) {
        if (isLogging() && eventProperties != null) {
            System.out.println("Avo Inspector: Supplied event " + eventName + " with params \n" + eventProperties);
        }
    }

    @Override
    public void trackSchema(@NotNull String eventName, @Nullable Map<String, AvoEventSchemaType> eventSchema) {
        try {
            trackSchemaInternal(eventName, eventSchema);
        } catch (Exception e) {
            handleException(e, AvoInspector.this.env);
        }
    }

    private void trackSchemaInternal(@NotNull String eventName, @Nullable Map<String, AvoEventSchemaType> eventSchema) {
        if (eventSchema == null) {
            eventSchema = new HashMap<>();
        }

        logPostExtract(eventName, eventSchema);

        List<Map<String, Object>> events = new ArrayList<>();
        events.add(networkCallsHandler.bodyForSessionStartedCall());
        events.add(networkCallsHandler.bodyForEventSchemaCall(eventName, eventSchema, null, null));

        networkCallsHandler.reportInspectorWithBatchBody(events);
    }

    static void logPostExtract(@Nullable String eventName, @NotNull Map<String, AvoEventSchemaType> eventSchema) {
        if (isLogging()) {
            StringBuilder schemaString = new StringBuilder();

            for (String key : eventSchema.keySet()) {
                AvoEventSchemaType value = eventSchema.get(key);
                if (value != null) {
                    String entry = "\t\"" + key + "\": \"" + value.getReportedName() + "\";\n";
                    schemaString.append(entry);
                }
            }

            if (eventName != null) {
                System.out.println("Avo Inspector: Saved event " + eventName + " with schema {\n" + schemaString + "}");
            } else {
                System.out.println("Avo Inspector: Parsed schema {\n" + schemaString + "}");
            }
        }
    }

    @Override
    public @NotNull Map<String, AvoEventSchemaType> extractSchema(@Nullable Object eventProperties) {
        try {
            return avoSchemaExtractor.extractSchema(eventProperties, true);
        } catch (Exception e) {
            handleException(e, AvoInspector.this.env);
            return new HashMap<>();
        }
    }

    @SuppressWarnings("WeakerAccess")
    static public boolean isLogging() {
        return logsEnabled;
    }

    @SuppressWarnings("WeakerAccess")
    static public void enableLogging(boolean enabled) {
        logsEnabled = enabled;
    }
}
