package is.avo.inspector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;

import java.util.Map;

/**
 * One event for {@link Inspector#trackSchemaFromEvent(InspectorEvent)}: its name and properties,
 * and optionally a stream id, gateway coordinates and another Avo source to send it to.
 *
 * <pre>{@code
 * inspector.trackSchemaFromEvent(InspectorEvent.builder()
 *         .eventName("Purchase")
 *         .eventProperties(properties)
 *         .build());
 * }</pre>
 *
 * <p>Immutable once built. The properties are read when the event is tracked, not copied here.
 * The string values are sent as the SDK normalizes them: a {@code null}, empty or whitespace-only
 * name is sent as {@code "Missing Event Name"}; a {@code null} stream id is sent as {@code ""};
 * the gateway coordinates are trimmed and a blank one counts as not set.
 */
public final class InspectorEvent {

    @Nullable private final String eventName;
    // A Map<String, ?> or a JSONObject.
    @Nullable private final Object eventProperties;
    @Nullable private final String streamId;
    @Nullable private final String outputReference;
    @Nullable private final String originHint;
    @Nullable private final String originAppVersion;
    @Nullable private final AvoInspectorTarget target;

    private InspectorEvent(Builder builder) {
        this.eventName = builder.eventName;
        this.eventProperties = builder.eventProperties;
        this.streamId = builder.streamId;
        this.outputReference = builder.outputReference;
        this.originHint = builder.originHint;
        this.originAppVersion = builder.originAppVersion;
        this.target = builder.target;
    }

    /** Starts an {@link InspectorEvent} with no values set. */
    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    /** The event name. */
    @Nullable
    public String getEventName() {
        return eventName;
    }

    /** The properties: a {@code Map<String, ?>}, a {@code JSONObject}, or {@code null} for none. */
    @Nullable
    public Object getEventProperties() {
        return eventProperties;
    }

    /** Caller-supplied correlation id. */
    @Nullable
    public String getStreamId() {
        return streamId;
    }

    /** Reference of the gateway output this observation was bound for; absent = the gateway checkpoint. */
    @Nullable
    public String getOutputReference() {
        return outputReference;
    }

    /** Low-cardinality label of the source the event came from. */
    @Nullable
    public String getOriginHint() {
        return originHint;
    }

    /** App version of the source that produced this event; overrides the instance's app version. */
    @Nullable
    public String getOriginAppVersion() {
        return originAppVersion;
    }

    /** The Avo source to send this event to instead of the instance's own, or {@code null}. */
    @Nullable
    public AvoInspectorTarget getTarget() {
        return target;
    }

    /** Builds an {@link InspectorEvent}. Every value is optional. */
    public static final class Builder {
        @Nullable private String eventName;
        @Nullable private Object eventProperties;
        @Nullable private String streamId;
        @Nullable private String outputReference;
        @Nullable private String originHint;
        @Nullable private String originAppVersion;
        @Nullable private AvoInspectorTarget target;

        private Builder() {
        }

        /** Sets the event name. A {@code null} or blank name is sent as {@code "Missing Event Name"}. */
        @NotNull
        public Builder eventName(@Nullable String eventName) {
            this.eventName = eventName;
            return this;
        }

        /** Sets the properties. Their order follows the map's iteration order. */
        @NotNull
        public Builder eventProperties(@Nullable Map<String, ?> eventProperties) {
            this.eventProperties = eventProperties;
            return this;
        }

        /** Sets the properties from a {@code JSONObject}, which does not keep insertion order. */
        @NotNull
        public Builder eventProperties(@Nullable JSONObject eventProperties) {
            this.eventProperties = eventProperties;
            return this;
        }

        /** Sets the stream id, any correlation id you choose; {@code null} is sent as {@code ""}. */
        @NotNull
        public Builder streamId(@Nullable String streamId) {
            this.streamId = streamId;
            return this;
        }

        /** Sets the reference of the gateway output the event was bound for. */
        @NotNull
        public Builder outputReference(@Nullable String outputReference) {
            this.outputReference = outputReference;
            return this;
        }

        /** Sets the low-cardinality source label, e.g. "web"; never a user identifier. */
        @NotNull
        public Builder originHint(@Nullable String originHint) {
            this.originHint = originHint;
            return this;
        }

        /** Sets the app version of the source that produced the event. */
        @NotNull
        public Builder originAppVersion(@Nullable String originAppVersion) {
            this.originAppVersion = originAppVersion;
            return this;
        }

        /** Sends the event to another Avo source; {@code null} sends it to the instance's own. */
        @NotNull
        public Builder target(@Nullable AvoInspectorTarget target) {
            this.target = target;
            return this;
        }

        /** Returns the event. */
        @NotNull
        public InspectorEvent build() {
            return new InspectorEvent(this);
        }
    }
}
