package is.avo.inspector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static is.avo.inspector.Util.handleException;

/**
 * Sends event schemas to Avo Inspector (SPEC.md §4). Outside dev, events are buffered and sent in
 * batches; call {@link #flush()} before the process exits. Safe to use from multiple threads.
 */
public class AvoInspector implements Inspector {

    static final String NO_API_KEY_MESSAGE = "[Avo Inspector] No API key provided. Inspector can't operate without API key.";
    static final String API_KEY_CONTROL_CHARACTER_MESSAGE = "[Avo Inspector] API key contains a control character. The API key is sent as a request header and cannot contain CR, LF, or NUL.";
    static final String API_KEY_OTHER_CONTROL_CHARACTER_MESSAGE = "Avo Inspector: apiKey must not contain control characters";
    static final String NO_VERSION_MESSAGE = "[Avo Inspector] No version provided. Many features of Inspector rely on versioning. Please provide comparable string version, i.e. integer or semantic.";

    static final int DEFAULT_BATCH_SIZE = 30;
    static final double DEFAULT_BATCH_FLUSH_SECONDS = 30;
    static final int DEFAULT_MAX_QUEUE_SIZE = 1000;
    static final long DEFAULT_FLUSH_TIMEOUT_MS = 10_000;

    // Process-wide (SPEC.md §4.4).
    private static volatile boolean logsEnabled = false;

    AvoInspectorTarget defaultAvoInspectorTarget;
    String libVersion;

    String env;

    final int batchSize;

    AvoSchemaExtractor avoSchemaExtractor;

    AvoNetworkCallsHandler networkCallsHandler;

    AvoNetworkCallsBodyFactory networkCallsBodyFactory;

    final AvoBatcher batcher;

    private volatile boolean destroyed = false;

    /**
     * @throws IllegalArgumentException when {@code apiKey} or {@code appVersion} is blank, or
     *                                  {@code apiKey} contains a control character other than tab
     */
    public AvoInspector(@NotNull String apiKey, @NotNull String appVersion, @NotNull String appName, @NotNull AvoInspectorEnv env) {
        this(AvoInspectorOptions.builder().apiKey(apiKey).appVersion(appVersion).appName(appName).env(env).build());
    }

    /**
     * @throws IllegalArgumentException when {@code apiKey} or {@code appVersion} is blank, or
     *                                  {@code apiKey} contains a control character other than tab
     */
    public AvoInspector(@NotNull AvoInspectorOptions options) {
        validateApiKey(options.apiKey);
        if (isBlank(options.appVersion)) {
            throw new IllegalArgumentException(NO_VERSION_MESSAGE);
        }

        AvoInspectorEnv resolvedEnv = resolveEnv(options);

        avoSchemaExtractor = new AvoSchemaExtractor();

        this.env = resolvedEnv.getName();
        this.defaultAvoInspectorTarget = new AvoInspectorTarget(options.apiKey,
                options.appName != null ? options.appName : "", options.appVersion);
        this.libVersion = AvoInspectorVersion.VERSION;

        this.networkCallsHandler = new AvoNetworkCallsHandler(this.env);

        this.networkCallsBodyFactory = new AvoNetworkCallsBodyFactory(this.env, libVersion);

        // SPEC.md §12.2: dev sends every event immediately.
        int configuredBatchSize = DEFAULT_BATCH_SIZE;
        if (options.batchSize != null) {
            if (options.batchSize >= 1) {
                configuredBatchSize = options.batchSize;
            } else {
                warn("Invalid batchSize " + options.batchSize + "; using default " + DEFAULT_BATCH_SIZE + ".");
            }
        }
        this.batchSize = resolvedEnv == AvoInspectorEnv.Dev ? 1 : configuredBatchSize;

        double batchFlushSeconds = DEFAULT_BATCH_FLUSH_SECONDS;
        if (options.batchFlushSeconds != null) {
            if (options.batchFlushSeconds > 0 && !options.batchFlushSeconds.isInfinite()) {
                batchFlushSeconds = options.batchFlushSeconds;
            } else {
                warn("Invalid batchFlushSeconds " + options.batchFlushSeconds + "; using default 30.");
            }
        }

        int maxQueueSize = DEFAULT_MAX_QUEUE_SIZE;
        if (options.maxQueueSize != null) {
            if (options.maxQueueSize >= 1) {
                maxQueueSize = options.maxQueueSize;
            } else {
                warn("Invalid maxQueueSize " + options.maxQueueSize + "; using default " + DEFAULT_MAX_QUEUE_SIZE + ".");
            }
        }

        // Not clamped: the conformance suite (batch-4) requires FIFO overflow in this case.
        if (batchSize > maxQueueSize) {
            warn("batchSize " + batchSize + " is larger than maxQueueSize " + maxQueueSize
                    + ", so a batch never fills: events are sent only by the scheduled flush or flush(), and the oldest are dropped once "
                    + maxQueueSize + " are buffered. Set batchSize to at most maxQueueSize.");
        }

        final AvoNetworkCallsHandler handler = this.networkCallsHandler;
        this.batcher = new AvoBatcher(new AvoBatcher.Sender() {
            @Override
            public AvoNetworkCallsHandler.SendResult send(List<Map<String, Object>> events, String apiKey) {
                return handler.send(events, apiKey);
            }
        }, batchSize, batchFlushSeconds, maxQueueSize, options.disableBatchTimer);

        enableLogging(resolvedEnv == AvoInspectorEnv.Dev);
    }

    // SPEC.md §4.1. Control characters are checked first, so a key of only NUL reports that.
    static void validateApiKey(@Nullable String apiKey) {
        if (apiKey == null) {
            throw new IllegalArgumentException(NO_API_KEY_MESSAGE);
        }
        if (apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0 || apiKey.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(API_KEY_CONTROL_CHARACTER_MESSAGE);
        }
        // The spec's message names CR, LF and NUL only, so other control characters get their own.
        if (AvoNetworkCallsHandler.containsControlCharacter(apiKey)) {
            throw new IllegalArgumentException(API_KEY_OTHER_CONTROL_CHARACTER_MESSAGE);
        }
        if (isBlank(apiKey)) {
            throw new IllegalArgumentException(NO_API_KEY_MESSAGE);
        }
    }

    // Empty or Unicode whitespace only (String.trim() misses e.g. U+00A0 and strips controls).
    static boolean isBlank(@Nullable String value) {
        return AvoNetworkCallsBodyFactory.normalize(value) == null;
    }

    private static AvoInspectorEnv resolveEnv(AvoInspectorOptions options) {
        if (options.env != null) {
            return options.env;
        }
        if (options.rawEnv != null) {
            for (AvoInspectorEnv candidate : AvoInspectorEnv.values()) {
                if (candidate.getName().equals(options.rawEnv)) {
                    return candidate;
                }
            }
        }
        warn("Invalid env \"" + options.rawEnv + "\", falling back to \"dev\".");
        return AvoInspectorEnv.Dev;
    }

    @Override
    public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable JSONObject eventProperties) {
        return this.trackSchemaFromEvent(eventName, eventProperties, this.defaultAvoInspectorTarget);
    }

    @Override
    public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable JSONObject eventProperties, @NotNull AvoInspectorTarget overrideAvoInspectorTarget) {
        return trackFromEvent(eventName, eventProperties, overrideAvoInspectorTarget, null, null, false);
    }

    /**
     * Tracks an event with a stream id and gateway options (SPEC.md §4.2, §4.2.1).
     *
     * @param streamId caller-supplied correlation id; {@code null} or empty is sent as ""
     * @param options  gateway coordinates for this call only; may be {@code null}
     */
    @Override
    public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable JSONObject eventProperties, @Nullable String streamId, @Nullable TrackOptions options) {
        return trackFromEvent(eventName, eventProperties, this.defaultAvoInspectorTarget, streamId, options, false);
    }

    /**
     * Tracks an event for another Avo source, with a stream id and gateway options.
     *
     * @param streamId caller-supplied correlation id; {@code null} or empty is sent as ""
     * @param options  gateway coordinates for this call only; may be {@code null}
     */
    @Override
    public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable JSONObject eventProperties, @NotNull AvoInspectorTarget overrideAvoInspectorTarget, @Nullable String streamId, @Nullable TrackOptions options) {
        return trackFromEvent(eventName, eventProperties, overrideAvoInspectorTarget, streamId, options, false);
    }

    @Override
    public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties) {
        return this.trackSchemaFromEvent(eventName, eventProperties, this.defaultAvoInspectorTarget);
    }

    @Override
    public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties, @NotNull AvoInspectorTarget overrideAvoInspectorTarget) {
        return trackFromEvent(eventName, eventProperties, overrideAvoInspectorTarget, null, null, false);
    }

    /**
     * Tracks an event with a stream id and gateway options (SPEC.md §4.2, §4.2.1).
     *
     * @param streamId caller-supplied correlation id; {@code null} or empty is sent as ""
     * @param options  gateway coordinates for this call only; may be {@code null}
     */
    @Override
    public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties, @Nullable String streamId, @Nullable TrackOptions options) {
        return trackFromEvent(eventName, eventProperties, this.defaultAvoInspectorTarget, streamId, options, false);
    }

    /**
     * Tracks an event for another Avo source, with a stream id and gateway options.
     *
     * @param streamId caller-supplied correlation id; {@code null} or empty is sent as ""
     * @param options  gateway coordinates for this call only; may be {@code null}
     */
    @Override
    public @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties, @NotNull AvoInspectorTarget overrideAvoInspectorTarget, @Nullable String streamId, @Nullable TrackOptions options) {
        return trackFromEvent(eventName, eventProperties, overrideAvoInspectorTarget, streamId, options, false);
    }

    /**
     * Test seam for the conformance harness: like {@code trackSchemaFromEvent}, but when the event
     * is sent immediately ({@code batchSize == 1}) it waits for that send and reports a non-200 as an
     * empty schema, which is the per-call outcome of SPEC.md §7.5.
     */
    @NotNull Map<String, AvoEventSchemaType> trackSchemaFromEventAwaitingSend(@NotNull String eventName, @Nullable Object eventProperties, @Nullable String streamId, @Nullable TrackOptions options) {
        return trackFromEvent(eventName, eventProperties, this.defaultAvoInspectorTarget, streamId, options, true);
    }

    private @NotNull Map<String, AvoEventSchemaType> trackFromEvent(@NotNull String eventName, @Nullable Object eventProperties,
                                                                    @NotNull AvoInspectorTarget target, @Nullable String streamId,
                                                                    @Nullable TrackOptions options, boolean awaitImmediateSend) {
        if (destroyed) {
            return new LinkedHashMap<>();
        }
        try {
            logPreExtract(eventName, eventProperties);

            Map<String, AvoEventSchemaType> schema = avoSchemaExtractor.extractSchema(eventProperties, false);

            List<Future<AvoNetworkCallsHandler.SendResult>> sends = trackSchemaInternal(eventName, schema, target, streamId, options);

            if (awaitImmediateSend && batchSize == 1) {
                for (Future<AvoNetworkCallsHandler.SendResult> send : sends) {
                    AvoNetworkCallsHandler.SendResult result = AvoBatcher.await(send,
                            TimeUnit.MILLISECONDS.toNanos(AvoNetworkCallsHandler.TIMEOUT_MS + 1_000));
                    if (result == AvoNetworkCallsHandler.SendResult.NON_200) {
                        return new LinkedHashMap<>();
                    }
                }
            }

            return schema;

        } catch (Exception e) {
            handleException(e, AvoInspector.this.env);
            return new LinkedHashMap<>();
        }
    }

    private void logPreExtract(@NotNull String eventName, @Nullable Object eventProperties) {
        if (isLogging() && eventProperties != null) {
            System.out.println("Avo Inspector: Supplied event " + eventName + " with params \n" + eventProperties);
        }
    }

    @Override
    public void trackSchema(@NotNull String eventName, @Nullable Map<String, AvoEventSchemaType> eventSchema) {
        if (destroyed) {
            return;
        }
        try {
            trackSchemaInternal(eventName, eventSchema, this.defaultAvoInspectorTarget, null, null);
        } catch (Exception e) {
            handleException(e, AvoInspector.this.env);
        }
    }

    private List<Future<AvoNetworkCallsHandler.SendResult>> trackSchemaInternal(@NotNull String eventName, @Nullable Map<String, AvoEventSchemaType> eventSchema,
                                                                                 @NotNull AvoInspectorTarget avoInspectorTarget,
                                                                                 @Nullable String streamId, @Nullable TrackOptions options) {
        if (eventSchema == null) {
            eventSchema = new LinkedHashMap<>();
        }

        if (streamId == null) {
            streamId = "";
        } else if (streamId.indexOf(':') >= 0) {
            // SPEC.md §4.2: warn, but send the value unchanged.
            warn("streamId contains ':'; using the value verbatim.");
        }

        // SPEC.md §7.7: sample each event at enqueue; the body carries the rate that governed it.
        double samplingRate = networkCallsHandler.samplingRate;
        if (ThreadLocalRandom.current().nextDouble() > samplingRate) {
            if (isLogging()) {
                System.out.println("Avo Inspector: Last event schema dropped due to sampling rate");
            }
            return Collections.emptyList();
        }

        Map<String, Object> event = networkCallsBodyFactory.bodyForEventSchemaCall(eventName, eventSchema,
                avoInspectorTarget, streamId, options, samplingRate);

        // Logged before enqueue: the batcher may dispatch this event from within enqueue.
        logPostExtract(eventName, eventSchema);
        return batcher.enqueue(event);
    }

    // Logs the schema as the JSON sent on the wire (eventProperties), so nested children read
    // cleanly. toString() keeps its 1.1.1 form and is not used here.
    static void logPostExtract(@Nullable String eventName, @NotNull Map<String, AvoEventSchemaType> eventSchema) {
        if (isLogging()) {
            String schemaJson = Util.remapProperties(eventSchema).toString();
            if (eventName != null) {
                System.out.println("Avo Inspector: Queued event " + eventName + " with schema " + schemaJson);
            } else {
                System.out.println("Avo Inspector: Parsed schema " + schemaJson);
            }
        }
    }

    @Override
    public @NotNull Map<String, AvoEventSchemaType> extractSchema(@Nullable Object eventProperties) {
        // SPEC.md §4.3: never throws.
        try {
            return avoSchemaExtractor.extractSchema(eventProperties, true);
        } catch (Throwable e) {
            Util.logInternalError(e);
            return new LinkedHashMap<>();
        }
    }

    /**
     * Sends every buffered event and waits up to 10 seconds for all in-flight sends. Call it
     * before the process exits or a serverless handler returns, or buffered events are lost.
     */
    @Override
    public void flush() {
        flush(DEFAULT_FLUSH_TIMEOUT_MS);
    }

    /**
     * Sends every buffered event and waits up to {@code timeoutMs} for all in-flight sends to
     * complete. A negative timeout means the 10 second default. Never throws; the instance stays
     * usable afterwards.
     */
    @Override
    public void flush(long timeoutMs) {
        try {
            batcher.flush(timeoutMs < 0 ? DEFAULT_FLUSH_TIMEOUT_MS : timeoutMs);
        } catch (Throwable e) {
            Util.logInternalError(e);
        }
    }

    /**
     * Terminates the instance: buffered events are discarded unsent, in-flight sends are
     * abandoned, the flush timer stops and the exit-time flush is unregistered. Later track calls do
     * nothing and return an empty schema.
     */
    @Override
    public void destroy() {
        destroyed = true;
        batcher.destroy();
        networkCallsHandler.abortAll();
    }

    // Test-only (runner-contract precondition.samplingRate); deliberately not public.
    void setSamplingRateForTesting(double samplingRate) {
        networkCallsHandler.samplingRate = samplingRate;
    }

    /** Whether logging is on. The flag is process-wide: it applies to every instance. */
    @SuppressWarnings("WeakerAccess")
    static public boolean isLogging() {
        return logsEnabled;
    }

    /**
     * Turns logging on or off for every instance (SPEC.md §4.4). Each constructor also sets it: on
     * in dev, off otherwise. Do not enable it in production.
     */
    @SuppressWarnings("WeakerAccess")
    static public void enableLogging(boolean enabled) {
        logsEnabled = enabled;
    }

    private static void warn(String message) {
        System.err.println("Avo Inspector: " + message);
    }
}
