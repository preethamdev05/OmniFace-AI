package com.omniface.ai.sync

import com.omniface.ai.attendance.AegisLedgerHasher
import com.omniface.ai.attendance.AttendanceService
import com.omniface.ai.data.local.entity.AegisOutboxEntity
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.ml.FakeAegisOutboxDao
import com.omniface.ai.ml.FakeAttendanceDao
import com.omniface.ai.ml.pipeline.BiometricSynthesisDecision
import com.omniface.ai.ml.pipeline.PipelineGateState
import com.omniface.ai.ml.verification.domain.VerificationDecision
import com.omniface.ai.security.AndroidSecurityUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Phase 18: Resilient Sync & Offline Ledger Test Suite.
 *
 * Verifies:
 * 1. Transactional Outbox atomic commitment (AttendanceRecord + AegisOutboxEntity).
 * 2. In-batch deduplication (highest confidence detection wins).
 * 3. Idempotent daily check-in deduplication.
 * 4. Aegis SHA-256 blockchain hash chaining continuity under offline queueing.
 * 5. Cryptographic tamper detection across blockchain ledger blocks.
 * 6. Sync payload JSON structure and special character escaping.
 * 7. HMAC-SHA256 device-to-cloud request authentication.
 * 8. Sync server response validation (success vs fail-closed error responses).
 * 9. Offline batch synchronization and mark-as-synced idempotency.
 */
class ResilientSyncOfflineLedgerTest {

    private lateinit var fakeAttendanceDao: FakeAttendanceDao
    private lateinit var fakeAegisOutboxDao: FakeAegisOutboxDao
    private lateinit var attendanceService: AttendanceService

    @Before
    fun setUp() {
        fakeAttendanceDao = FakeAttendanceDao()
        fakeAegisOutboxDao = FakeAegisOutboxDao()
        attendanceService = AttendanceService(
            database = null,
            attendanceDao = fakeAttendanceDao,
            aegisOutboxDao = fakeAegisOutboxDao
        )
    }

    private fun makeSynthesisDecision(
        roll: String,
        name: String,
        confidencePct: Float,
        similarity: Float
    ): BiometricSynthesisDecision {
        return BiometricSynthesisDecision(
            gateState = PipelineGateState.PASS,
            isAttendanceAuthorized = true,
            matchedStudentRoll = roll,
            matchedStudentName = name,
            matchConfidence = confidencePct,
            matchSimilarity = similarity,
            decisionMargin = 0.15f,
            qualityScore = 90.0f,
            livenessScore = 98.0f,
            title = "VERIFIED",
            subtitle = name,
            technicalExplanation = "All gates passed"
        )
    }

    @Test
    fun testTransactionalOutboxAtomicCommit() = runBlocking {
        val verifiedList = listOf(
            VerificationDecision.Verified(
                identityId = "STU_001",
                displayName = "Arya Stark",
                role = "STUDENT",
                confidence = 0.98f,
                liveness = 0.99f,
                leafHash = ""
            ),
            VerificationDecision.Verified(
                identityId = "STU_002",
                displayName = "Jon Snow",
                role = "STUDENT",
                confidence = 0.95f,
                liveness = 0.97f,
                leafHash = ""
            )
        )

        val result = attendanceService.recordVerifiedBatch(verifiedList, securityTier = "HIGH", timestamp = 1756000000000L)
        assertEquals(2, result.newlyRecorded.size)
        assertEquals(0, result.skippedDuplicates.size)

        // Verify AttendanceDao records
        val allRecords = fakeAttendanceDao.getAllRecords()
        assertEquals(2, allRecords.size)

        // Verify OutboxDao records
        val pendingOutbox = fakeAegisOutboxDao.getPendingOutbox(10)
        assertEquals(2, pendingOutbox.size)

        for (record in allRecords) {
            val matchingOutbox = pendingOutbox.firstOrNull { it.recordId == record.recordId }
            assertNotNull("Each attendance record must have an atomic outbox counterpart", matchingOutbox)
            assertEquals(record.studentRoll, matchingOutbox!!.studentRoll)
            assertEquals(record.sha256Hash, matchingOutbox.leafHash)
            assertEquals(AegisOutboxEntity.STATUS_PENDING_MINT, matchingOutbox.status)
        }
    }

    @Test
    fun testInBatchDeduplicationHighestConfidenceWins() = runBlocking {
        val candidate1 = VerificationDecision.Verified(
            identityId = "STU_DUAL",
            displayName = "Dual Detect",
            role = "STUDENT",
            confidence = 0.78f,
            liveness = 0.90f,
            leafHash = ""
        )
        val candidate2 = VerificationDecision.Verified(
            identityId = "STU_DUAL",
            displayName = "Dual Detect",
            role = "STUDENT",
            confidence = 0.96f,
            liveness = 0.95f,
            leafHash = ""
        )

        val result = attendanceService.recordVerifiedBatch(
            listOf(candidate1, candidate2),
            securityTier = "HIGH",
            timestamp = 1756000100000L
        )

        assertEquals(1, result.newlyRecorded.size)
        assertEquals(1, result.skippedDuplicates.size)
        assertEquals("STU_DUAL", result.skippedDuplicates[0])

        val record = fakeAttendanceDao.getAllRecords().first()
        assertEquals("STU_DUAL", record.studentRoll)
        assertEquals(96.0f, record.confidencePct, 1e-3f)
    }

    @Test
    fun testIdempotentDailyCheckInSkipping() = runBlocking {
        val t0 = 1756000200000L
        val verified = VerificationDecision.Verified(
            identityId = "STU_DAILY",
            displayName = "Daily Student",
            role = "STUDENT",
            confidence = 0.94f,
            liveness = 0.96f,
            leafHash = ""
        )

        val res1 = attendanceService.recordVerifiedBatch(listOf(verified), timestamp = t0)
        assertEquals(1, res1.newlyRecorded.size)

        // Attempt second check-in 10 minutes later on same date
        val t1 = t0 + 600_000L
        val res2 = attendanceService.recordVerifiedBatch(listOf(verified), timestamp = t1)
        assertEquals(0, res2.newlyRecorded.size)
        assertEquals(1, res2.skippedDuplicates.size)
        assertEquals("STU_DAILY", res2.skippedDuplicates[0])

        assertEquals(1, fakeAttendanceDao.getAllRecords().size)
    }

    @Test
    fun testAegisHashChainingContinuityUnderOfflineQueueing() = runBlocking {
        val baseTimestamp = 1756000300000L
        for (i in 1..5) {
            val decision = makeSynthesisDecision(
                roll = "ROLL_CHAIN_$i",
                name = "Chain Student $i",
                confidencePct = 90.0f + i,
                similarity = 0.90f
            )
            attendanceService.recordSynthesisBatch(
                listOf(decision),
                timestamp = baseTimestamp + (i * 1000L)
            )
        }

        val records = fakeAttendanceDao.getAllRecords().sortedBy { it.timestamp }
        assertEquals(5, records.size)

        // Verify unbroken hash chain
        var expectedPrev = AegisLedgerHasher.GENESIS_HASH
        for (rec in records) {
            val expectedHash = AegisLedgerHasher.computeBlockHash(
                previousHash = expectedPrev,
                studentRoll = rec.studentRoll,
                timestamp = rec.timestamp,
                confidencePct = rec.confidencePct
            )
            assertEquals("Record hash must match expected chained block hash", expectedHash, rec.sha256Hash)
            expectedPrev = rec.sha256Hash
        }

        assertTrue("Chain integrity must pass on authentic offline ledger", AegisLedgerHasher.verifyChainIntegrity(records))
    }

    @Test
    fun testAegisHashChainingTamperDetection() = runBlocking {
        val baseTimestamp = 1756000400000L
        for (i in 1..3) {
            val decision = makeSynthesisDecision(
                roll = "ROLL_TAMPER_$i",
                name = "Student $i",
                confidencePct = 95.0f,
                similarity = 0.95f
            )
            attendanceService.recordSynthesisBatch(
                listOf(decision),
                timestamp = baseTimestamp + (i * 1000L)
            )
        }

        val records = fakeAttendanceDao.getAllRecords().sortedBy { it.timestamp }
        assertEquals(3, records.size)
        assertTrue(AegisLedgerHasher.verifyChainIntegrity(records))

        // 1. Tamper confidence
        val tamperedConfidence = records.mapIndexed { idx, rec ->
            if (idx == 1) rec.copy(confidencePct = 80.0f) else rec
        }
        assertFalse("Tampered confidence must break chain verification", AegisLedgerHasher.verifyChainIntegrity(tamperedConfidence))

        // 2. Tamper roll number
        val tamperedRoll = records.mapIndexed { idx, rec ->
            if (idx == 0) rec.copy(studentRoll = "HACKED_ROLL") else rec
        }
        assertFalse("Tampered student roll must break chain verification", AegisLedgerHasher.verifyChainIntegrity(tamperedRoll))

        // 3. Tamper timestamp
        val tamperedTimestamp = records.mapIndexed { idx, rec ->
            if (idx == 2) rec.copy(timestamp = rec.timestamp + 5000L) else rec
        }
        assertFalse("Tampered timestamp must break chain verification", AegisLedgerHasher.verifyChainIntegrity(tamperedTimestamp))

        // 4. Out of order records
        val swappedRecords = listOf(records[1], records[0], records[2])
        assertFalse("Swapped record sequence must break chain verification", AegisLedgerHasher.verifyChainIntegrity(swappedRecords))
    }

    @Test
    fun testSyncPayloadJsonStructureWithSpecialCharacters() {
        val record = AttendanceRecordEntity(
            recordId = "rec_special_001",
            studentRoll = "CSE_2026/01_A",
            studentName = "Renee O'Connor Lab Assistant",
            timestamp = 1756000500000L,
            sessionDate = "2026-08-25",
            confidencePct = 99.1f,
            securityTier = "STRICT",
            sha256Hash = "hash_special_123",
            isSynced = false
        )

        val payload = AttendanceSyncWorker.buildPayloadString(
            deviceId = "TERMINAL-01",
            records = listOf(record),
            orgId = "ORG-ACME"
        )

        assertNotNull(payload)
        assertTrue(payload.isNotBlank())

        // Verify payload JSON keys and values are properly formatted
        assertTrue("Payload must include device_id", payload.contains("\"device_id\":\"TERMINAL-01\""))
        assertTrue("Payload must include orgId", payload.contains("\"orgId\":\"ORG-ACME\""))
        assertTrue("Payload must include record_id", payload.contains("\"record_id\":\"rec_special_001\""))
        assertTrue("Payload must include student_roll", payload.contains("\"student_roll\":\"CSE_2026/01_A\""))
        assertTrue("Payload must include student_name", payload.contains("\"student_name\":\"Renee O'Connor Lab Assistant\""))
        assertTrue("Payload must include confidence_pct", payload.contains("\"confidence_pct\":99.1"))
        assertTrue("Payload must include sha256_hash", payload.contains("\"sha256_hash\":\"hash_special_123\""))
        assertTrue("Payload must include security_tier", payload.contains("\"security_tier\":\"STRICT\""))
    }

    @Test
    fun testSyncHmacSignatureAuthentication() {
        val deviceId = "OMNIFACE-KIOSK-07"
        val timestamp = 1756000600000L
        val secret = "super_secure_enterprise_hmac_secret_2026"
        val payload = """{"device_id":"$deviceId","records":[]}"""

        val signingData = AttendanceSyncWorker.generateSigningData(deviceId, timestamp, payload)
        val signature = AndroidSecurityUtils.computeHmacSha256(secret, signingData)

        // 1. Valid verification
        assertTrue(AndroidSecurityUtils.verifyHmacSha256(secret, signingData, signature))

        // 2. Tampered payload fails
        val tamperedPayload = """{"device_id":"$deviceId","records":[{"id":"injected"}]}"""
        val tamperedSigningData = AttendanceSyncWorker.generateSigningData(deviceId, timestamp, tamperedPayload)
        assertFalse(AndroidSecurityUtils.verifyHmacSha256(secret, tamperedSigningData, signature))

        // 3. Tampered timestamp fails
        val replaySigningData = AttendanceSyncWorker.generateSigningData(deviceId, timestamp + 5000L, payload)
        assertFalse(AndroidSecurityUtils.verifyHmacSha256(secret, replaySigningData, signature))

        // 4. Wrong secret fails
        assertFalse(AndroidSecurityUtils.verifyHmacSha256("wrong_secret", signingData, signature))
    }

    @Test
    fun testSyncResponseParsingExhaustive() {
        // Success cases
        assertTrue(AttendanceSyncWorker.validateSyncResponse("{\"status\":\"SUCCESS\",\"count\":5}"))
        assertTrue(AttendanceSyncWorker.validateSyncResponse("{\"success\":true,\"synced\":10}"))
        assertTrue(AttendanceSyncWorker.validateSyncResponse("{\"status\":\"OK\"}"))
        assertTrue(AttendanceSyncWorker.validateSyncResponse("")) // HTTP 204 No Content
        assertTrue(AttendanceSyncWorker.validateSyncResponse("OK"))

        // Error / Fail-closed cases
        assertFalse(AttendanceSyncWorker.validateSyncResponse("{\"status\":\"error\",\"message\":\"Unauthorized\"}"))
        assertFalse(AttendanceSyncWorker.validateSyncResponse("{\"status\":\"failed\"}"))
        assertFalse(AttendanceSyncWorker.validateSyncResponse("{\"status\":\"rejected\"}"))
        assertFalse(AttendanceSyncWorker.validateSyncResponse("{\"success\":false}"))
        assertFalse(AttendanceSyncWorker.validateSyncResponse("{\"error\":\"Invalid HMAC\"}"))
        assertFalse(AttendanceSyncWorker.validateSyncResponse("500 Internal Server Error"))
    }

    @Test
    fun testOfflineBatchMarkAsSyncedIdempotency() = runBlocking {
        for (i in 1..4) {
            val record = AttendanceRecordEntity(
                recordId = "REC_BATCH_$i",
                studentRoll = "ROLL_$i",
                studentName = "Student $i",
                sessionDate = "2026-08-25",
                timestamp = 1756000700000L + i,
                confidencePct = 95.0f,
                securityTier = "HIGH",
                sha256Hash = "hash_$i",
                isSynced = false
            )
            fakeAttendanceDao.insertRecord(record)
        }

        // Fetch page 1 (limit 2)
        val page1 = fakeAttendanceDao.getUnsyncedRecordsPaged(limit = 2)
        assertEquals(2, page1.size)
        assertEquals("REC_BATCH_1", page1[0].recordId)
        assertEquals("REC_BATCH_2", page1[1].recordId)

        // Mark page 1 as synced
        fakeAttendanceDao.markAsSynced(page1.map { it.recordId })

        // Fetch page 2 (limit 2)
        val page2 = fakeAttendanceDao.getUnsyncedRecordsPaged(limit = 2)
        assertEquals(2, page2.size)
        assertEquals("REC_BATCH_3", page2[0].recordId)
        assertEquals("REC_BATCH_4", page2[1].recordId)

        // Mark page 2 as synced
        fakeAttendanceDao.markAsSynced(page2.map { it.recordId })

        // Unsynced should now be empty
        val remaining = fakeAttendanceDao.getUnsyncedRecords()
        assertEquals(0, remaining.size)

        // Total records preserved
        assertEquals(4, fakeAttendanceDao.getAllRecords().size)
        assertTrue(fakeAttendanceDao.getAllRecords().all { it.isSynced })
    }
}
