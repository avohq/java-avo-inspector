package is.avo.inspector;

import org.jetbrains.annotations.NotNull;

public final class AvoInspectorTarget {
    @NotNull private final String apiKey;
    @NotNull private final String appName; 
    @NotNull private final String appVersion;

    public AvoInspectorTarget(@NotNull String apiKey, @NotNull String appName, @NotNull String appVersion) {
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
