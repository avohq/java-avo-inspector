package is.avo.inspector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Optional gateway coordinates for one {@code trackSchemaFromEvent} call (SPEC.md §4.2.1).
 *
 * <p>Values are trimmed; a {@code null}, empty or whitespace-only value is treated as not supplied.
 * {@code originHint} is a low-cardinality label of the source the event came from (e.g. "web",
 * "ios"); it must never be a user identifier or any other high-cardinality value.
 */
public final class TrackOptions {

    @Nullable private final String outputReference;
    @Nullable private final String originHint;
    @Nullable private final String originAppVersion;

    private TrackOptions(@Nullable String outputReference, @Nullable String originHint, @Nullable String originAppVersion) {
        this.outputReference = outputReference;
        this.originHint = originHint;
        this.originAppVersion = originAppVersion;
    }

    @NotNull
    public static Builder builder() {
        return new Builder();
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

    public static final class Builder {
        @Nullable private String outputReference;
        @Nullable private String originHint;
        @Nullable private String originAppVersion;

        private Builder() {
        }

        @NotNull
        public Builder outputReference(@Nullable String outputReference) {
            this.outputReference = outputReference;
            return this;
        }

        @NotNull
        public Builder originHint(@Nullable String originHint) {
            this.originHint = originHint;
            return this;
        }

        @NotNull
        public Builder originAppVersion(@Nullable String originAppVersion) {
            this.originAppVersion = originAppVersion;
            return this;
        }

        @NotNull
        public TrackOptions build() {
            return new TrackOptions(outputReference, originHint, originAppVersion);
        }
    }
}
