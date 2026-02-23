package is.avo.inspector;

import org.junit.Test;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

/**
 * Shared cross-SDK encryption interoperability test utility.
 *
 * Provides a reference Java decryptor for the Avo ECIES encryption format:
 *   [Version 0x00 (1B)] [EphemeralPubKey (65B)] [IV (16B)] [AuthTag (16B)] [Ciphertext (variable)]
 *
 * Algorithm:
 *   1. Base64-decode the ciphertext
 *   2. Assert data[0] == 0x00 (version byte)
 *   3. Extract ephemeralPubKeyBytes [1..65] (65 bytes, uncompressed EC point)
 *   4. Extract iv [66..81] (16 bytes)
 *   5. Extract authTag [82..97] (16 bytes)
 *   6. Extract ciphertext [98..N]
 *   7. Reconstruct ECPublicKey from ephemeralPubKeyBytes (X = bytes[1..32], Y = bytes[33..64])
 *   8. ECDH with recipient private key -> sharedSecret
 *   9. KDF: SHA-256(sharedSecret) -> aesKey
 *  10. AES/GCM/NoPadding decrypt with iv, tag size 128 bits
 *      (Java GCM expects ciphertext + authTag concatenated for doFinal)
 *  11. Return new String(plainBytes, "UTF-8")
 *
 * The minimum total output length is 99 bytes (1 + 65 + 16 + 16 + 1).
 *
 * Uses standard Java JCE only: java.security.*, javax.crypto.*, java.util.Base64.
 */
public class EncryptionInteropTestUtil {

    // =========================================================================
    // Public API: Reference Decryptor
    // =========================================================================

    /**
     * Decrypts a base64-encoded Avo ECIES ciphertext using the recipient's EC private key.
     *
     * @param base64Encrypted  Base64-encoded ciphertext in Avo ECIES format
     * @param recipientPrivateKey  The EC private key corresponding to the public key used for encryption
     * @return The decrypted plaintext string
     * @throws Exception if decryption fails for any reason
     */
    public static String decrypt(String base64Encrypted, ECPrivateKey recipientPrivateKey) throws Exception {
        byte[] data = Base64.getDecoder().decode(base64Encrypted);

        // Minimum length: 1 (version) + 65 (ephemeral pub key) + 16 (IV) + 16 (auth tag) + 1 (min ciphertext)
        if (data.length < 99) {
            throw new IllegalArgumentException(
                "Encrypted data too short: expected at least 99 bytes, got " + data.length);
        }

        // Step 2: Assert version byte
        if (data[0] != 0x00) {
            throw new IllegalArgumentException(
                "Unsupported version byte: expected 0x00, got 0x" + String.format("%02x", data[0]));
        }

        // Step 3: Extract ephemeral public key bytes [1..65]
        byte[] ephemeralPubKeyBytes = new byte[65];
        System.arraycopy(data, 1, ephemeralPubKeyBytes, 0, 65);

        // Step 4: Extract IV [66..81]
        byte[] iv = new byte[16];
        System.arraycopy(data, 66, iv, 0, 16);

        // Step 5: Extract auth tag [82..97]
        byte[] authTag = new byte[16];
        System.arraycopy(data, 82, authTag, 0, 16);

        // Step 6: Extract ciphertext [98..N]
        int ciphertextLen = data.length - 98;
        byte[] ciphertext = new byte[ciphertextLen];
        System.arraycopy(data, 98, ciphertext, 0, ciphertextLen);

        // Step 7: Reconstruct ECPublicKey from ephemeral public key bytes
        // ephemeralPubKeyBytes[0] == 0x04 (uncompressed point marker)
        // X = bytes[1..32], Y = bytes[33..64]
        byte[] xBytes = new byte[32];
        byte[] yBytes = new byte[32];
        System.arraycopy(ephemeralPubKeyBytes, 1, xBytes, 0, 32);
        System.arraycopy(ephemeralPubKeyBytes, 33, yBytes, 0, 32);

        BigInteger x = new BigInteger(1, xBytes);
        BigInteger y = new BigInteger(1, yBytes);
        ECPoint point = new ECPoint(x, y);

        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec ecSpec = params.getParameterSpec(ECParameterSpec.class);

        ECPublicKeySpec pubKeySpec = new ECPublicKeySpec(point, ecSpec);
        ECPublicKey ephemeralPubKey = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(pubKeySpec);

        // Step 8: ECDH with recipient private key -> sharedSecret
        KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH");
        keyAgreement.init(recipientPrivateKey);
        keyAgreement.doPhase(ephemeralPubKey, true);
        byte[] sharedSecret = keyAgreement.generateSecret();

        // Step 9: KDF: SHA-256(sharedSecret) -> aesKey
        byte[] aesKeyBytes = MessageDigest.getInstance("SHA-256").digest(sharedSecret);
        SecretKeySpec aesKey = new SecretKeySpec(aesKeyBytes, "AES");

        // Step 10: AES/GCM/NoPadding decrypt
        // Java GCM expects ciphertext + authTag concatenated for doFinal
        byte[] ciphertextWithTag = new byte[ciphertextLen + 16];
        System.arraycopy(ciphertext, 0, ciphertextWithTag, 0, ciphertextLen);
        System.arraycopy(authTag, 0, ciphertextWithTag, ciphertextLen, 16);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        byte[] plainBytes = cipher.doFinal(ciphertextWithTag);

        // Step 11: Return plaintext as UTF-8 string
        return new String(plainBytes, "UTF-8");
    }

    // =========================================================================
    // Test-only encrypt (mirrors Android AvoEncryption.encrypt format exactly)
    // =========================================================================

    /**
     * Encrypts a plaintext string using ECIES with the given EC public key.
     * This is a test-only method that produces ciphertext in the same format
     * as the Android AvoEncryption.encrypt() method.
     *
     * Output format:
     *   [Version 0x00 (1B)] [EphemeralPubKey (65B)] [IV (16B)] [AuthTag (16B)] [Ciphertext]
     *
     * @param plaintext  The plaintext to encrypt
     * @param recipientPublicKey  The recipient's EC public key
     * @return Base64-encoded ciphertext
     * @throws Exception if encryption fails
     */
    static String encrypt(String plaintext, ECPublicKey recipientPublicKey) throws Exception {
        // Generate ephemeral P-256 key pair
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair ephemeralKeyPair = keyGen.generateKeyPair();

        // ECDH: compute shared secret
        KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH");
        keyAgreement.init(ephemeralKeyPair.getPrivate());
        keyAgreement.doPhase(recipientPublicKey, true);
        byte[] sharedSecret = keyAgreement.generateSecret();

        // KDF: SHA-256 of shared secret
        byte[] aesKeyBytes = MessageDigest.getInstance("SHA-256").digest(sharedSecret);

        // AES-256-GCM encrypt
        SecretKeySpec aesKey = new SecretKeySpec(aesKeyBytes, "AES");
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        byte[] ciphertextWithTag = cipher.doFinal(plaintext.getBytes("UTF-8"));

        // Java GCM appends auth tag to ciphertext; split last 16 bytes
        int ciphertextLen = ciphertextWithTag.length - 16;
        byte[] ciphertextOnly = new byte[ciphertextLen];
        byte[] authTag = new byte[16];
        System.arraycopy(ciphertextWithTag, 0, ciphertextOnly, 0, ciphertextLen);
        System.arraycopy(ciphertextWithTag, ciphertextLen, authTag, 0, 16);

        // Serialize ephemeral public key as uncompressed: 0x04 + X(32) + Y(32)
        ECPublicKey ephemeralPub = (ECPublicKey) ephemeralKeyPair.getPublic();
        byte[] ephemeralPubBytes = encodeUncompressedPoint(ephemeralPub);

        // Assemble: [Version 0x00 (1B)] + [EphemeralPubKey (65B)] + [IV (16B)] + [AuthTag (16B)] + [Ciphertext]
        byte[] output = new byte[1 + 65 + 16 + 16 + ciphertextLen];
        output[0] = 0x00; // version
        System.arraycopy(ephemeralPubBytes, 0, output, 1, 65);
        System.arraycopy(iv, 0, output, 66, 16);
        System.arraycopy(authTag, 0, output, 82, 16);
        System.arraycopy(ciphertextOnly, 0, output, 98, ciphertextLen);

        return Base64.getEncoder().encodeToString(output);
    }

    // =========================================================================
    // Helper: Public key hex encoding (for interop with SDKs that accept hex)
    // =========================================================================

    /**
     * Encodes an EC public key as an uncompressed hex string (04 + X + Y).
     *
     * @param publicKey  The EC public key
     * @return Hex string representation of the uncompressed public key
     */
    static String publicKeyToHex(ECPublicKey publicKey) {
        byte[] encoded = encodeUncompressedPoint(publicKey);
        StringBuilder hex = new StringBuilder();
        for (byte b : encoded) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    // =========================================================================
    // Helper: Key pair generation
    // =========================================================================

    /**
     * Generates a new P-256 EC key pair for testing.
     *
     * @return A new EC key pair
     * @throws Exception if key generation fails
     */
    static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(new ECGenParameterSpec("secp256r1"));
        return keyGen.generateKeyPair();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static byte[] encodeUncompressedPoint(ECPublicKey publicKey) {
        ECPoint w = publicKey.getW();
        byte[] xBytes = toUnsigned32Bytes(w.getAffineX());
        byte[] yBytes = toUnsigned32Bytes(w.getAffineY());

        byte[] result = new byte[65];
        result[0] = 0x04;
        System.arraycopy(xBytes, 0, result, 1, 32);
        System.arraycopy(yBytes, 0, result, 33, 32);
        return result;
    }

    private static byte[] toUnsigned32Bytes(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length == 32) {
            return bytes;
        } else if (bytes.length > 32) {
            // Strip leading sign byte
            byte[] trimmed = new byte[32];
            System.arraycopy(bytes, bytes.length - 32, trimmed, 0, 32);
            return trimmed;
        } else {
            // Pad with leading zeros
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 32 - bytes.length, bytes.length);
            return padded;
        }
    }

    // =========================================================================
    // Self-tests: Round-trip encryption/decryption
    // =========================================================================

    @Test
    public void selfTestRoundTripHelloWorld() throws Exception {
        KeyPair keyPair = generateKeyPair();
        ECPublicKey publicKey = (ECPublicKey) keyPair.getPublic();
        ECPrivateKey privateKey = (ECPrivateKey) keyPair.getPrivate();

        String plaintext = "\"hello world\"";
        String encrypted = encrypt(plaintext, publicKey);

        assertNotNull("Encryption should produce non-null result", encrypted);

        String decrypted = decrypt(encrypted, privateKey);
        assertEquals("Round-trip decrypt should return original plaintext", plaintext, decrypted);
    }

    @Test
    public void selfTestRoundTripVariousPayloads() throws Exception {
        KeyPair keyPair = generateKeyPair();
        ECPublicKey publicKey = (ECPublicKey) keyPair.getPublic();
        ECPrivateKey privateKey = (ECPrivateKey) keyPair.getPrivate();

        String[] payloads = {
            "\"hello world\"",
            "42",
            "3.14",
            "true",
            "\"test string value\"",
            "{\"key\":\"value\",\"number\":123}",
            "",  // empty string
            "\"unicode: \u00e9\u00e8\u00ea \u00fc\u00f6\u00e4\"",
        };

        for (String plaintext : payloads) {
            String encrypted = encrypt(plaintext, publicKey);
            assertNotNull("Encryption should succeed for: " + plaintext, encrypted);
            String decrypted = decrypt(encrypted, privateKey);
            assertEquals("Round-trip should preserve: " + plaintext, plaintext, decrypted);
        }
    }

    @Test
    public void selfTestOutputFormat() throws Exception {
        KeyPair keyPair = generateKeyPair();
        ECPublicKey publicKey = (ECPublicKey) keyPair.getPublic();

        String encrypted = encrypt("test", publicKey);
        assertNotNull(encrypted);

        byte[] decoded = Base64.getDecoder().decode(encrypted);

        // Minimum: 1 (version) + 65 (pubkey) + 16 (IV) + 16 (authTag) + at least 1 byte ciphertext
        assertTrue("Output should be at least 99 bytes, got " + decoded.length, decoded.length >= 99);

        // Version byte
        assertEquals("Version byte should be 0x00", 0x00, decoded[0]);

        // Ephemeral public key starts with 0x04 (uncompressed)
        assertEquals("Ephemeral key should start with 0x04", 0x04, decoded[1]);
    }

    @Test
    public void selfTestDifferentEncryptionsProduceDifferentOutput() throws Exception {
        KeyPair keyPair = generateKeyPair();
        ECPublicKey publicKey = (ECPublicKey) keyPair.getPublic();
        ECPrivateKey privateKey = (ECPrivateKey) keyPair.getPrivate();

        String plaintext = "same plaintext";
        String encrypted1 = encrypt(plaintext, publicKey);
        String encrypted2 = encrypt(plaintext, publicKey);

        assertNotNull(encrypted1);
        assertNotNull(encrypted2);
        assertNotEquals("Different encryptions should produce different output", encrypted1, encrypted2);

        // Both should decrypt to the same plaintext
        assertEquals(plaintext, decrypt(encrypted1, privateKey));
        assertEquals(plaintext, decrypt(encrypted2, privateKey));
    }

    @Test
    public void selfTestDecryptRejectsInvalidVersionByte() throws Exception {
        KeyPair keyPair = generateKeyPair();
        ECPublicKey publicKey = (ECPublicKey) keyPair.getPublic();

        String encrypted = encrypt("test", publicKey);
        byte[] data = Base64.getDecoder().decode(encrypted);

        // Corrupt version byte
        data[0] = 0x01;
        String corrupted = Base64.getEncoder().encodeToString(data);

        try {
            decrypt(corrupted, (ECPrivateKey) keyPair.getPrivate());
            fail("Should have thrown for invalid version byte");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Unsupported version byte"));
        }
    }

    @Test
    public void selfTestDecryptRejectsTooShortData() throws Exception {
        // Create data that is shorter than minimum 99 bytes
        byte[] tooShort = new byte[50];
        String encoded = Base64.getEncoder().encodeToString(tooShort);

        KeyPair keyPair = generateKeyPair();

        try {
            decrypt(encoded, (ECPrivateKey) keyPair.getPrivate());
            fail("Should have thrown for too-short data");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("too short"));
        }
    }
}
