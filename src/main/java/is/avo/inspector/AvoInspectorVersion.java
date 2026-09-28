package is.avo.inspector;

/**
 * Library and specification versions.
 *
 * <p>Maintainers: update {@link #VERSION} on every release, together with {@code version} in
 * {@code build.gradle} (the {@code verifyVersionConstant} build check enforces that they match).
 */
public final class AvoInspectorVersion {

    /** The library version, sent as {@code libVersion} on every event (SPEC.md §7.3.3). */
    public static final String VERSION = "1.2.0";

    /** The version of avohq/spec-first-inspector-server-sdk this SDK implements. */
    public static final String SPEC_VERSION = "3.0.1";

    private AvoInspectorVersion() {
    }
}
