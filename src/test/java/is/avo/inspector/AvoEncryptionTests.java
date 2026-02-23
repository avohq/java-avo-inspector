package is.avo.inspector;

import org.junit.Test;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

import static org.junit.Assert.*;

/**
 * Tests for AvoEncryption — ECIES encryption for Java JVM Inspector SDK.
 */
public class AvoEncryptionTests {

    // =========================================================================
    // shouldEncrypt() tests
    // =========================================================================

    @Test
    public void shouldEncrypt_returnsFalseWhenEnvIsProd() {
        assertFalse(AvoEncryption.shouldEncrypt("prod", "somePubKeyHex"));
    }

    @Test
    public void shouldEncrypt_returnsFalseWhenPublicKeyIsNull() {
        assertFalse(AvoEncryption.shouldEncrypt("dev", null));
    }

    @Test
    public void shouldEncrypt_returnsFalseWhenPublicKeyIsEmpty() {
        assertFalse(AvoEncryption.shouldEncrypt("dev", ""));
    }

    @Test
    public void shouldEncrypt_returnsFalseWhenProdAndNullKey() {
        assertFalse(AvoEncryption.shouldEncrypt("prod", null));
    }

    @Test
    public void shouldEncrypt_returnsTrueForDevWithNonEmptyKey() {
        assertTrue(AvoEncryption.shouldEncrypt("dev", "abcdef1234"));
    }

    @Test
    public void shouldEncrypt_returnsTrueForStagingWithNonEmptyKey() {
        assertTrue(AvoEncryption.shouldEncrypt("staging", "abcdef1234"));
    }

    // =========================================================================
    // encrypt() — wire format tests
    // =========================================================================

    @Test
    public void encrypt_producesOutputWithCorrectMinimumLength() throws Exception {
        KeyPair keyPair = EncryptionInteropTestUtil.generateKeyPair();
        String pubKeyHex = EncryptionInteropTestUtil.publicKeyToHex((ECPublicKey) keyPair.getPublic());

        String encrypted = AvoEncryption.encrypt("hello world", pubKeyHex);
        assertNotNull(encrypted);

        byte[] decoded = Base64.getDecoder().decode(encrypted);
        assertTrue("Output should be at least 99 bytes, got " + decoded.length, decoded.length >= 99);
    }

    @Test
    public void encrypt_outputStartsWithVersionByte0x00() throws Exception {
        KeyPair keyPair = EncryptionInteropTestUtil.generateKeyPair();
        String pubKeyHex = EncryptionInteropTestUtil.publicKeyToHex((ECPublicKey) keyPair.getPublic());

        String encrypted = AvoEncryption.encrypt("test", pubKeyHex);
        byte[] decoded = Base64.getDecoder().decode(encrypted);

        assertEquals("Version byte should be 0x00", 0x00, decoded[0]);
    }

    @Test
    public void encrypt_outputHasUncompressedPointMarker0x04() throws Exception {
        KeyPair keyPair = EncryptionInteropTestUtil.generateKeyPair();
        String pubKeyHex = EncryptionInteropTestUtil.publicKeyToHex((ECPublicKey) keyPair.getPublic());

        String encrypted = AvoEncryption.encrypt("test", pubKeyHex);
        byte[] decoded = Base64.getDecoder().decode(encrypted);

        assertEquals("Ephemeral key should start with 0x04", 0x04, decoded[1]);
    }

    @Test
    public void encrypt_differentEncryptionsProduceDifferentOutput() throws Exception {
        KeyPair keyPair = EncryptionInteropTestUtil.generateKeyPair();
        String pubKeyHex = EncryptionInteropTestUtil.publicKeyToHex((ECPublicKey) keyPair.getPublic());

        String encrypted1 = AvoEncryption.encrypt("same plaintext", pubKeyHex);
        String encrypted2 = AvoEncryption.encrypt("same plaintext", pubKeyHex);

        assertNotEquals("Different encryptions should produce different output", encrypted1, encrypted2);
    }

    // =========================================================================
    // encrypt() — returns null on failure
    // =========================================================================

    @Test
    public void encrypt_returnsNullForInvalidPublicKey() {
        String result = AvoEncryption.encrypt("hello", "not-a-valid-hex-key");
        assertNull("Should return null for invalid public key", result);
    }

    @Test
    public void encrypt_returnsNullForEmptyPublicKey() {
        String result = AvoEncryption.encrypt("hello", "");
        assertNull("Should return null for empty public key", result);
    }

    // =========================================================================
    // Cross-SDK interop tests (AC #10)
    // =========================================================================

    @Test
    public void interop_decryptMatchesPlaintext_helloWorld() throws Exception {
        assertInteropRoundTrip("hello world");
    }

    @Test
    public void interop_decryptMatchesPlaintext_testStringValue() throws Exception {
        assertInteropRoundTrip("test string value");
    }

    @Test
    public void interop_decryptMatchesPlaintext_42() throws Exception {
        assertInteropRoundTrip("42");
    }

    @Test
    public void interop_decryptMatchesPlaintext_314() throws Exception {
        assertInteropRoundTrip("3.14");
    }

    @Test
    public void interop_decryptMatchesPlaintext_true() throws Exception {
        assertInteropRoundTrip("true");
    }

    /**
     * Helper: encrypt with AvoEncryption.encrypt(), decrypt with EncryptionInteropTestUtil.decrypt(),
     * verify round-trip equals original plaintext.
     */
    private void assertInteropRoundTrip(String plaintext) throws Exception {
        KeyPair keyPair = EncryptionInteropTestUtil.generateKeyPair();
        ECPublicKey publicKey = (ECPublicKey) keyPair.getPublic();
        ECPrivateKey privateKey = (ECPrivateKey) keyPair.getPrivate();
        String pubKeyHex = EncryptionInteropTestUtil.publicKeyToHex(publicKey);

        String encrypted = AvoEncryption.encrypt(plaintext, pubKeyHex);
        assertNotNull("Encryption should succeed for: " + plaintext, encrypted);

        String decrypted = EncryptionInteropTestUtil.decrypt(encrypted, privateKey);
        assertEquals("Interop round-trip should preserve: " + plaintext, plaintext, decrypted);
    }

    // =========================================================================
    // Uses java.util.Base64 (AC #1) — compile-time guarantee
    // =========================================================================

    @Test
    public void encrypt_usesJavaUtilBase64_outputIsValidBase64() throws Exception {
        KeyPair keyPair = EncryptionInteropTestUtil.generateKeyPair();
        String pubKeyHex = EncryptionInteropTestUtil.publicKeyToHex((ECPublicKey) keyPair.getPublic());

        String encrypted = AvoEncryption.encrypt("test", pubKeyHex);
        assertNotNull(encrypted);

        // This uses java.util.Base64 — will throw if not valid base64
        byte[] decoded = Base64.getDecoder().decode(encrypted);
        assertNotNull(decoded);
        assertTrue(decoded.length > 0);
    }
}
