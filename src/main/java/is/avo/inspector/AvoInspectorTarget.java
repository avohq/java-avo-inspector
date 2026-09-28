package is.avo.inspector;

import org.jetbrains.annotations.NotNull;

public final class AvoInspectorTarget {
    static final String NO_APP_NAME_MESSAGE = "[Avo Inspector] No app name provided. AvoInspectorTarget requires an app name; use \"\" for none.";

    @NotNull private final String apiKey;
    @NotNull private final String appName;
    @NotNull private final String appVersion;

    /**
     * @throws IllegalArgumentException when {@code apiKey} is blank or contains a control character
     *                                  other than tab, or {@code appName} is {@code null}; the
     *                                  messages are the {@link AvoInspector} constructor's
     */
    public AvoInspectorTarget(@NotNull String apiKey, @NotNull String appName, @NotNull String appVersion) {
        // apiKey travels in the api-key header, so it gets the constructor's checks (SPEC.md §4.1).
        AvoInspector.validateApiKey(apiKey);
        //noinspection ConstantConditions
        if (appName == null) {
            throw new IllegalArgumentException(NO_APP_NAME_MESSAGE);
        }
        this.apiKey = apiKey;
        this.appName = appName;
        this.appVersion = appVersion;
    }

    @NotNull
    public String getApiKey() {
        return apiKey;
    }

    @NotNull
    public String getAppName() {
        return appName;
    }

    @NotNull
    public String getAppVersion() {
        return appVersion;
    }
}
