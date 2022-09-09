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
    Map<String, AvoEventSchemaType> trackSchemaFromEvent(@NotNull String eventName, @Nullable Map<String, ?> eventProperties);

    void trackSchema(@NotNull String eventName, @Nullable Map<String, AvoEventSchemaType> eventSchema);

    @NotNull
    Map<String, AvoEventSchemaType> extractSchema(@Nullable Object eventProperties);
}
