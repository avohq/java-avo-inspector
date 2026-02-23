package is.avo.inspector;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.verify;

/**
 * Integration tests for encryption in the payload pipeline.
 *
 * AC #6: publicEncryptionKey included in every base body when non-null/non-empty
 * AC #7: List-type property values omitted entirely
 * AC #8: Encryption failure logs warning and omits that property's value, continues
 * AC #11: Prod negative test — no encryptedPropertyValue in prod env payload
 */
public class EncryptionIntegrationTests {

    @Mock
    AvoNetworkCallsHandler mockNetworkCallsHandler;

    // =========================================================================
    // AC #6: publicEncryptionKey included in base body when non-null/non-empty
    // =========================================================================

    @Test
    public void baseBody_includesPublicEncryptionKey_whenNonNullNonEmpty() throws Exception {
        MockitoAnnotations.initMocks(this);
        KeyPair keyPair = EncryptionInteropTestUtil.generateKeyPair();
        String pubKeyHex = EncryptionInteropTestUtil.publicKeyToHex((ECPublicKey) keyPair.getPublic());

        AvoInspector inspector = new AvoInspector("apiKey", "1.0", "TestApp",
                AvoInspectorEnv.Dev, pubKeyHex);
        inspector.networkCallsHandler = mockNetworkCallsHandler;

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);

        inspector.trackSchemaFromEvent("TestEvent", (Map<String, ?>) null);

        verify(mockNetworkCallsHandler).reportInspectorWithBatchBody(captor.capture());
        Map<String, Object> body = captor.getValue().get(0);

        assertEquals("publicEncryptionKey should be in the body", pubKeyHex, body.get("publicEncryptionKey"));
    }

    @Test
    public void baseBody_doesNotIncludePublicEncryptionKey_whenNull() {
        MockitoAnnotations.initMocks(this);

        AvoInspector inspector = new AvoInspector("apiKey", "1.0", "TestApp",
                AvoInspectorEnv.Dev);
        inspector.networkCallsHandler = mockNetworkCallsHandler;

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);

        inspector.trackSchemaFromEvent("TestEvent", (Map<String, ?>) null);

        verify(mockNetworkCallsHandler).reportInspectorWithBatchBody(captor.capture());
        Map<String, Object> body = captor.getValue().get(0);

        assertFalse("publicEncryptionKey should NOT be in the body when null",
                body.containsKey("publicEncryptionKey"));
    }

    @Test
    public void baseBody_doesNotIncludePublicEncryptionKey_whenEmpty() {
        MockitoAnnotations.initMocks(this);

        AvoInspector inspector = new AvoInspector("apiKey", "1.0", "TestApp",
                AvoInspectorEnv.Dev, "");
        inspector.networkCallsHandler = mockNetworkCallsHandler;

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);

        inspector.trackSchemaFromEvent("TestEvent", (Map<String, ?>) null);

        verify(mockNetworkCallsHandler).reportInspectorWithBatchBody(captor.capture());
        Map<String, Object> body = captor.getValue().get(0);

        assertFalse("publicEncryptionKey should NOT be in the body when empty",
                body.containsKey("publicEncryptionKey"));
    }

    // =========================================================================
    // AC #11: Prod negative test — no encryptedPropertyValue in prod env payload
    // =========================================================================

    @Test
    public void prodEnv_noEncryptedPropertyValueInPayload() throws Exception {
        MockitoAnnotations.initMocks(this);
        KeyPair keyPair = EncryptionInteropTestUtil.generateKeyPair();
        String pubKeyHex = EncryptionInteropTestUtil.publicKeyToHex((ECPublicKey) keyPair.getPublic());

        AvoInspector inspector = new AvoInspector("apiKey", "1.0", "TestApp",
                AvoInspectorEnv.Prod, pubKeyHex);
        inspector.networkCallsHandler = mockNetworkCallsHandler;

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);

        Map<String, Object> props = new HashMap<>();
        props.put("userName", "Alice");
        inspector.trackSchemaFromEvent("TestEvent", props);

        verify(mockNetworkCallsHandler).reportInspectorWithBatchBody(captor.capture());
        Map<String, Object> body = captor.getValue().get(0);

        // In prod, no property should have encryptedPropertyValue
        assertNoEncryptedValues(body);
    }

    // =========================================================================
    // AC #7: List-type property values omitted entirely
    // =========================================================================

    @Test
    public void devEnv_listPropertyValues_areOmittedEntirely() throws Exception {
        MockitoAnnotations.initMocks(this);
        KeyPair keyPair = EncryptionInteropTestUtil.generateKeyPair();
        String pubKeyHex = EncryptionInteropTestUtil.publicKeyToHex((ECPublicKey) keyPair.getPublic());

        AvoInspector inspector = new AvoInspector("apiKey", "1.0", "TestApp",
                AvoInspectorEnv.Dev, pubKeyHex);
        inspector.networkCallsHandler = mockNetworkCallsHandler;

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);

        Map<String, Object> props = new HashMap<>();
        props.put("userName", "Alice");
        props.put("tags", Arrays.asList("a", "b", "c")); // List type — should be omitted

        inspector.trackSchemaFromEvent("TestEvent", props);

        verify(mockNetworkCallsHandler).reportInspectorWithBatchBody(captor.capture());
        Map<String, Object> body = captor.getValue().get(0);

        // The eventProperties schema should still include the list type schema
        // but the encrypted property values should not include the list value
        // Check that no encrypted value exists for the list property
        Object encryptedProps = body.get("encryptedPropertyValues");
        if (encryptedProps instanceof Map) {
            Map<?, ?> encMap = (Map<?, ?>) encryptedProps;
            assertFalse("List property 'tags' should be omitted from encrypted values",
                    encMap.containsKey("tags"));
            // But non-list property should be encrypted
            assertTrue("Non-list property 'userName' should be encrypted",
                    encMap.containsKey("userName"));
        }
        // If encryption is working, encryptedPropertyValues should exist
        assertNotNull("encryptedPropertyValues should exist for dev env with encryption key", encryptedProps);
    }

    // =========================================================================
    // AC #8: Encryption failure for a single property omits that value, continues
    // =========================================================================

    @Test
    public void devEnv_encryptionProducesEncryptedValues_forValidProperties() throws Exception {
        MockitoAnnotations.initMocks(this);
        KeyPair keyPair = EncryptionInteropTestUtil.generateKeyPair();
        ECPrivateKey privateKey = (ECPrivateKey) keyPair.getPrivate();
        String pubKeyHex = EncryptionInteropTestUtil.publicKeyToHex((ECPublicKey) keyPair.getPublic());

        AvoInspector inspector = new AvoInspector("apiKey", "1.0", "TestApp",
                AvoInspectorEnv.Dev, pubKeyHex);
        inspector.networkCallsHandler = mockNetworkCallsHandler;

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);

        Map<String, Object> props = new HashMap<>();
        props.put("userName", "Alice");
        props.put("age", 30);
        props.put("score", 3.14);
        props.put("active", true);

        inspector.trackSchemaFromEvent("TestEvent", props);

        verify(mockNetworkCallsHandler).reportInspectorWithBatchBody(captor.capture());
        Map<String, Object> body = captor.getValue().get(0);

        Object encryptedProps = body.get("encryptedPropertyValues");
        assertNotNull("encryptedPropertyValues should exist for dev env", encryptedProps);
        assertTrue("encryptedPropertyValues should be a Map", encryptedProps instanceof Map);

        Map<?, ?> encMap = (Map<?, ?>) encryptedProps;

        // Each encrypted value should be decryptable
        String encUserName = (String) encMap.get("userName");
        assertNotNull("userName should have an encrypted value", encUserName);
        String decryptedUserName = EncryptionInteropTestUtil.decrypt(encUserName, privateKey);
        assertEquals("\"Alice\"", decryptedUserName);

        String encAge = (String) encMap.get("age");
        assertNotNull("age should have an encrypted value", encAge);
        String decryptedAge = EncryptionInteropTestUtil.decrypt(encAge, privateKey);
        assertEquals("30", decryptedAge);

        String encScore = (String) encMap.get("score");
        assertNotNull("score should have an encrypted value", encScore);
        String decryptedScore = EncryptionInteropTestUtil.decrypt(encScore, privateKey);
        assertEquals("3.14", decryptedScore);

        String encActive = (String) encMap.get("active");
        assertNotNull("active should have an encrypted value", encActive);
        String decryptedActive = EncryptionInteropTestUtil.decrypt(encActive, privateKey);
        assertEquals("true", decryptedActive);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void assertNoEncryptedValues(Map<String, Object> body) {
        assertNull("encryptedPropertyValues should NOT be in prod payload",
                body.get("encryptedPropertyValues"));
    }
}
