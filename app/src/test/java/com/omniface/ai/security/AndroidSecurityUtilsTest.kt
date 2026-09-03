package com.omniface.ai.security

import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

class AndroidSecurityUtilsTest {

    @Before
    fun setUp() {
        // Reset key cache before test
        AndroidSecurityUtils.invalidateKeyCache()
    }

    @Test
    fun testAes256GcmEncryptionAndDecryptionRoundtrip() {
        val original = "STUDENT_2026_BIOMETRIC_VECTOR_DATA_0.123,0.456,0.789"
        val encrypted = AndroidSecurityUtils.encrypt(original)

        assertNotNull(encrypted)
        assertTrue("Ciphertext must be non-empty", encrypted.isNotEmpty())
        assertNotEquals("Ciphertext must not match plaintext", original, encrypted)

        val decrypted = AndroidSecurityUtils.decrypt(encrypted)
        assertEquals("Decrypted text must match original plaintext", original, decrypted)
    }

    @Test
    fun testAes256GcmByteArrayEncryptionAndDecryptionRoundtrip() {
        val originalBytes = "STUDENT_2026_BYTE_ARRAY_BIOMETRIC_RAW".toByteArray(Charsets.UTF_8)
        val encryptedBytes = AndroidSecurityUtils.encrypt(originalBytes)

        assertNotNull(encryptedBytes)
        assertTrue("Encrypted byte array must be >= 28 bytes", encryptedBytes.size >= 28)
        assertFalse("Ciphertext bytes must not equal plaintext bytes", originalBytes.contentEquals(encryptedBytes))

        val decryptedBytes = AndroidSecurityUtils.decrypt(encryptedBytes)
        assertArrayEquals("Decrypted bytes must match original plaintext bytes", originalBytes, decryptedBytes)

        // Under 28 bytes check
        val shortBytes = ByteArray(20)
        val shortDecrypted = AndroidSecurityUtils.decrypt(shortBytes)
        assertEquals(0, shortDecrypted.size)
    }

    @Test
    fun testCiphertextLengthValidationRejectsUnder28Bytes() {
        // Ciphertext under 28 bytes (IV 12 bytes + GCM Tag 16 bytes = 28 bytes)
        val emptyResult = AndroidSecurityUtils.decrypt("")
        assertEquals("Empty string should return empty", "", emptyResult)

        // 10 bytes base64
        val shortBase64 = java.util.Base64.getEncoder().encodeToString(ByteArray(10))
        val shortResult = AndroidSecurityUtils.decrypt(shortBase64)
        assertEquals("Payload < 28 bytes must return empty string without throwing exception", "", shortResult)

        // 27 bytes base64
        val payload27 = java.util.Base64.getEncoder().encodeToString(ByteArray(27))
        val result27 = AndroidSecurityUtils.decrypt(payload27)
        assertEquals("Payload of 27 bytes must return empty string", "", result27)

        // Corrupted 28 bytes payload (fails authentication tag)
        val payload28Corrupted = java.util.Base64.getEncoder().encodeToString(ByteArray(28))
        val result28 = AndroidSecurityUtils.decrypt(payload28Corrupted)
        assertEquals("Corrupted ciphertext must safely return empty string without crashing", "", result28)
    }

    @Test
    fun testThreadSafeSecretKeyCachingUnderConcurrency() {
        val threadCount = 16
        val executor = Executors.newFixedThreadPool(threadCount)
        val tasks = mutableListOf<Callable<String>>()

        for (i in 0 until 50) {
            tasks.add(Callable {
                val text = "PAYLOAD_THREAD_$i"
                val enc = AndroidSecurityUtils.encrypt(text)
                AndroidSecurityUtils.decrypt(enc)
            })
        }

        val futures: List<Future<String>> = executor.invokeAll(tasks)
        for (i in 0 until 50) {
            val result = futures[i].get()
            assertEquals("Concurrent decrypt must match original", "PAYLOAD_THREAD_$i", result)
        }
        executor.shutdown()
    }

    @Test
    fun testSha256Determinism() {
        val input = "OMNIFACE_TEST_INPUT_2026"
        val hash1 = AndroidSecurityUtils.computeSha256(input)
        val hash2 = AndroidSecurityUtils.computeSha256(input)

        assertEquals("SHA-256 must be deterministic", hash1, hash2)
        assertEquals("SHA-256 hex string must be 64 characters", 64, hash1.length)
        assertTrue("SHA-256 must contain only hex characters", hash1.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun testMerkleRootComputation() {
        val emptyRoot = AndroidSecurityUtils.computeMerkleRoot(emptyList())
        assertEquals(64, emptyRoot.length)

        val singleLeaf = AndroidSecurityUtils.computeSha256("LEAF_1")
        val singleRoot = AndroidSecurityUtils.computeMerkleRoot(listOf(singleLeaf))
        assertEquals("Single leaf Merkle root should equal the leaf hash", singleLeaf, singleRoot)

        val leaves = listOf(
            AndroidSecurityUtils.computeSha256("LEAF_1"),
            AndroidSecurityUtils.computeSha256("LEAF_2"),
            AndroidSecurityUtils.computeSha256("LEAF_3"),
            AndroidSecurityUtils.computeSha256("LEAF_4")
        )
        val root = AndroidSecurityUtils.computeMerkleRoot(leaves)
        assertEquals(64, root.length)
    }

    @Test
    fun testAegisBlockchainHashChainingAndIntegrity() {
        val genesis = AndroidSecurityUtils.AEGIS_GENESIS_HASH
        assertEquals("Genesis hash must be 64 zeroes", 64, genesis.length)
        assertTrue(genesis.all { it == '0' })

        // Block 1
        val hash1 = AndroidSecurityUtils.computeAegisBlockHash(genesis, "ROLL_101", 1000L, 98.5f)
        val record1 = AttendanceRecordEntity(
            recordId = "REC-1",
            studentRoll = "ROLL_101",
            studentName = "Alice",
            sessionDate = "2026-08-25",
            timestamp = 1000L,
            confidencePct = 98.5f,
            securityTier = "HIGH",
            sha256Hash = hash1
        )

        // Block 2
        val hash2 = AndroidSecurityUtils.computeAegisBlockHash(hash1, "ROLL_102", 2000L, 95.0f)
        val record2 = AttendanceRecordEntity(
            recordId = "REC-2",
            studentRoll = "ROLL_102",
            studentName = "Bob",
            sessionDate = "2026-08-25",
            timestamp = 2000L,
            confidencePct = 95.0f,
            securityTier = "HIGH",
            sha256Hash = hash2
        )

        // Block 3
        val hash3 = AndroidSecurityUtils.computeAegisBlockHash(hash2, "ROLL_103", 3000L, 99.1f)
        val record3 = AttendanceRecordEntity(
            recordId = "REC-3",
            studentRoll = "ROLL_103",
            studentName = "Charlie",
            sessionDate = "2026-08-25",
            timestamp = 3000L,
            confidencePct = 99.1f,
            securityTier = "STRICT",
            sha256Hash = hash3
        )

        val chain = listOf(record1, record2, record3)
        assertTrue("Valid sequential chain must pass integrity check", AndroidSecurityUtils.verifyChainIntegrity(chain))

        // Tamper test 1: Modify confidence in block 2
        val tamperedRecord2 = record2.copy(confidencePct = 80.0f)
        val tamperedChain1 = listOf(record1, tamperedRecord2, record3)
        assertFalse("Tampered confidence must fail chain integrity", AndroidSecurityUtils.verifyChainIntegrity(tamperedChain1))

        // Tamper test 2: Modify roll number in block 1
        val tamperedRecord1 = record1.copy(studentRoll = "ROLL_HACKED")
        val tamperedChain2 = listOf(tamperedRecord1, record2, record3)
        assertFalse("Tampered roll number must fail chain integrity", AndroidSecurityUtils.verifyChainIntegrity(tamperedChain2))

        // Tamper test 3: Modify intermediate hash
        val tamperedRecord2Hash = record2.copy(sha256Hash = AndroidSecurityUtils.computeSha256("FAKE_HASH"))
        val tamperedChain3 = listOf(record1, tamperedRecord2Hash, record3)
        assertFalse("Tampered hash must fail chain integrity", AndroidSecurityUtils.verifyChainIntegrity(tamperedChain3))
    }

    @Test
    fun testHmacSha256SigningAndVerification() {
        val secret = "test_hmac_secret_key_omniface_2026_abc123"
        val message = "DEVICE-01:1756123456789:{\"records\":[{\"id\":\"1\"}]}"

        val signature = AndroidSecurityUtils.computeHmacSha256(secret, message)
        assertEquals(64, signature.length)

        val isValid = AndroidSecurityUtils.verifyHmacSha256(secret, message, signature)
        assertTrue("Valid HMAC signature must verify successfully", isValid)

        val isTamperedDataValid = AndroidSecurityUtils.verifyHmacSha256(secret, message + "_tampered", signature)
        assertFalse("Tampered message must fail HMAC verification", isTamperedDataValid)

        val isWrongSecretValid = AndroidSecurityUtils.verifyHmacSha256("wrong_secret", message, signature)
        assertFalse("Wrong secret must fail HMAC verification", isWrongSecretValid)
    }

    @Test
    fun testCreateEncryptedBackupPayloadAndDecryptRoundtrip() {
        // Genuine SQLite database test byte sequence simulating a SQLite header and page data
        val sqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val mockDbBytes = ByteArray(4096)
        System.arraycopy(sqliteHeader, 0, mockDbBytes, 0, sqliteHeader.size)
        // Fill remaining with deterministic data
        for (i in sqliteHeader.size until mockDbBytes.size) {
            mockDbBytes[i] = ((i * 31) and 0xFF).toByte()
        }

        val encryptedEnvelope = AndroidSecurityUtils.createEncryptedBackupPayload(mockDbBytes)
        assertNotNull(encryptedEnvelope)
        assertTrue(
            "Envelope must contain 48-byte header + >=28-byte ciphertext",
            encryptedEnvelope.size >= AndroidSecurityUtils.BACKUP_HEADER_SIZE + AndroidSecurityUtils.MIN_CIPHERTEXT_LENGTH
        )

        // Verify magic bytes
        val magic = encryptedEnvelope.copyOfRange(0, 4)
        assertArrayEquals(AndroidSecurityUtils.BACKUP_MAGIC_BYTES, magic)

        // Decrypt and verify payload
        val decryptedDb = AndroidSecurityUtils.decryptBackupPayload(encryptedEnvelope)
        assertArrayEquals("Decrypted database bytes must match original byte-for-byte", mockDbBytes, decryptedDb)
    }

    @Test
    fun testEncryptedBackupRejectsTamperedMagicHeader() {
        val originalBytes = "SAMPLE_DATABASE_PAGES_OMNIFACE_AI".toByteArray(Charsets.UTF_8)
        val encryptedEnvelope = AndroidSecurityUtils.createEncryptedBackupPayload(originalBytes)

        // Tamper magic header
        encryptedEnvelope[0] = 'X'.code.toByte()
        encryptedEnvelope[1] = 'Y'.code.toByte()

        try {
            AndroidSecurityUtils.decryptBackupPayload(encryptedEnvelope)
            fail("Must throw GeneralSecurityException on tampered magic header")
        } catch (e: java.security.GeneralSecurityException) {
            assertTrue(e.message?.contains("magic", ignoreCase = true) == true)
        }
    }

    @Test
    fun testEncryptedBackupRejectsUnsupportedVersion() {
        val originalBytes = "SAMPLE_DATABASE_PAGES_OMNIFACE_AI".toByteArray(Charsets.UTF_8)
        val encryptedEnvelope = AndroidSecurityUtils.createEncryptedBackupPayload(originalBytes)

        // Tamper version byte (index 4)
        encryptedEnvelope[4] = 99.toByte()

        try {
            AndroidSecurityUtils.decryptBackupPayload(encryptedEnvelope)
            fail("Must throw GeneralSecurityException on unsupported version")
        } catch (e: java.security.GeneralSecurityException) {
            assertTrue(e.message?.contains("version", ignoreCase = true) == true)
        }
    }

    @Test
    fun testEncryptedBackupRejectsTamperedCiphertextPayload() {
        val originalBytes = "SAMPLE_DATABASE_PAGES_OMNIFACE_AI".toByteArray(Charsets.UTF_8)
        val encryptedEnvelope = AndroidSecurityUtils.createEncryptedBackupPayload(originalBytes)

        // Tamper ciphertext payload byte (past 48-byte header)
        val lastIdx = encryptedEnvelope.size - 1
        encryptedEnvelope[lastIdx] = (encryptedEnvelope[lastIdx].toInt() xor 0xFF).toByte()

        try {
            AndroidSecurityUtils.decryptBackupPayload(encryptedEnvelope)
            fail("Must throw GeneralSecurityException on tampered ciphertext/GCM auth tag")
        } catch (e: java.security.GeneralSecurityException) {
            // Expected GCM auth tag failure
            assertNotNull(e.message)
        }
    }

    @Test
    fun testEncryptedBackupRejectsTamperedSha256Checksum() {
        val originalBytes = "SAMPLE_DATABASE_PAGES_OMNIFACE_AI".toByteArray(Charsets.UTF_8)
        val encryptedEnvelope = AndroidSecurityUtils.createEncryptedBackupPayload(originalBytes)

        // Tamper SHA-256 checksum field (bytes 16..47)
        encryptedEnvelope[20] = (encryptedEnvelope[20].toInt() xor 0xAA).toByte()

        try {
            AndroidSecurityUtils.decryptBackupPayload(encryptedEnvelope)
            fail("Must throw GeneralSecurityException on checksum mismatch")
        } catch (e: java.security.GeneralSecurityException) {
            assertTrue(e.message?.contains("checksum", ignoreCase = true) == true)
        }
    }

    @Test
    fun testRestoreEncryptedDatabaseBackupToTargetFile() {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "omniface_test_backups_${System.currentTimeMillis()}").apply { mkdirs() }
        val backupFile = java.io.File(tempDir, "test_backup.db.enc")
        val targetRestoredDb = java.io.File(tempDir, "restored_omniface.db")

        try {
            val originalContent = "SQLITE_SIMULATED_STUDENT_EMBEDDING_PAGES_V2".toByteArray(Charsets.UTF_8)
            val envelope = AndroidSecurityUtils.createEncryptedBackupPayload(originalContent)
            backupFile.writeBytes(envelope)

            val restored = AndroidSecurityUtils.restoreEncryptedDatabaseBackup(backupFile, targetRestoredDb)
            assertTrue("Restore must succeed", restored)
            assertTrue("Target DB file must exist", targetRestoredDb.exists())
            assertArrayEquals("Restored content must match original", originalContent, targetRestoredDb.readBytes())
        } finally {
            backupFile.delete()
            targetRestoredDb.delete()
            tempDir.delete()
        }
    }
}
