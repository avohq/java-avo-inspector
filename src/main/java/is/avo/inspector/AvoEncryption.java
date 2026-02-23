package is.avo.inspector;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
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

import org.jetbrains.annotations.Nullable;

/**
 * ECIES encryption implementation for Java JVM Inspector SDK.
 *
 * Algorithm: ECIES with P-256 + ECDH + SHA-256 KDF + AES-256-GCM
 *
 * Wire format:
 *   [0x00 version byte (1B)] [uncompressed ephemeral pubkey (65B)] [IV (16B)] [auth tag (16B)] [ciphertext (variable)]
 *
 * Base64 encodes the entire wire format output.
 *
 * Uses java.util.Base64 (NOT android.util.Base64).
 */
class AvoEncryption {

    /**
     * Determines whether encryption should be applied.
     *
     * Returns true only when:
     * - env is NOT "prod"
     * - publicEncryptionKey is non-null and non-empty
     *
     * @param envName the environment name ("prod", "dev", "staging")
     * @param publicEncryptionKey the hex-encoded public encryption key, or null
     * @return true if encryption should be applied
     */
    static boolean shouldEncrypt(String envName, @Nullable String publicEncryptionKey) {
        if ("prod".equals(envName)) {
            return false;
        }
        return publicEncryptionKey != null && !publicEncryptionKey.isEmpty();
    }

    /**
     * Encrypts a plaintext string using ECIES with the given hex-encoded EC public key.
     *
     * On failure, logs a warning and returns null (fail-safe: omit the value rather than
     * sending it unencrypted).
     *
     * @param plaintext the plaintext to encrypt
     * @param publicKeyHex hex-encoded uncompressed EC public key (04 + X + Y, 130 hex chars)
     * @return Base64-encoded ciphertext in Avo ECIES wire format, or null on failure
     */
    @Nullable
    static String encrypt(String plaintext, String publicKeyHex) {
        try {
            if (publicKeyHex == null || publicKeyHex.isEmpty()) {
                return null;
            }

            // Decode hex public key to bytes
            byte[] pubKeyBytes = hexToBytes(publicKeyHex);
            if (pubKeyBytes.length != 65 || pubKeyBytes[0] != 0x04) {
                if (AvoInspector.isLogging()) {
                    System.err.println("AvoInspector: Invalid public key format for encryption");
                }
                return null;
            }

            // Reconstruct ECPublicKey from uncompressed point bytes
            byte[] xBytes = new byte[32];
            byte[] yBytes = new byte[32];
            System.arraycopy(pubKeyBytes, 1, xBytes, 0, 32);
            System.arraycopy(pubKeyBytes, 33, yBytes, 0, 32);

            BigInteger x = new BigInteger(1, xBytes);
            BigInteger y = new BigInteger(1, yBytes);
            ECPoint point = new ECPoint(x, y);

            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec ecSpec = params.getParameterSpec(ECParameterSpec.class);

            ECPublicKeySpec recipientPubKeySpec = new ECPublicKeySpec(point, ecSpec);
            ECPublicKey recipientPublicKey = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(recipientPubKeySpec);

            // Generate ephemeral P-256 key pair
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
            keyGen.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair ephemeralKeyPair = keyGen.generateKeyPair();

            // ECDH: compute shared secret
            KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH");
            keyAgreement.init(ephemeralKeyPair.getPrivate());
            keyAgreement.doPhase(recipientPublicKey, true);
            byte[] sharedSecret = keyAgreement.generateSecret();

            // KDF: SHA-256 of shared secret -> AES key
            byte[] aesKeyBytes = MessageDigest.getInstance("SHA-256").digest(sharedSecret);
            SecretKeySpec aesKey = new SecretKeySpec(aesKeyBytes, "AES");

            // AES-256-GCM encrypt with 16-byte IV
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
            byte[] ciphertextWithTag = cipher.doFinal(plaintext.getBytes("UTF-8"));

            // Java GCM appends auth tag to ciphertext in doFinal — split last 16 bytes as auth tag
            int ciphertextLen = ciphertextWithTag.length - 16;
            byte[] ciphertextOnly = new byte[ciphertextLen];
            byte[] authTag = new byte[16];
            System.arraycopy(ciphertextWithTag, 0, ciphertextOnly, 0, ciphertextLen);
            System.arraycopy(ciphertextWithTag, ciphertextLen, authTag, 0, 16);

            // Serialize ephemeral public key as uncompressed: 0x04 + X(32) + Y(32)
            ECPublicKey ephemeralPub = (ECPublicKey) ephemeralKeyPair.getPublic();
            byte[] ephemeralPubBytes = encodeUncompressedPoint(ephemeralPub);

            // Assemble wire format:
            // [0x00 version (1B)] + [ephemeral pubkey (65B)] + [IV (16B)] + [auth tag (16B)] + [ciphertext]
            byte[] output = new byte[1 + 65 + 16 + 16 + ciphertextLen];
            output[0] = 0x00; // version byte
            System.arraycopy(ephemeralPubBytes, 0, output, 1, 65);
            System.arraycopy(iv, 0, output, 66, 16);
            System.arraycopy(authTag, 0, output, 82, 16);
            System.arraycopy(ciphertextOnly, 0, output, 98, ciphertextLen);

            return Base64.getEncoder().encodeToString(output);

        } catch (Exception e) {
            if (AvoInspector.isLogging()) {
                System.err.println("AvoInspector: Encryption failed, omitting property value: " + e.getMessage());
            }
            return null;
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Encodes an EC public key as an uncompressed point: 0x04 + X(32) + Y(32).
     */
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

    /**
     * Converts a BigInteger to an unsigned 32-byte array (zero-padded or truncated).
     */
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

    /**
     * Converts a hex string to a byte array.
     */
    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
