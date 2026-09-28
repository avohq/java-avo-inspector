package is.avo.inspector;

/** The environment an {@link AvoInspector} runs in; it sets batching and logging defaults. */
public enum AvoInspectorEnv {

    Prod("prod"),
    Dev("dev"),
    Staging("staging");

    private final String name;

    AvoInspectorEnv(String name) {
        this.name = name;
    }

    /** The wire name: {@code "prod"}, {@code "dev"} or {@code "staging"}. */
    public String getName() {
        return name;
    }
}
