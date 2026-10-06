package is.avo.inspector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.json.JSONObject;

import java.util.Map;

/**
 * The Avo Inspector API; {@link AvoInspector} is the implementation.
 *
 * <p>The methods added in 2.0 are default methods, so implementations written for 1.x keep
 * compiling. A custom implementation that wraps an {@link AvoInspector} must override and forward
 * {@link #flush()}, {@link #flush(long)} and {@link #destroy()}: their defaults do nothing, so a
 * wrapper that does not forward them never sends buffered events on flush and never stops the
 * instance it wraps. It should also forward the stream id and {@link TrackOptions} overloads,
 * whose defaults call the 1.x methods and drop the stream id and options.
 */
@SuppressWarnings("UnusedReturnValue")
public interface Inspector {

    /**
     * Extracts the schema of an event's properties and queues it for the Inspector backend.
     *
     * @return the extracted schema, as soon as the event is queued; never waits for the network
     */
    @NotNull
    Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable JSONObject eventProperties);

    /**
     * Extracts the schema of an event's properties and queues it for another Avo source.
     *
     * @return the extracted schema, as soon as the event is queued; never waits for the network
     */
    @NotNull
    Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable JSONObject eventProperties, @NotNull AvoInspectorTarget overrideAvoInspectorTarget);

    /**
     * Extracts the schema of an event's properties and queues it for the Inspector backend.
     * Property order follows the map's iteration order.
     *
     * @return the extracted schema, as soon as the event is queued; never waits for the network
     */
    @NotNull
    Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties);

    /**
     * Extracts the schema of an event's properties and queues it for another Avo source.
     * Property order follows the map's iteration order.
     *
     * @return the extracted schema, as soon as the event is queued; never waits for the network
     */
    @NotNull
    Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties, @NotNull AvoInspectorTarget overrideAvoInspectorTarget);

    /** Queues an event schema you extracted yourself, e.g. with {@link #extractSchema(Object)}. */
    void trackSchema(@NotNull String eventName, @Nullable Map<String, AvoEventSchemaType> eventSchema);

    /**
     * Extracts the schema of an event's properties (SPEC.md §9) without sending anything.
     * Never throws and makes no network calls.
     */
    @NotNull
    Map<String, AvoEventSchemaType> extractSchema(@Nullable Object eventProperties);

    // The methods below were added in 2.0 as defaults, so implementations written against 1.x keep
    // compiling. The defaults ignore streamId and options and delegate to the 1.x methods;
    // AvoInspector implements them fully.

    /** Tracks an event with a stream id and gateway options (SPEC.md §4.2, §4.2.1). */
    @NotNull
    default Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable JSONObject eventProperties,
                                                               @Nullable String streamId, @Nullable TrackOptions options) {
        return trackSchemaFromEvent(eventName, eventProperties);
    }

    /** Tracks an event with a stream id and gateway options (SPEC.md §4.2, §4.2.1). */
    @NotNull
    default Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties,
                                                               @Nullable String streamId, @Nullable TrackOptions options) {
        return trackSchemaFromEvent(eventName, eventProperties);
    }

    /** Tracks an event for another Avo source, with a stream id and gateway options. */
    @NotNull
    default Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable JSONObject eventProperties,
                                                               @NotNull AvoInspectorTarget overrideAvoInspectorTarget,
                                                               @Nullable String streamId, @Nullable TrackOptions options) {
        return trackSchemaFromEvent(eventName, eventProperties, overrideAvoInspectorTarget);
    }

    /** Tracks an event for another Avo source, with a stream id and gateway options. */
    @NotNull
    default Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties,
                                                               @NotNull AvoInspectorTarget overrideAvoInspectorTarget,
                                                               @Nullable String streamId, @Nullable TrackOptions options) {
        return trackSchemaFromEvent(eventName, eventProperties, overrideAvoInspectorTarget);
    }

    /**
     * Sends every buffered event and waits up to 10 seconds for in-flight sends.
     *
     * @return true if nothing is left buffered, waiting or in flight; false if the time ran out. Drained, not delivered: failed sends
     * and dropped events are reported on stderr.
     * The default has nothing to send and returns true.
     */
    default boolean flush() {
        return true;
    }

    /**
     * Sends every buffered event and waits up to {@code timeoutMs} for in-flight sends.
     *
     * @return true if nothing is left buffered, waiting or in flight; false if the timeout ran out. Drained, not delivered: failed sends
     * and dropped events are reported on stderr.
     * The default has nothing to send and returns true.
     */
    default boolean flush(long timeoutMs) {
        return true;
    }

    /** Terminates the instance, discarding buffered events. */
    default void destroy() {
    }
}
