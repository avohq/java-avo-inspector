package is.avo.inspector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Constructor options for {@link AvoInspector#AvoInspector(AvoInspectorOptions)} (SPEC.md §4.1, §5).
 *
 * <p>{@code apiKey} and {@code appVersion} are required. Unset batch options take the defaults:
 * {@code batchSize} 30 (forced to 1 in dev), {@code batchFlushSeconds} 30, {@code maxQueueSize}
 * 1000, {@code disableBatchTimer} false.
 */
public final class AvoInspectorOptions {

    @Nullable final String apiKey;
    @Nullable final AvoInspectorEnv env;
    @Nullable final String rawEnv;
    @Nullable final String appVersion;
    @Nullable final String appName;
    @Nullable final Integer batchSize;
    @Nullable final Double batchFlushSeconds;
    @Nullable final Integer maxQueueSize;
    final boolean disableBatchTimer;

    private AvoInspectorOptions(Builder builder) {
        this.apiKey = builder.apiKey;
        this.env = builder.env;
        this.rawEnv = builder.rawEnv;
        this.appVersion = builder.appVersion;
        this.appName = builder.appName;
        this.batchSize = builder.batchSize;
        this.batchFlushSeconds = builder.batchFlushSeconds;
        this.maxQueueSize = builder.maxQueueSize;
        this.disableBatchTimer = builder.disableBatchTimer;
    }

    /** Starts an {@link AvoInspectorOptions} with no values set. */
    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    /** Builds an {@link AvoInspectorOptions}. */
    public static final class Builder {
        @Nullable private String apiKey;
        @Nullable private AvoInspectorEnv env;
        @Nullable private String rawEnv;
        @Nullable private String appVersion;
        @Nullable private String appName;
        @Nullable private Integer batchSize;
        @Nullable private Double batchFlushSeconds;
        @Nullable private Integer maxQueueSize;
        private boolean disableBatchTimer;

        private Builder() {
        }

        /** Required. Must not be blank or contain a control character other than tab. */
        @NotNull
        public Builder apiKey(@Nullable String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * The environment. {@code null}, or never calling this, falls back to
         * {@link AvoInspectorEnv#Dev} with the stderr warning
         * {@code Avo Inspector: Invalid env "null", falling back to "dev".}; the instance then logs
         * (process-wide) and sends every event immediately.
         */
        @NotNull
        public Builder env(@Nullable AvoInspectorEnv env) {
            this.env = env;
            this.rawEnv = null;
            return this;
        }

        /**
         * "dev", "staging" or "prod" (exact, lower case). {@code null} or any other value falls back
         * to dev with the stderr warning {@code Avo Inspector: Invalid env "<value>", falling back to "dev".}
         */
        @NotNull
        public Builder env(@Nullable String env) {
            this.env = null;
            this.rawEnv = env;
            return this;
        }

        /** Required. Must not be blank. */
        @NotNull
        public Builder appVersion(@Nullable String appVersion) {
            this.appVersion = appVersion;
            return this;
        }

        /** Defaults to "". */
        @NotNull
        public Builder appName(@Nullable String appName) {
            this.appName = appName;
            return this;
        }

        /** Events per request; must be at least 1. Ignored in dev, where every event is sent immediately. */
        @NotNull
        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        /** Maximum age of the oldest buffered event before a scheduled flush; must be positive. */
        @NotNull
        public Builder batchFlushSeconds(double batchFlushSeconds) {
            this.batchFlushSeconds = batchFlushSeconds;
            return this;
        }

        /** Buffered events cap; the oldest events are dropped first on overflow. */
        @NotNull
        public Builder maxQueueSize(int maxQueueSize) {
            this.maxQueueSize = maxQueueSize;
            return this;
        }

        /** When true no background flush timer runs; recommended for serverless deployments. */
        @NotNull
        public Builder disableBatchTimer(boolean disableBatchTimer) {
            this.disableBatchTimer = disableBatchTimer;
            return this;
        }

        /** Returns the options; the {@link AvoInspector} constructor validates them. */
        @NotNull
        public AvoInspectorOptions build() {
            return new AvoInspectorOptions(this);
        }
    }
}
