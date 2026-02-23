package is.avo.inspector;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.verify;

/**
 * Tests for STORY-05: Anonymous ID / Session Removal (Model C).
 *
 * Acceptance criteria:
 * - bodyForSessionStartedCall method does NOT exist in AvoNetworkCallsBodyFactory
 * - createBaseCallBody sets anonymousId field, NOT sessionId
 * - createBaseCallBody does NOT set trackingId field
 * - No sessionStarted type body is sent in any batch
 * - trackSchemaFromEvent with streamId passes streamId as anonymousId in payload
 * - trackSchemaFromEvent without streamId passes empty string as anonymousId
 * - streamId containing ':' logs warning
 * - Existing 4-param constructor compiles and functions correctly
 * - New 5-param constructor with publicEncryptionKey compiles and stores the key
 */
public class AnonymousIdSessionRemovalTests {

    private AvoInspector sut;

    @Mock
    AvoNetworkCallsHandler mockNetworkCallsHandler;

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        sut = new AvoInspector(
                "apiKey",
                "appVersion",
                "appName",
                AvoInspectorEnv.Dev
        );
        sut.networkCallsHandler = mockNetworkCallsHandler;

        // Capture stdout for warning tests
        System.setOut(new PrintStream(outContent));
    }

    @After
    public void tearDown() {
        System.setOut(originalOut);
    }

    // =========================================================================
    // AC: bodyForSessionStartedCall method does NOT exist
    // =========================================================================

    @Test
    public void bodyForSessionStartedCallMethodDoesNotExist() throws NoSuchMethodException {
        // Verify via reflection that this method no longer exists on the factory
        boolean methodExists = false;
        try {
            AvoNetworkCallsBodyFactory.class.getDeclaredMethod("bodyForSessionStartedCall", AvoInspectorTarget.class);
            methodExists = true;
        } catch (NoSuchMethodException e) {
            methodExists = false;
        }
        assertFalse("bodyForSessionStartedCall should NOT exist in AvoNetworkCallsBodyFactory", methodExists);
    }

    // =========================================================================
    // AC: createBaseCallBody sets anonymousId, NOT sessionId, NOT trackingId
    // =========================================================================

    @Test
    public void trackSchemaFromEventJsonSetsAnonymousIdNotSessionId() {
        // Given
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        JSONObject eventProperties = new JSONObject();
        eventProperties.put("key1", "value1");

        // When
        sut.trackSchemaFromEvent("My Event", eventProperties);

        // Then
        verify(mockNetworkCallsHandler).reportInspectorWithBatchBody(captor.capture());
        List<Map<String, Object>> bodies = captor.getValue();
        assertEquals("Batch should contain exactly 1 body (no session started)", 1, bodies.size());

        Map<String, Object> eventBody = bodies.get(0);
        assertNotNull("anonymousId should be present", eventBody.get("anonymousId"));
        assertNull("sessionId should NOT be present", eventBody.get("sessionId"));
        assertNull("trackingId should NOT be present", eventBody.get("trackingId"));
    }

    @Test
    public void trackSchemaFromEventMapSetsAnonymousIdNotSessionId() {
        // Given
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        Map<String, Object> eventProperties = new HashMap<>();
        eventProperties.put("key1", "value1");

        // When
        sut.trackSchemaFromEvent("My Event", eventProperties);

        // Then
        verify(mockNetworkCallsHandler).reportInspectorWithBatchBody(captor.capture());
        List<Map<String, Object>> bodies = captor.getValue();
        Map<String, Object> eventBody = bodies.get(0);
        assertNotNull("anonymousId should be present", eventBody.get("anonymousId"));
        assertNull("sessionId should NOT be present", eventBody.get("sessionId"));
        assertNull("trackingId should NOT be present", eventBody.get("trackingId"));
    }

    // =========================================================================
    // AC: No sessionStarted type body is sent in any batch
    // =========================================================================

    @Test
    public void trackSchemaFromEventJsonDoesNotSendSessionStartedBody() {
        // Given
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        JSONObject eventProperties = new JSONObject();
        eventProperties.put("key1", "value1");

        // When
        sut.trackSchemaFromEvent("My Event", eventProperties);

        // Then
        verify(mockNetworkCallsHandler).reportInspectorWithBatchBody(captor.capture());
        List<Map<String, Object>> bodies = captor.getValue();

        for (Map<String, Object> body : bodies) {
            assertNotEquals("No body should have type 'sessionStarted'",
                    "sessionStarted", body.get("type"));
        }
    }

    @Test
    public void trackSchemaFromEventMapDoesNotSendSessionStartedBody() {
        // Given
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        Map<String, Object> eventProperties = new HashMap<>();
        eventProperties.put("key1", "value1");

        // When
        sut.trackSchemaFromEvent("My Event", eventProperties);

        // Then
        verify(mockNetworkCallsHandler).reportInspectorWithBatchBody(captor.capture());
        List<Map<String, Object>> bodies = captor.getValue();

        for (Map<String, Object> body : bodies) {
            assertNotEquals("No body should have type 'sessionStarted'",
                    "sessionStarted", body.get("type"));
        }
    }

    // =========================================================================
    // AC: trackSchemaFromEvent with streamId passes streamId as anonymousId
    // =========================================================================

    @Test
    public void trackSchemaFromEventJsonWithStreamIdPassesStreamIdAsAnonymousId() {
        // Given
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        JSONObject eventProperties = new JSONObject();
        eventProperties.put("key1", "value1");
        String streamId = "my-stream-id-123";

        // When
        sut.trackSchemaFromEvent("My Event", eventProperties, streamId);

        // Then
        verify(mockNetworkCallsHandler).reportInspectorWithBatchBody(captor.capture());
        List<Map<String, Object>> bodies = captor.getValue();
        assertEquals(1, bodies.size());
        Map<String, Object> eventBody = bodies.get(0);
        assertEquals("anonymousId should be the streamId", streamId, eventBody.get("anonymousId"));
    }

    @Test
    public void trackSchemaFromEventMapWithStreamIdPassesStreamIdAsAnonymousId() {
        // Given
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        Map<String, Object> eventProperties = new HashMap<>();
        eventProperties.put("key1", "value1");
        String streamId = "my-stream-id-456";

        // When
        sut.trackSchemaFromEvent("My Event", eventProperties, streamId);

        // Then
        verify(mockNetworkCallsHandler).reportInspectorWithBatchBody(captor.capture());
        List<Map<String, Object>> bodies = captor.getValue();
        assertEquals(1, bodies.size());
        Map<String, Object> eventBody = bodies.get(0);
        assertEquals("anonymousId should be the streamId", streamId, eventBody.get("anonymousId"));
    }

    // =========================================================================
    // AC: trackSchemaFromEvent without streamId passes empty string as anonymousId
    // =========================================================================

    @Test
    public void trackSchemaFromEventJsonWithoutStreamIdPassesEmptyStringAsAnonymousId() {
        // Given
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        JSONObject eventProperties = new JSONObject();
        eventProperties.put("key1", "value1");

        // When
        sut.trackSchemaFromEvent("My Event", eventProperties);

        // Then
        verify(mockNetworkCallsHandler).reportInspectorWithBatchBody(captor.capture());
        List<Map<String, Object>> bodies = captor.getValue();
        Map<String, Object> eventBody = bodies.get(0);
        assertEquals("anonymousId should be empty string when no streamId provided",
                "", eventBody.get("anonymousId"));
    }

    @Test
    public void trackSchemaFromEventMapWithoutStreamIdPassesEmptyStringAsAnonymousId() {
        // Given
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        Map<String, Object> eventProperties = new HashMap<>();
        eventProperties.put("key1", "value1");

        // When
        sut.trackSchemaFromEvent("My Event", eventProperties);

        // Then
        verify(mockNetworkCallsHandler).reportInspectorWithBatchBody(captor.capture());
        List<Map<String, Object>> bodies = captor.getValue();
        Map<String, Object> eventBody = bodies.get(0);
        assertEquals("anonymousId should be empty string when no streamId provided",
                "", eventBody.get("anonymousId"));
    }

    // =========================================================================
    // AC: streamId containing ':' logs warning
    // =========================================================================

    @Test
    public void trackSchemaFromEventJsonWithStreamIdContainingColonLogsWarning() {
        // Given
        AvoInspector.enableLogging(true);
        JSONObject eventProperties = new JSONObject();
        eventProperties.put("key1", "value1");
        String streamIdWithColon = "stream:id:with:colons";

        // When
        sut.trackSchemaFromEvent("My Event", eventProperties, streamIdWithColon);

        // Then
        AvoInspector.enableLogging(false);
        String output = outContent.toString();
        assertTrue("Should log warning about colon in streamId",
                output.contains("[Avo Inspector] Warning: streamId contains ':' which is not supported"));
    }

    @Test
    public void trackSchemaFromEventMapWithStreamIdContainingColonLogsWarning() {
        // Given
        AvoInspector.enableLogging(true);
        Map<String, Object> eventProperties = new HashMap<>();
        eventProperties.put("key1", "value1");
        String streamIdWithColon = "stream:id";

        // When
        sut.trackSchemaFromEvent("My Event", eventProperties, streamIdWithColon);

        // Then
        AvoInspector.enableLogging(false);
        String output = outContent.toString();
        assertTrue("Should log warning about colon in streamId",
                output.contains("[Avo Inspector] Warning: streamId contains ':' which is not supported"));
    }

    @Test
    public void trackSchemaFromEventJsonWithStreamIdWithoutColonDoesNotLogWarning() {
        // Given
        AvoInspector.enableLogging(true);
        JSONObject eventProperties = new JSONObject();
        eventProperties.put("key1", "value1");
        String streamIdWithoutColon = "valid-stream-id-123";

        // When
        sut.trackSchemaFromEvent("My Event", eventProperties, streamIdWithoutColon);

        // Then
        AvoInspector.enableLogging(false);
        String output = outContent.toString();
        assertFalse("Should NOT log warning when streamId has no colon",
                output.contains("[Avo Inspector] Warning: streamId contains ':' which is not supported"));
    }

    // =========================================================================
    // AC: Existing 4-param constructor compiles and functions correctly
    // =========================================================================

    @Test
    public void fourParamConstructorCompiles() {
        AvoInspector inspector = new AvoInspector(
                "testApiKey",
                "1.0.0",
                "TestApp",
                AvoInspectorEnv.Prod
        );
        assertNotNull("4-param constructor should produce a valid inspector instance", inspector);
    }

    @Test
    public void fourParamConstructorCreatesDefaultTarget() {
        AvoInspector inspector = new AvoInspector(
                "testApiKey",
                "1.0.0",
                "TestApp",
                AvoInspectorEnv.Prod
        );
        assertNotNull("defaultAvoInspectorTarget should be set", inspector.defaultAvoInspectorTarget);
        assertEquals("testApiKey", inspector.defaultAvoInspectorTarget.getApiKey());
        assertEquals("TestApp", inspector.defaultAvoInspectorTarget.getAppName());
        assertEquals("1.0.0", inspector.defaultAvoInspectorTarget.getAppVersion());
    }

    // =========================================================================
    // AC: New 5-param constructor with publicEncryptionKey compiles and stores the key
    // =========================================================================

    @Test
    public void fiveParamConstructorCompiles() {
        AvoInspector inspector = new AvoInspector(
                "testApiKey",
                "1.0.0",
                "TestApp",
                AvoInspectorEnv.Prod,
                "publicEncryptionKeyHex"
        );
        assertNotNull("5-param constructor should produce a valid inspector instance", inspector);
    }

    @Test
    public void fiveParamConstructorStoresPublicEncryptionKey() {
        String expectedKey = "04abcdef1234567890";
        AvoInspector inspector = new AvoInspector(
                "testApiKey",
                "1.0.0",
                "TestApp",
                AvoInspectorEnv.Prod,
                expectedKey
        );
        assertEquals("publicEncryptionKey should be stored", expectedKey, inspector.publicEncryptionKey);
    }

    @Test
    public void fiveParamConstructorWithNullKeyStoresNull() {
        AvoInspector inspector = new AvoInspector(
                "testApiKey",
                "1.0.0",
                "TestApp",
                AvoInspectorEnv.Prod,
                null
        );
        assertNull("publicEncryptionKey should be null when passed null", inspector.publicEncryptionKey);
    }
}
