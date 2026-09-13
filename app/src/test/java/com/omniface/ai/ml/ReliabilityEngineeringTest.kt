package com.omniface.ai.ml

import com.omniface.ai.attendance.AttendanceService
import com.omniface.ai.data.local.dao.AegisOutboxDao
import com.omniface.ai.data.local.dao.AttendanceDao
import com.omniface.ai.data.local.entity.AegisOutboxEntity
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.hardware.ThermalGovernor
import com.omniface.ai.hardware.ThermalState
import com.omniface.ai.ml.verification.domain.VerificationDecision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🧪 Test Suite for Stage 17 & 18: Reliability Engineering & Offline Durability.
 *
 * Verifies:
 * 1. Atomic Transactional Outbox: Attendance record + Aegis outbox entry committed atomically.
 * 2. Idempotent Deduplication: Rejects duplicate attendance on same calendar day.
 * 3. Sovereign Authority: AttendanceService acts as single authoritative data layer.
 * 4. Thermal Throttling: ThermalGovernor regulates downscale factors and frame skip rates.
 */
class ReliabilityEngineeringTest {

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

    @After
    fun tearDown() {
        ThermalGovernor.setSimulationOverride(null)
    }

    @Test
    fun testAtomicAttendance_persistsRecordAndOutbox() = runBlocking {
        val verified = VerificationDecision.Verified(
            identityId = "STU_1001",
            displayName = "Ananya Iyer",
            role = "STUDENT",
            confidence = 0.88f,
            liveness = 0.96f,
            leafHash = "hash_ananya_001"
        )

        val timestamp = System.currentTimeMillis()
        val success = attendanceService.recordVerifiedAttendance(verified, "HIGH", timestamp)
        assertTrue("Initial verification must record successfully", success)

        // Verify Attendance Record
        val sessionDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))
        val record = fakeAttendanceDao.getRecordForStudentOnDate(sessionDate, "STU_1001")
        assertNotNull("AttendanceRecordEntity must be persisted in SQLite", record)
        assertEquals("STU_1001", record!!.studentRoll)
        assertEquals("Ananya Iyer", record.studentName)
        assertEquals("hash_ananya_001", record.sha256Hash)
        assertEquals(88f, record.confidencePct, 0.1f)

        // Verify Aegis Outbox Entry (Transactional Outbox Pattern)
        val outboxList = fakeAegisOutboxDao.getPendingOutbox(limit = 10)
        assertEquals(1, outboxList.size)
        val outbox = outboxList[0]
        assertEquals("STU_1001", outbox.studentRoll)
        assertEquals(AegisOutboxEntity.STATUS_PENDING_MINT, outbox.status)
        assertEquals("hash_ananya_001", outbox.leafHash)
    }

    @Test
    fun testIdempotentDeduplication_rejectsSecondCheckInSameDay() = runBlocking {
        val verified = VerificationDecision.Verified(
            identityId = "STU_1002",
            displayName = "Vikram Reddy",
            role = "STUDENT",
            confidence = 0.91f,
            liveness = 0.99f,
            leafHash = "hash_vikram_001"
        )

        val timestamp = System.currentTimeMillis()

        // 1st record -> success
        val firstResult = attendanceService.recordVerifiedAttendance(verified, "HIGH", timestamp)
        assertTrue("First attendance record must succeed", firstResult)

        // 2nd record on same day -> rejected as duplicate
        val secondResult = attendanceService.recordVerifiedAttendance(verified, "HIGH", timestamp + 5000L)
        assertFalse("Duplicate attendance on same day must be rejected", secondResult)

        // Verify only 1 record exists
        val count = attendanceService.getAttendanceCountForStudent("STU_1002")
        assertEquals(1, count)

        // Verify outbox has only 1 entry
        val outboxList = fakeAegisOutboxDao.getPendingOutbox(limit = 10)
        assertEquals(1, outboxList.size)
    }

    @Test
    fun testAttendanceService_queryEncapsulation() = runBlocking {
        val verified = VerificationDecision.Verified(
            identityId = "STU_1003",
            displayName = "Pooja Hegde",
            role = "STUDENT",
            confidence = 0.85f,
            liveness = 0.90f,
            leafHash = "hash_pooja_001"
        )

        assertFalse(attendanceService.isAlreadyCheckedInToday("STU_1003"))

        attendanceService.recordVerifiedAttendance(verified)

        assertTrue(attendanceService.isAlreadyCheckedInToday("STU_1003"))
        assertEquals(1, attendanceService.getAttendanceCountForStudent("STU_1003"))
        assertEquals("hash_pooja_001", attendanceService.getLatestHash())
    }

    @Test
    fun testAttendanceService_batchAtomicRecording() = runBlocking {
        val students = listOf(
            VerificationDecision.Verified(
                identityId = "STU_2001",
                displayName = "Rahul Sharma",
                role = "STUDENT",
                confidence = 0.92f,
                liveness = 0.95f,
                leafHash = ""
            ),
            VerificationDecision.Verified(
                identityId = "STU_2002",
                displayName = "Priya Patel",
                role = "STUDENT",
                confidence = 0.89f,
                liveness = 0.91f,
                leafHash = ""
            ),
            VerificationDecision.Verified(
                identityId = "STU_2003",
                displayName = "Arjun Reddy",
                role = "STUDENT",
                confidence = 0.96f,
                liveness = 0.98f,
                leafHash = ""
            )
        )

        val result = attendanceService.recordVerifiedBatch(students, securityTier = "HIGH")

        assertEquals(3, result.newlyRecorded.size)
        assertEquals(0, result.skippedDuplicates.size)

        assertTrue(attendanceService.isAlreadyCheckedInToday("STU_2001"))
        assertTrue(attendanceService.isAlreadyCheckedInToday("STU_2002"))
        assertTrue(attendanceService.isAlreadyCheckedInToday("STU_2003"))
        assertEquals(1, attendanceService.getAttendanceCountForStudent("STU_2001"))
        assertEquals(1, attendanceService.getAttendanceCountForStudent("STU_2002"))
        assertEquals(1, attendanceService.getAttendanceCountForStudent("STU_2003"))

        // Check hash chaining
        val hashes = result.newlyRecorded.map { it.sha256Hash }
        assertEquals(3, hashes.distinct().size)
        hashes.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun testAttendanceService_inBatchAndDailyDeduplication() = runBlocking {
        val batch1 = listOf(
            VerificationDecision.Verified(
                identityId = "STU_3001",
                displayName = "Meera Nair",
                role = "STUDENT",
                confidence = 0.80f,
                liveness = 0.88f,
                leafHash = ""
            ),
            VerificationDecision.Verified(
                identityId = "STU_3001", // Duplicate in same batch with higher confidence
                displayName = "Meera Nair",
                role = "STUDENT",
                confidence = 0.95f,
                liveness = 0.92f,
                leafHash = ""
            ),
            VerificationDecision.Verified(
                identityId = "STU_3002",
                displayName = "Vikram Singh",
                role = "STUDENT",
                confidence = 0.91f,
                liveness = 0.90f,
                leafHash = ""
            )
        )

        val result1 = attendanceService.recordVerifiedBatch(batch1)
        assertEquals(2, result1.newlyRecorded.size)
        assertTrue(result1.skippedDuplicates.contains("STU_3001"))

        // Verify that the higher confidence record (95%) was saved
        val meeraRecord = result1.newlyRecorded.first { it.studentRoll == "STU_3001" }
        assertEquals(95.0f, meeraRecord.confidencePct, 1e-3f)

        // Second batch attempts to record Vikram again + new student Rohan
        val batch2 = listOf(
            VerificationDecision.Verified(
                identityId = "STU_3002", // Already checked in today
                displayName = "Vikram Singh",
                role = "STUDENT",
                confidence = 0.94f,
                liveness = 0.93f,
                leafHash = ""
            ),
            VerificationDecision.Verified(
                identityId = "STU_3003",
                displayName = "Rohan Verma",
                role = "STUDENT",
                confidence = 0.88f,
                liveness = 0.89f,
                leafHash = ""
            )
        )

        val result2 = attendanceService.recordVerifiedBatch(batch2)
        assertEquals(1, result2.newlyRecorded.size)
        assertEquals("STU_3003", result2.newlyRecorded[0].studentRoll)
        assertTrue(result2.skippedDuplicates.contains("STU_3002"))
    }

    @Test
    fun testThermalGovernor_adaptiveScaleFactors() {
        ThermalGovernor.setSimulationOverride(ThermalState.NOMINAL)
        assertEquals(1.0f, ThermalGovernor.thermalState.value.downscaleFactor, 1e-4f)
        assertEquals(1L, ThermalGovernor.thermalState.value.frameSkipMod)

        ThermalGovernor.setSimulationOverride(ThermalState.WARM)
        assertEquals(0.75f, ThermalGovernor.thermalState.value.downscaleFactor, 1e-4f)
        assertEquals(2L, ThermalGovernor.thermalState.value.frameSkipMod)

        ThermalGovernor.setSimulationOverride(ThermalState.CRITICAL)
        assertEquals(0.50f, ThermalGovernor.thermalState.value.downscaleFactor, 1e-4f)
        assertEquals(3L, ThermalGovernor.thermalState.value.frameSkipMod)

        // Reset to NOMINAL
        ThermalGovernor.setSimulationOverride(ThermalState.NOMINAL)
    }
}

/**
 * In-memory test fake for [AttendanceDao].
 */
class FakeAttendanceDao : AttendanceDao {
    private val records = mutableListOf<AttendanceRecordEntity>()

    override suspend fun insertRecord(record: AttendanceRecordEntity) {
        records.removeAll { it.recordId == record.recordId }
        records.add(record)
    }

    override suspend fun insertRecords(records: List<AttendanceRecordEntity>) {
        records.forEach { insertRecord(it) }
    }

    override suspend fun getAllRecords(): List<AttendanceRecordEntity> = records.toList()

    override fun getAllRecordsFlow(): Flow<List<AttendanceRecordEntity>> = flowOf(records.toList())

    override fun getRecordsBetweenTimestampsFlow(startTime: Long, endTime: Long): Flow<List<AttendanceRecordEntity>> =
        flowOf(records.filter { it.timestamp in startTime..endTime })

    override fun getRecentRecordsFlow(limit: Int): Flow<List<AttendanceRecordEntity>> =
        flowOf(records.sortedByDescending { it.timestamp }.take(limit))

    override fun getRecordsForDateFlow(date: String): Flow<List<AttendanceRecordEntity>> =
        flowOf(records.filter { it.sessionDate == date })

    override suspend fun getRecordForStudentOnDate(date: String, roll: String): AttendanceRecordEntity? =
        records.firstOrNull { it.sessionDate == date && it.studentRoll == roll }

    override fun getRecordsForStudentFlow(roll: String): Flow<List<AttendanceRecordEntity>> =
        flowOf(records.filter { it.studentRoll == roll })

    override suspend fun getAttendanceCountForStudent(roll: String): Int =
        records.count { it.studentRoll == roll }

    override fun getCountForDateFlow(date: String): Flow<Int> =
        flowOf(records.count { it.sessionDate == date })

    override suspend fun getUnsyncedRecords(): List<AttendanceRecordEntity> =
        records.filter { !it.isSynced }

    override suspend fun getUnsyncedRecordsPaged(limit: Int): List<AttendanceRecordEntity> =
        records.filter { !it.isSynced }.take(limit)

    override suspend fun markAsSynced(recordIds: List<String>) {
        val updated = records.map {
            if (recordIds.contains(it.recordId)) it.copy(isSynced = true) else it
        }
        records.clear()
        records.addAll(updated)
    }

    override suspend fun getLatestHash(): String? =
        records.maxByOrNull { it.timestamp }?.sha256Hash

    override suspend fun purgeLegacyRecordsBefore(cutoffTimestamp: Long): Int {
        val before = records.size
        records.removeAll { it.timestamp < cutoffTimestamp }
        return before - records.size
    }

    override suspend fun deleteAllRecords(): Int {
        val count = records.size
        records.clear()
        return count
    }
}

/**
 * In-memory test fake for [AegisOutboxDao].
 */
class FakeAegisOutboxDao : AegisOutboxDao {
    private val outboxList = mutableListOf<AegisOutboxEntity>()

    override suspend fun insertOutbox(entry: AegisOutboxEntity) {
        outboxList.removeAll { it.recordId == entry.recordId }
        outboxList.add(entry)
    }

    override suspend fun getPendingOutbox(limit: Int): List<AegisOutboxEntity> =
        outboxList.filter { it.status == AegisOutboxEntity.STATUS_PENDING_MINT }.take(limit)

    override suspend fun updateStatus(recordId: String, status: String) {
        val idx = outboxList.indexOfFirst { it.recordId == recordId }
        if (idx != -1) {
            outboxList[idx] = outboxList[idx].copy(status = status)
        }
    }

    override suspend fun incrementRetryCount(recordId: String) {
        val idx = outboxList.indexOfFirst { it.recordId == recordId }
        if (idx != -1) {
            outboxList[idx] = outboxList[idx].copy(retryCount = outboxList[idx].retryCount + 1)
        }
    }

    override fun getPendingCountFlow(): Flow<Int> =
        flowOf(outboxList.count { it.status == AegisOutboxEntity.STATUS_PENDING_MINT })

    override suspend fun getPendingCount(): Int =
        outboxList.count { it.status == AegisOutboxEntity.STATUS_PENDING_MINT }
}
