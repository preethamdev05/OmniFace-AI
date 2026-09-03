package com.omniface.ai.sync

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import javax.crypto.AEADBadTagException

/**
 * Verification of Zero-Knowledge PBKDF2-HMAC-SHA256 + AES-256-GCM End-to-End Encryption.
 *
 * Confirms that:
 * 1. Encryption and decryption are strictly deterministic and lossless.
 * 2. An incorrect PIN strictly fails with AEAD authentication failure.
 * 3. Ciphertext byte tampering is instantly detected and rejected.
 * 4. Header metadata verification rejects unauthorized file payloads.
 */
class UserDriveBackupCryptoTest {

    @Test
    fun testEncryptionAndDecryptionRoundTrip() {
        val originalSecret = "OmniFace-Biometric-Payload: [Alice, Bob, Carol], Vectors: 512-D, Block: #42"
        val plainBytes = originalSecret.toByteArray(StandardCharsets.UTF_8)
        val userPin = "948210"

        val encrypted = UserDriveBackupManager.encryptBytesWithPin(plainBytes, userPin)

        assertNotNull(encrypted)
        assertTrue("Encrypted file must be larger than plaintext due to headers, salt, and tag", encrypted.size > plainBytes.size)

        val decryptedBytes = UserDriveBackupManager.decryptBytesWithPin(encrypted, userPin)
        val decryptedString = String(decryptedBytes, StandardCharsets.UTF_8)

        assertEquals("Decrypted content must match original plaintext identically", originalSecret, decryptedString)
    }

    @Test
    fun testWrongPinRejection() {
        val originalSecret = "Top-Secret-Biometric-Embeddings"
        val plainBytes = originalSecret.toByteArray(StandardCharsets.UTF_8)
        val correctPin = "123456"
        val wrongPin = "654321"

        val encrypted = UserDriveBackupManager.encryptBytesWithPin(plainBytes, correctPin)

        try {
            UserDriveBackupManager.decryptBytesWithPin(encrypted, wrongPin)
            fail("Decryption with wrong PIN must throw an exception!")
        } catch (e: Exception) {
            // Expected AEADBadTagException or BadPaddingException
            assertTrue(
                "Exception must be cryptographic auth failure: ${e.javaClass.simpleName}",
                e is AEADBadTagException || e.cause is AEADBadTagException || e is javax.crypto.BadPaddingException
            )
        }
    }

    @Test
    fun testTamperedCiphertextRejection() {
        val plainBytes = "IntegrityProtectedData".toByteArray(StandardCharsets.UTF_8)
        val pin = "778899"

        val encrypted = UserDriveBackupManager.encryptBytesWithPin(plainBytes, pin)

        // Tamper with one byte in the ciphertext payload (after header, salt, iv)
        val tampered = encrypted.clone()
        val targetIdx = tampered.size - 5
        tampered[targetIdx] = (tampered[targetIdx].toInt() xor 0xFF).toByte()

        try {
            UserDriveBackupManager.decryptBytesWithPin(tampered, pin)
            fail("Tampered ciphertext must fail GCM authentication tag verification!")
        } catch (e: Exception) {
            assertTrue(
                "Must reject tampered byte via AEAD auth tag",
                e is AEADBadTagException || e.cause is AEADBadTagException || e is javax.crypto.BadPaddingException
            )
        }
    }

    @Test
    fun testInvalidHeaderRejection() {
        val invalidBytes = ByteArray(64) { 0x00 }
        try {
            UserDriveBackupManager.decryptBytesWithPin(invalidBytes, "123456")
            fail("Missing magic header must be rejected!")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("magic header") == true || e.message?.contains("too small") == true)
        }
    }
}
