package is.avo.inspector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The Avo Inspector API; {@link AvoInspector} is the implementation.
 *
 * <p>In 2.0 the track call takes one {@link InspectorEvent}, and the interface has no default
 * methods: an implementation written for 1.x must implement
 * {@link #trackSchemaFromEvent(InspectorEvent)}, {@link #flush()}, {@link #flush(long)} and
 * {@link #destroy()}. A wrapper around an {@link AvoInspector} should forward all of them, or the
 * wrapped instance never sends what it buffered.
 */
@SuppressWarnings("UnusedReturnValue")
public interface Inspector {

    /**
     * Extracts the schema of the event's properties and queues the event for the Inspector backend.
     *
     * @return the extracted schema, as soon as the event is queued; never waits for the network
     */
    @NotNull
    Map<String, AvoEventSchemaType> trackSchemaFromEvent(@Nullable InspectorEvent event);

    /** Queues an event schema you extracted yourself, e.g. with {@link #extractSchema(Object)}. */
    void trackSchema(@NotNull String eventName, @Nullable Map<String, AvoEventSchemaType> eventSchema);

    /**
     * Extracts the schema of an event's properties (SPEC.md §9) without sending anything.
     * Never throws and makes no network calls.
     */
    @NotNull
    Map<String, AvoEventSchemaType> extractSchema(@Nullable Object eventProperties);

    /**
     * Sends every buffered event and waits up to 10 seconds for in-flight sends.
     *
     * @return true if nothing is left buffered, waiting or in flight when it returns; false if
     * work is still pending or the flush failed. Drained, not delivered: failed sends and dropped
     * events are reported on stderr.
     */
    boolean flush();

    /**
     * Sends every buffered event and waits up to {@code timeoutMs} for in-flight sends.
     *
     * @return true if nothing is left buffered, waiting or in flight when it returns; false if
     * work is still pending or the flush failed. Drained, not delivered: failed sends and dropped
     * events are reported on stderr.
     */
    boolean flush(long timeoutMs);

    /** Terminates the instance, discarding buffered events. */
    void destroy();
}
