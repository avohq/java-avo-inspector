package is.avo.inspector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Types for event spec fetch responses used in event validation.
 */
class AvoEventSpecFetchTypes {

    /**
     * Metadata about an event spec, returned from the spec endpoint.
     */
    static class EventSpecMetadata {
        @NotNull final String schemaId;
        @NotNull final String branchId;
        @NotNull final String latestActionId;
        @NotNull final String sourceId;

        EventSpecMetadata(@NotNull String schemaId, @NotNull String branchId,
                          @NotNull String latestActionId, @NotNull String sourceId) {
            this.schemaId = schemaId;
            this.branchId = branchId;
            this.latestActionId = latestActionId;
            this.sourceId = sourceId;
        }
    }

    /**
     * A single property rule within an event spec.
     * The typePattern is a RE2J regex that the property type must match.
     */
    static class PropertyRule {
        @NotNull final String propertyName;
        @NotNull final String typePattern;

        PropertyRule(@NotNull String propertyName, @NotNull String typePattern) {
            this.propertyName = propertyName;
            this.typePattern = typePattern;
        }
    }

    /**
     * Full event spec response from the API.
     * A null eventSpec (with non-null metadata) means the event is unknown.
     */
    static class EventSpecResponse {
        @Nullable final EventSpecMetadata metadata;
        @Nullable final List<PropertyRule> propertyRules;

        EventSpecResponse(@Nullable EventSpecMetadata metadata,
                          @Nullable List<PropertyRule> propertyRules) {
            this.metadata = metadata;
            this.propertyRules = propertyRules;
        }
    }

    /**
     * Result of validating an event against its spec.
     */
    static class ValidationResult {
        @NotNull final String streamId;
        @NotNull final EventSpecMetadata metadata;
        @NotNull final List<String> passedEventIds;
        @NotNull final List<String> failedEventIds;
        @NotNull final Map<String, PropertyValidation> propertyValidations;

        ValidationResult(@NotNull String streamId,
                         @NotNull EventSpecMetadata metadata,
                         @NotNull List<String> passedEventIds,
                         @NotNull List<String> failedEventIds,
                         @NotNull Map<String, PropertyValidation> propertyValidations) {
            this.streamId = streamId;
            this.metadata = metadata;
            this.passedEventIds = passedEventIds;
            this.failedEventIds = failedEventIds;
            this.propertyValidations = propertyValidations;
        }
    }

    /**
     * Per-property validation results with bandwidth optimization.
     * Only passedEventIds OR failedEventIds is populated:
     * - passedEventIds only when strictly smaller than failedEventIds
     * - failedEventIds when equal or smaller (tie-breaker: use failedEventIds)
     */
    static class PropertyValidation {
        @Nullable final List<String> passedEventIds;
        @Nullable final List<String> failedEventIds;

        PropertyValidation(@Nullable List<String> passedEventIds,
                           @Nullable List<String> failedEventIds) {
            this.passedEventIds = passedEventIds;
            this.failedEventIds = failedEventIds;
        }
    }

    /**
     * Callback interface for async spec fetching.
     */
    interface FetchCallback {
        void onResult(@Nullable EventSpecResponse response);
    }
}
