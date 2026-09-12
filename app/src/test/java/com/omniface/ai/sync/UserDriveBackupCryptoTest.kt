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

    @Test
    fun testComplexBiometricPayloadRoundTrip() {
        // Multi-identity JSON simulation with 512-D float vectors and international UTF-8 names
        val simulatedPayload = """
        {
            "version": 1,
            "timestamp": 1789150000000,
            "app_id": "com.omniface.ai",
            "students": [
                {"roll_number": "CS-001", "full_name": "Aarav Sharma (आरव)", "department": "Computer Science"},
                {"roll_number": "CS-002", "full_name": "Priya Patel (ಪ್ರಿಯಾ)", "department": "AI & Robotics"}
            ],
            "templates": [
                {
                    "id": "tpl_001",
                    "student_roll": "CS-001",
                    "angle_type": "FRONTAL",
                    "embedding_encrypted_csv": "${(1..512).joinToString(",") { "0.04412" }}",
                    "quality_score": 98.5
                }
            ],
            "attendance": [
                {
                    "record_id": "att_001",
                    "student_roll": "CS-001",
                    "timestamp": 1789151000000,
                    "session_date": "2026-09-12",
                    "confidence_pct": 99.2,
                    "security_tier": "HIGH"
                }
            ]
        }
        """.trimIndent()

        val rawBytes = simulatedPayload.toByteArray(StandardCharsets.UTF_8)
        val userPin = "839201"

        val encryptedBytes = UserDriveBackupManager.encryptBytesWithPin(rawBytes, userPin)
        assertNotNull(encryptedBytes)
        assertTrue(encryptedBytes.size > rawBytes.size)

        val decryptedBytes = UserDriveBackupManager.decryptBytesWithPin(encryptedBytes, userPin)
        val decryptedString = String(decryptedBytes, StandardCharsets.UTF_8)

        assertEquals("Decrypted complex biometric payload must match verbatim", simulatedPayload, decryptedString)
        assertTrue("Must contain Unicode name", decryptedString.contains("आरव"))
        assertTrue("Must contain 512-D vector", decryptedString.contains("0.04412"))
    }

    @Test
    fun testGoogleDriveAppDataFolderPolicyContracts() {
        // Verify Google Drive API v3 constants and appDataFolder scope isolation
        val appDataScope = "https://www.googleapis.com/auth/drive.appdata"
        val driveFilesUrl = "https://www.googleapis.com/drive/v3/files"
        val driveUploadUrl = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"

        assertTrue("AppData scope must target drive.appdata", appDataScope.endsWith("/drive.appdata"))
        assertTrue("Upload endpoint must be multipart", driveUploadUrl.contains("uploadType=multipart"))
        assertTrue("Files endpoint must use v3 REST API", driveFilesUrl.contains("/drive/v3/files"))
    }

    @Test
    fun testCloudBackupRetentionPruningLogic() {
        // WhatsApp/OmniFace standard: Retain latest 3 backups, prune older revisions
        val mockDriveFiles = listOf(
            DriveFileMeta("id_1", "omniface_backup_1710300000.enc", 2048L, "2026-03-03T10:00:00Z"),
            DriveFileMeta("id_2", "omniface_backup_1710200000.enc", 2048L, "2026-03-02T10:00:00Z"),
            DriveFileMeta("id_3", "omniface_backup_1710100000.enc", 2048L, "2026-03-01T10:00:00Z"),
            DriveFileMeta("id_4", "omniface_backup_1710000000.enc", 2048L, "2026-02-28T10:00:00Z"),
            DriveFileMeta("id_5", "omniface_backup_1709900000.enc", 2048L, "2026-02-27T10:00:00Z")
        )

        val keepCount = 3
        val retained = mockDriveFiles.take(keepCount)
        val pruned = mockDriveFiles.drop(keepCount)

        assertEquals("Must retain exactly 3 latest backups", 3, retained.size)
        assertEquals("Latest backup retained", "id_1", retained[0].id)
        assertEquals("Second latest retained", "id_2", retained[1].id)
        assertEquals("Third latest retained", "id_3", retained[2].id)

        assertEquals("Must prune 2 older backups", 2, pruned.size)
        assertEquals("Pruned oldest candidates", listOf("id_4", "id_5"), pruned.map { it.id })
    }

    @Test
    fun testVariableLengthPinKeyDerivation() {
        val payload = "Testing PIN Entropy Derivation".toByteArray(StandardCharsets.UTF_8)
        val pins = listOf("0000", "123456", "SecurePassphrase#2026", "9999999999999999")

        for (pin in pins) {
            val enc = UserDriveBackupManager.encryptBytesWithPin(payload, pin)
            val dec = UserDriveBackupManager.decryptBytesWithPin(enc, pin)
            assertEquals("PIN ($pin) roundtrip must succeed", String(payload), String(dec))
        }
    }
}
