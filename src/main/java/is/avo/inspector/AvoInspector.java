package is.avo.inspector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;

import java.util.*;

import static is.avo.inspector.Util.handleException;

public class AvoInspector implements Inspector {

    private static boolean logsEnabled = false;

    AvoInspectorTarget defaultAvoInspectorTarget;
    String libVersion;

    String env;

    @Nullable
    String publicEncryptionKey;

    AvoSchemaExtractor avoSchemaExtractor;

    AvoNetworkCallsHandler networkCallsHandler;

    AvoNetworkCallsBodyFactory networkCallsBodyFactory;

    // Event spec validation (active in dev/staging only)
    EventSpecCache eventSpecCache;
    AvoEventSpecFetcher eventSpecFetcher;
    EventValidator eventValidator;

    public AvoInspector(@NotNull String apiKey, @NotNull String appVersion, @NotNull String appName, @NotNull AvoInspectorEnv env) {
        this(apiKey, appVersion, appName, env, null);
    }

    public AvoInspector(@NotNull String apiKey, @NotNull String appVersion, @NotNull String appName,
                        @NotNull AvoInspectorEnv env, @Nullable String publicEncryptionKey) {
        avoSchemaExtractor = new AvoSchemaExtractor();

        this.env = env.getName();
        this.defaultAvoInspectorTarget = new AvoInspectorTarget(apiKey, appName, appVersion);
        this.publicEncryptionKey = publicEncryptionKey;
        try {
            this.libVersion = ResourceBundle.getBundle("version").getString("version");
        } catch (Exception e) {
            this.libVersion = "-";
        }

        this.networkCallsHandler = new AvoNetworkCallsHandler(env.getName());

        this.networkCallsBodyFactory = new AvoNetworkCallsBodyFactory(env.getName(), libVersion);

        // Initialize event spec validation components (active in dev/staging only)
        this.eventSpecCache = new EventSpecCache();
        this.eventSpecFetcher = new AvoEventSpecFetcher();
        this.eventValidator = new EventValidator();

        enableLogging(env == AvoInspectorEnv.Dev);
    }

    @Override
    public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable JSONObject eventProperties) {
        return this.trackSchemaFromEvent(eventName, eventProperties, this.defaultAvoInspectorTarget, "");
    }

    @Override
    public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable JSONObject eventProperties, @NotNull AvoInspectorTarget overrideAvoInspectorTarget) {
        return this.trackSchemaFromEvent(eventName, eventProperties, overrideAvoInspectorTarget, "");
    }

    @Override
    public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable JSONObject eventProperties, @NotNull String streamId) {
        return this.trackSchemaFromEvent(eventName, eventProperties, this.defaultAvoInspectorTarget, streamId);
    }

    private @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable JSONObject eventProperties, @NotNull AvoInspectorTarget avoInspectorTarget, @NotNull String streamId) {
        try {
            warnIfStreamIdContainsColon(streamId);
            logPreExtract(eventName, eventProperties);

            Map<String, AvoEventSchemaType> schema = avoSchemaExtractor.extractSchema(eventProperties, false);

            trackSchemaInternal(eventName, schema, avoInspectorTarget, streamId);

            return schema;

        } catch (Exception e) {
            handleException(e, AvoInspector.this.env);
            return new HashMap<>();
        }
    }

    @Override
    public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties) {
        return this.trackSchemaFromEvent(eventName, eventProperties, this.defaultAvoInspectorTarget, "");
    }

    @Override
    public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties, @NotNull AvoInspectorTarget overrideAvoInspectorTarget) {
        return this.trackSchemaFromEvent(eventName, eventProperties, overrideAvoInspectorTarget, "");
    }

    @Override
    public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties, @NotNull String streamId) {
        return this.trackSchemaFromEvent(eventName, eventProperties, this.defaultAvoInspectorTarget, streamId);
    }

    private @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties, @NotNull AvoInspectorTarget avoInspectorTarget, @NotNull String streamId) {
        try {
            warnIfStreamIdContainsColon(streamId);
            logPreExtract(eventName, eventProperties);

            Map<String, AvoEventSchemaType> schema = avoSchemaExtractor.extractSchema(eventProperties, false);

            trackSchemaInternal(eventName, schema, avoInspectorTarget, streamId);

            return schema;

        } catch (Exception e) {
            handleException(e, AvoInspector.this.env);
            return new HashMap<>();
        }
    }

    private void warnIfStreamIdContainsColon(@NotNull String streamId) {
        if (streamId.contains(":")) {
            System.out.println("[Avo Inspector] Warning: streamId contains ':' which is not supported");
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
            trackSchemaInternal(eventName, eventSchema, this.defaultAvoInspectorTarget, "");
        } catch (Exception e) {
            handleException(e, AvoInspector.this.env);
        }
    }

    private void trackSchemaInternal(@NotNull String eventName, @Nullable Map<String, AvoEventSchemaType> eventSchema, @NotNull AvoInspectorTarget avoInspectorTarget, @NotNull String anonymousId) {
        if (eventSchema == null) {
            eventSchema = new HashMap<>();
        }

        logPostExtract(eventName, eventSchema);

        List<Map<String, Object>> events = new ArrayList<>();
        events.add(networkCallsBodyFactory.bodyForEventSchemaCall(eventName, eventSchema, null, null, avoInspectorTarget, anonymousId));

        networkCallsHandler.reportInspectorWithBatchBody(events);

        // Event spec validation: active in dev/staging only
        if (isValidationEnabled()) {
            fetchAndValidateAsync(eventName, eventSchema, avoInspectorTarget, anonymousId);
        }
    }

    /**
     * Returns true if event spec validation is active (dev or staging only).
     */
    boolean isValidationEnabled() {
        return AvoInspectorEnv.Dev.getName().equals(env) || AvoInspectorEnv.Staging.getName().equals(env);
    }

    /**
     * Fetch event spec and validate asynchronously.
     * Cache hit: synchronous validation then immediate send.
     * Cache miss: async fetch, then validate and send on response.
     */
    void fetchAndValidateAsync(@NotNull String eventName,
                               @NotNull Map<String, AvoEventSchemaType> schema,
                               @NotNull AvoInspectorTarget avoInspectorTarget,
                               @NotNull String streamId) {
        try {
            String apiKey = avoInspectorTarget.getApiKey();
            String cacheKey = EventSpecCache.buildKey(apiKey, streamId, eventName);
            long nowMs = System.currentTimeMillis();

            EventSpecCache.LookupResult lookup = eventSpecCache.get(cacheKey, nowMs);

            if (lookup.found) {
                // Cache hit: synchronous validation
                if (lookup.response != null) {
                    AvoEventSpecFetchTypes.ValidationResult result =
                            eventValidator.validate(streamId, schema, lookup.response);
                    if (result != null) {
                        reportValidatedEvent(result, avoInspectorTarget, streamId);
                    }
                }
                // If lookup.response is null, event is unknown - no validation needed
            } else {
                // Cache miss: async fetch
                eventSpecFetcher.fetchSpec(apiKey, streamId, eventName, response -> {
                    try {
                        // Cache the response (including null for unknown events)
                        eventSpecCache.put(cacheKey, response, System.currentTimeMillis());

                        // Update branchId if present
                        if (response != null && response.metadata != null) {
                            eventSpecCache.checkBranchId(response.metadata.branchId);
                        }

                        // Validate if we have a spec
                        if (response != null) {
                            AvoEventSpecFetchTypes.ValidationResult result =
                                    eventValidator.validate(streamId, schema, response);
                            if (result != null) {
                                reportValidatedEvent(result, avoInspectorTarget, streamId);
                            }
                        }
                    } catch (Exception e) {
                        handleException(e, AvoInspector.this.env);
                    }
                });
            }
        } catch (Exception e) {
            handleException(e, AvoInspector.this.env);
        }
    }

    /**
     * Report a validated event to the network handler.
     */
    void reportValidatedEvent(@NotNull AvoEventSpecFetchTypes.ValidationResult result,
                              @NotNull AvoInspectorTarget avoInspectorTarget,
                              @NotNull String streamId) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("type", "validatedEvent");
            body.put("apiKey", avoInspectorTarget.getApiKey());
            body.put("appName", avoInspectorTarget.getAppName());
            body.put("appVersion", avoInspectorTarget.getAppVersion());
            body.put("libVersion", libVersion);
            body.put("env", env);
            body.put("libPlatform", "java-jvm");
            body.put("messageId", java.util.UUID.randomUUID().toString());
            body.put("createdAt", Util.currentTimeAsISO8601UTCString());
            body.put("streamId", result.streamId);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("schemaId", result.metadata.schemaId);
            metadata.put("branchId", result.metadata.branchId);
            metadata.put("latestActionId", result.metadata.latestActionId);
            metadata.put("sourceId", result.metadata.sourceId);
            body.put("eventSpecMetadata", metadata);

            if (!result.passedEventIds.isEmpty()) {
                body.put("passedEventIds", result.passedEventIds);
            }
            if (!result.failedEventIds.isEmpty()) {
                body.put("failedEventIds", result.failedEventIds);
            }

            Map<String, Object> propertyValidations = new HashMap<>();
            for (Map.Entry<String, AvoEventSpecFetchTypes.PropertyValidation> entry : result.propertyValidations.entrySet()) {
                Map<String, Object> propVal = new HashMap<>();
                if (entry.getValue().passedEventIds != null) {
                    propVal.put("passedEventIds", entry.getValue().passedEventIds);
                }
                if (entry.getValue().failedEventIds != null) {
                    propVal.put("failedEventIds", entry.getValue().failedEventIds);
                }
                propertyValidations.put(entry.getKey(), propVal);
            }
            body.put("propertyValidations", propertyValidations);

            List<Map<String, Object>> events = new ArrayList<>();
            events.add(body);

            networkCallsHandler.reportInspectorWithBatchBody(events);

            if (isLogging()) {
                System.out.println("Avo Inspector: Reported validated event for stream " + result.streamId);
            }
        } catch (Exception e) {
            handleException(e, AvoInspector.this.env);
        }
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
