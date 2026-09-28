package is.avo.inspector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.json.JSONObject;

import java.util.Map;

@SuppressWarnings("UnusedReturnValue")
public interface Inspector {

    @NotNull
    Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable JSONObject eventProperties);

    @NotNull
    Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable JSONObject eventProperties, @NotNull AvoInspectorTarget overrideAvoInspectorTarget);

    @NotNull
    Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties);

    @NotNull
    Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties, @NotNull AvoInspectorTarget overrideAvoInspectorTarget);

    void trackSchema(@NotNull String eventName, @Nullable Map<String, AvoEventSchemaType> eventSchema);

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

    /** Sends every buffered event and waits up to 10 seconds for in-flight sends. */
    default void flush() {
    }

    /** Sends every buffered event and waits up to {@code timeoutMs} for in-flight sends. */
    default void flush(long timeoutMs) {
    }

    /** Terminates the instance, discarding buffered events. */
    default void destroy() {
    }
}
