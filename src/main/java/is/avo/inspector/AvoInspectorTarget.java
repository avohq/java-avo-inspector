package is.avo.inspector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record AvoInspectorTarget(@NotNull String apiKey, @NotNull String appName, @NotNull String appVersion) {}
