package is.avo.inspector;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// SPEC.md §4: construction, logging, and the public track/extract surface.
public class AvoInspectorTests {

    private final List<AvoInspector> inspectors = new ArrayList<>();

    @After
    public void tearDown() {
        for (AvoInspector inspector : inspectors) {
            inspector.destroy();
        }
        AvoInspector.enableLogging(false);
    }

    private AvoInspector track(AvoInspector inspector) {
        inspectors.add(inspector);
        return inspector;
    }

    private static void assertConstructorThrows(String expectedMessage, AvoInspectorOptions options) {
        try {
            new AvoInspector(options).destroy();
            fail("expected the constructor to throw");
        } catch (IllegalArgumentException e) {
            assertEquals(expectedMessage, e.getMessage());
        }
    }

    @Test
    public void rejectsMissingOrBlankApiKey() {
        String message = "[Avo Inspector] No API key provided. Inspector can't operate without API key.";
        assertConstructorThrows(message, AvoInspectorOptions.builder().appVersion("1.0.0").env(AvoInspectorEnv.Dev).build());
        assertConstructorThrows(message, AvoInspectorOptions.builder().apiKey("").appVersion("1.0.0").build());
        assertConstructorThrows(message, AvoInspectorOptions.builder().apiKey(" \t ").appVersion("1.0.0").build());
        try {
            new AvoInspector("  ", "1.0.0", "App", AvoInspectorEnv.Prod);
            fail("expected the positional constructor to throw");
        } catch (IllegalArgumentException e) {
            assertEquals(message, e.getMessage());
        }
    }

    @Test
    public void rejectsApiKeyWithControlCharacters() {
        String message = "[Avo Inspector] API key contains a control character. The API key is sent as a request header and cannot contain CR, LF, or NUL.";
        for (String key : new String[]{"key\r", "ke\ny", "\0key", "key\r\nX-Injected: 1", "key\u0001\n"}) {
            assertConstructorThrows(message, AvoInspectorOptions.builder().apiKey(key).appVersion("1.0.0").build());
        }
        // Other control characters get a message of their own; the spec message names CR, LF and NUL.
        String other = "Avo Inspector: apiKey must not contain control characters";
        for (String key : new String[]{"key\u0001", "key\u000b", "key\u001b[31m", "key\u001f", "key\u007f",
                "key\u0080", "key\u0085", "key\u009f"}) {
            assertConstructorThrows(other, AvoInspectorOptions.builder().apiKey(key).appVersion("1.0.0").build());
        }
    }

    @Test
    public void apiKeyMayContainATab() {
        track(new AvoInspector(AvoInspectorOptions.builder().apiKey("key\twith-tab").appVersion("1.0.0").build()));
    }

    @Test
    public void unicodeWhitespaceIsBlank() {
        String noKey = "[Avo Inspector] No API key provided. Inspector can't operate without API key.";
        assertConstructorThrows(noKey, AvoInspectorOptions.builder().apiKey("\u00a0\u2003").appVersion("1.0.0").build());
        assertConstructorThrows(noKey, AvoInspectorOptions.builder().apiKey("\t").appVersion("1.0.0").build());
        String noVersion = "[Avo Inspector] No version provided. Many features of Inspector rely on versioning. Please provide comparable string version, i.e. integer or semantic.";
        assertConstructorThrows(noVersion, AvoInspectorOptions.builder().apiKey("key").appVersion("\u3000").build());
    }

    @Test
    public void controlCharactersAreReportedBeforeBlankness() {
        String message = "[Avo Inspector] API key contains a control character. The API key is sent as a request header and cannot contain CR, LF, or NUL.";
        assertConstructorThrows(message, AvoInspectorOptions.builder().apiKey("\0").appVersion("1.0.0").build());
        assertConstructorThrows(message, AvoInspectorOptions.builder().apiKey(" \r\n ").appVersion("1.0.0").build());
    }

    private static void assertTargetThrows(String expectedMessage, String apiKey, String appName) {
        try {
            new AvoInspectorTarget(apiKey, appName, "1.0.0");
            fail("expected the AvoInspectorTarget constructor to throw");
        } catch (IllegalArgumentException e) {
            assertEquals(expectedMessage, e.getMessage());
        }
    }

    @Test
    public void inspectorTargetRequiresAVersionLikeTheConstructor() {
        String noVersion = "[Avo Inspector] No version provided. Many features of Inspector rely on versioning. Please provide comparable string version, i.e. integer or semantic.";
        for (String version : new String[]{null, "", "  ", " "}) {
            try {
                new AvoInspectorTarget("key", "App", version);
                fail("expected the AvoInspectorTarget constructor to throw for " + version);
            } catch (IllegalArgumentException e) {
                assertEquals(noVersion, e.getMessage());
            }
        }
        assertEquals("2.0.0", new AvoInspectorTarget("key", "App", "2.0.0").getAppVersion());
    }

    @Test
    public void inspectorTargetValidatesLikeTheConstructor() {
        String noKey = "[Avo Inspector] No API key provided. Inspector can't operate without API key.";
        String control = "[Avo Inspector] API key contains a control character. The API key is sent as a request header and cannot contain CR, LF, or NUL.";
        assertTargetThrows(noKey, null, "App");
        assertTargetThrows(noKey, " \u00a0", "App");
        assertTargetThrows(control, "bad\rkey", "App");
        assertTargetThrows(control, "\0", "App");
        assertTargetThrows("Avo Inspector: apiKey must not contain control characters", "key\u0007", "App");
        assertTargetThrows("Avo Inspector: apiKey must not contain control characters", "key\u0085", "App");
        assertTargetThrows("[Avo Inspector] No app name provided. AvoInspectorTarget requires an app name; use \"\" for none.", "key", null);

        new AvoInspectorTarget("key\twith-tab", "", "1.0.0");
    }

    @Test
    public void rejectsMissingOrBlankVersion() {
        String message = "[Avo Inspector] No version provided. Many features of Inspector rely on versioning. Please provide comparable string version, i.e. integer or semantic.";
        assertConstructorThrows(message, AvoInspectorOptions.builder().apiKey("key").build());
        assertConstructorThrows(message, AvoInspectorOptions.builder().apiKey("key").appVersion("   ").build());
    }

    @Test
    public void versionIsNotControlCharacterChecked() {
        track(new AvoInspector(AvoInspectorOptions.builder().apiKey("key").appVersion("1.0\n").env(AvoInspectorEnv.Prod).build()));
    }

    @Test
    public void invalidEnvFallsBackToDev() {
        AvoInspector fromString = track(new AvoInspector(AvoInspectorOptions.builder().apiKey("key").appVersion("1").env("production").build()));
        assertEquals("dev", fromString.env);
        assertEquals(1, fromString.batchSize);

        AvoInspector fromNull = track(new AvoInspector(AvoInspectorOptions.builder().apiKey("key").appVersion("1").build()));
        assertEquals("dev", fromNull.env);

        AvoInspector valid = track(new AvoInspector(AvoInspectorOptions.builder().apiKey("key").appVersion("1").env("staging").build()));
        assertEquals("staging", valid.env);
    }

    @Test
    public void loggingDefaultFollowsEnvAndIsProcessWide() {
        track(new AvoInspector("key", "1.0.0", "App", AvoInspectorEnv.Dev));
        assertTrue(AvoInspector.isLogging());

        AvoInspector prod = track(new AvoInspector("key", "1.0.0", "App", AvoInspectorEnv.Prod));
        assertFalse(AvoInspector.isLogging());

        AvoInspector.enableLogging(true);
        assertTrue(AvoInspector.isLogging());
        prod.destroy();
        assertTrue(AvoInspector.isLogging());
    }

    @Test
    public void appNameDefaultsToEmpty() {
        AvoInspector inspector = track(new AvoInspector(AvoInspectorOptions.builder().apiKey("key").appVersion("1").env(AvoInspectorEnv.Prod).build()));
        assertEquals("", inspector.defaultAvoInspectorTarget.getAppName());
    }

    @Test
    public void trackReturnsTheExtractedSchemaInInputOrder() {
        AvoInspector inspector = track(new AvoInspector("key", "1.0.0", "App", AvoInspectorEnv.Staging));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("zeta", 1);
        props.put("alpha", "a");
        props.put("mid", 0.0);

        Map<String, AvoEventSchemaType> schema = inspector.trackSchemaFromEvent("Event", props);

        assertEquals(new ArrayList<>(props.keySet()), new ArrayList<>(schema.keySet()));
        assertEquals("float", schema.get("mid").toString());

        JSONObject json = new JSONObject();
        json.put("n", 1);
        assertEquals("int", inspector.trackSchemaFromEvent("Event", json, "stream", null).get("n").toString());
    }

    @Test
    public void extractSchemaNeverThrowsEvenInDev() {
        AvoInspector inspector = track(new AvoInspector("key", "1.0.0", "App", AvoInspectorEnv.Dev));
        Map<Object, Object> props = new java.util.HashMap<>();
        props.put("x", 1);
        Map<Object, Object> broken = new java.util.AbstractMap<Object, Object>() {
            @Override
            public java.util.Set<Entry<Object, Object>> entrySet() {
                throw new IllegalStateException("boom");
            }
        };

        assertTrue(inspector.extractSchema(broken).isEmpty());
        assertTrue(inspector.extractSchema(null).isEmpty());
        assertEquals(1, inspector.extractSchema(props).size());
    }

    @Test
    public void internalErrorInDevRethrowsTheSpecMessage() {
        AvoInspector inspector = track(new AvoInspector("key", "1.0.0", "App", AvoInspectorEnv.Dev));
        Map<String, Object> broken = new java.util.AbstractMap<String, Object>() {
            @Override
            public java.util.Set<Entry<String, Object>> entrySet() {
                throw new IllegalStateException("boom");
            }
        };
        try {
            inspector.trackSchemaFromEvent("Event", broken);
            fail("expected a rethrow in dev");
        } catch (RuntimeException e) {
            assertEquals("Avo Inspector: something went wrong. Please report to support@avo.app.", e.getMessage());
        }

        AvoInspector prod = track(new AvoInspector("key", "1.0.0", "App", AvoInspectorEnv.Prod));
        assertTrue(prod.trackSchemaFromEvent("Event", broken).isEmpty());
    }

    @Test
    public void versionConstantsArePlainSemver() {
        assertTrue(AvoInspectorVersion.VERSION.matches("^\\d+\\.\\d+\\.\\d+$"));
        assertEquals("3.0.1", AvoInspectorVersion.SPEC_VERSION);
    }
}
