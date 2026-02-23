package is.avo.inspector;

import com.google.re2j.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates event property types against event spec rules using RE2J regex.
 *
 * RE2J is used for all regex evaluation to guarantee linear-time matching
 * and prevent catastrophic backtracking.
 *
 * Bandwidth optimization for per-property results:
 * - Only include passedEventIds when its size is strictly less than failedEventIds size
 * - When sizes are equal, use failedEventIds (tie-breaker)
 */
class EventValidator {

    // Cache compiled patterns to avoid recompilation
    private final Map<String, Pattern> patternCache = new HashMap<>();

    /**
     * Validate event properties against spec rules.
     *
     * @param streamId   The stream identifier
     * @param schema     The extracted event schema (property name -> type)
     * @param specResponse The event spec response with metadata and rules
     * @return ValidationResult or null if spec has no rules
     */
    @Nullable
    AvoEventSpecFetchTypes.ValidationResult validate(
            @NotNull String streamId,
            @NotNull Map<String, AvoEventSchemaType> schema,
            @NotNull AvoEventSpecFetchTypes.EventSpecResponse specResponse) {

        if (specResponse.metadata == null || specResponse.propertyRules == null) {
            return null;
        }

        List<String> passedEventIds = new ArrayList<>();
        List<String> failedEventIds = new ArrayList<>();
        Map<String, AvoEventSpecFetchTypes.PropertyValidation> propertyValidations = new HashMap<>();

        for (AvoEventSpecFetchTypes.PropertyRule rule : specResponse.propertyRules) {
            String propertyName = rule.propertyName;
            String typePattern = rule.typePattern;

            AvoEventSchemaType actualType = schema.get(propertyName);
            String actualTypeName = actualType != null ? actualType.getReportedName() : "null";

            boolean matches = matchesPattern(typePattern, actualTypeName);

            String ruleId = propertyName + ":" + typePattern;

            if (matches) {
                passedEventIds.add(ruleId);
            } else {
                failedEventIds.add(ruleId);
            }

            // Bandwidth optimization per property
            List<String> propPassed = new ArrayList<>();
            List<String> propFailed = new ArrayList<>();
            if (matches) {
                propPassed.add(ruleId);
            } else {
                propFailed.add(ruleId);
            }

            AvoEventSpecFetchTypes.PropertyValidation propValidation =
                    buildBandwidthOptimizedValidation(propPassed, propFailed);
            propertyValidations.put(propertyName, propValidation);
        }

        // Global bandwidth optimization
        List<String> optimizedPassed;
        List<String> optimizedFailed;
        if (passedEventIds.size() < failedEventIds.size()) {
            optimizedPassed = passedEventIds;
            optimizedFailed = new ArrayList<>();
        } else {
            // Equal or failed is smaller: use failedEventIds
            optimizedPassed = new ArrayList<>();
            optimizedFailed = failedEventIds;
        }

        return new AvoEventSpecFetchTypes.ValidationResult(
                streamId,
                specResponse.metadata,
                optimizedPassed,
                optimizedFailed,
                propertyValidations
        );
    }

    /**
     * Build bandwidth-optimized property validation.
     * passedEventIds returned only when strictly smaller than failedEventIds.
     * Equal-size arrays use failedEventIds (tie-breaker).
     */
    private AvoEventSpecFetchTypes.PropertyValidation buildBandwidthOptimizedValidation(
            List<String> passed, List<String> failed) {
        if (passed.size() < failed.size()) {
            return new AvoEventSpecFetchTypes.PropertyValidation(passed, null);
        } else {
            return new AvoEventSpecFetchTypes.PropertyValidation(null, failed);
        }
    }

    /**
     * Match a type string against a RE2J pattern.
     */
    boolean matchesPattern(@NotNull String pattern, @NotNull String value) {
        try {
            Pattern compiled = patternCache.get(pattern);
            if (compiled == null) {
                compiled = Pattern.compile(pattern);
                patternCache.put(pattern, compiled);
            }
            return compiled.matcher(value).matches();
        } catch (Exception e) {
            if (AvoInspector.isLogging()) {
                System.err.println("AvoInspector: Invalid regex pattern: " + pattern + " - " + e.getMessage());
            }
            return false;
        }
    }
}
