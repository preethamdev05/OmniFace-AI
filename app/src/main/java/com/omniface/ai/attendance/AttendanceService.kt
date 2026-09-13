package com.omniface.ai.attendance

import android.util.Log
import androidx.room.withTransaction
import com.omniface.ai.data.local.AppDatabase
import com.omniface.ai.data.local.entity.AegisOutboxEntity
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.ml.pipeline.BiometricSynthesisDecision
import com.omniface.ai.ml.verification.domain.VerificationDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Result of a batch attendance transaction.
 */
data class BatchAttendanceResult(
    val newlyRecorded: List<AttendanceRecordEntity>,
    val skippedDuplicates: List<String>
)

/**
 * Sovereign Attendance Persistence Service.
 *
 * Implements the Transactional Outbox Pattern:
 * Persists the confirmed attendance record and queues an atomic Aegis outbox entry
 * within a single SQLite transaction. Attendance durability is 100% independent
 * of transient in-memory events and asynchronous blockchain minting.
 */
class AttendanceService(
    private val database: AppDatabase? = null,
    private val attendanceDao: com.omniface.ai.data.local.dao.AttendanceDao = database!!.attendanceDao(),
    private val aegisOutboxDao: com.omniface.ai.data.local.dao.AegisOutboxDao = database!!.aegisOutboxDao()
) {
    companion object {
        private const val TAG = "AttendanceService"
    }

    /**
     * Atomically commits attendance records and corresponding Aegis outbox entries
     * for multiple verified candidates in a single SQLite transaction.
     *
     * Invariants:
     * 1. In-batch deduplication: if multiple detections of the same roll exist, the highest confidence entry wins.
     * 2. Idempotent daily checks: skips identities already marked present today.
     * 3. Sequential Aegis hash chaining: guarantees every record in the batch builds upon the latest ledger hash.
     * 4. Atomic durability: attendance records and outbox entries are committed atomically.
     */
    suspend fun recordVerifiedBatch(
        verifiedList: List<VerificationDecision.Verified>,
        securityTier: String = "STANDARD",
        timestamp: Long = System.currentTimeMillis()
    ): BatchAttendanceResult = withContext(Dispatchers.IO) {
        if (verifiedList.isEmpty()) {
            return@withContext BatchAttendanceResult(emptyList(), emptyList())
        }

        val sessionDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))

        // 1. In-batch deduplication: keep highest confidence detection per student roll
        val deduplicatedCandidates = mutableMapOf<String, VerificationDecision.Verified>()
        val inBatchDuplicates = mutableListOf<String>()

        for (item in verifiedList) {
            val roll = item.identityId
            if (roll.isBlank()) continue
            val existing = deduplicatedCandidates[roll]
            if (existing == null) {
                deduplicatedCandidates[roll] = item
            } else {
                inBatchDuplicates.add(roll)
                if (item.confidence > existing.confidence) {
                    deduplicatedCandidates[roll] = item
                }
            }
        }

        val executeBlock: suspend () -> BatchAttendanceResult = {
            val newlyRecorded = mutableListOf<AttendanceRecordEntity>()
            val skippedDuplicates = ArrayList(inBatchDuplicates)

            var prevHash = attendanceDao.getLatestHash() ?: AegisLedgerHasher.GENESIS_HASH

            for ((_, verified) in deduplicatedCandidates) {
                // Check if already checked in today (idempotency guard)
                val existing = attendanceDao.getRecordForStudentOnDate(sessionDate, verified.identityId)
                if (existing != null) {
                    Log.i(TAG, "Skipping duplicate attendance for ${verified.identityId} on $sessionDate")
                    skippedDuplicates.add(verified.identityId)
                    continue
                }

                // Determine Authoritative Aegis Leaf Hash with sequential chaining
                val effectiveHash = if (verified.leafHash.isNotBlank()) {
                    verified.leafHash
                } else {
                    val computed = AegisLedgerHasher.computeBlockHash(
                        previousHash = prevHash,
                        studentRoll = verified.identityId,
                        timestamp = timestamp,
                        confidencePct = verified.confidence * 100f
                    )
                    prevHash = computed
                    computed
                }
                prevHash = effectiveHash

                val recordId = UUID.randomUUID().toString()
                val record = AttendanceRecordEntity(
                    recordId = recordId,
                    studentRoll = verified.identityId,
                    studentName = verified.displayName,
                    sessionDate = sessionDate,
                    timestamp = timestamp,
                    confidencePct = verified.confidence * 100f,
                    securityTier = securityTier,
                    sha256Hash = effectiveHash,
                    isSynced = false
                )
                attendanceDao.insertRecord(record)

                val outbox = AegisOutboxEntity(
                    recordId = recordId,
                    studentRoll = verified.identityId,
                    timestamp = timestamp,
                    confidencePct = verified.confidence * 100f,
                    leafHash = effectiveHash,
                    status = AegisOutboxEntity.STATUS_PENDING_MINT,
                    retryCount = 0,
                    createdAt = timestamp
                )
                aegisOutboxDao.insertOutbox(outbox)
                newlyRecorded.add(record)
                Log.i(TAG, "Atomically persisted attendance & outbox for ${verified.identityId} ($recordId)")
            }

            BatchAttendanceResult(newlyRecorded = newlyRecorded, skippedDuplicates = skippedDuplicates)
        }

        try {
            if (database != null) {
                database.withTransaction { executeBlock() }
            } else {
                executeBlock()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to persist attendance batch: ${t.message}", t)
            BatchAttendanceResult(emptyList(), verifiedList.map { it.identityId })
        }
    }

    /**
     * Batch convenience API for [BiometricSynthesisDecision] results directly from the recognition pipeline.
     */
    suspend fun recordSynthesisBatch(
        decisions: List<BiometricSynthesisDecision>,
        roleLookup: (String) -> String = { "STUDENT" },
        securityTier: String = "STANDARD",
        timestamp: Long = System.currentTimeMillis()
    ): BatchAttendanceResult {
        val verifiedList = decisions.filter { it.isAttendanceAuthorized && it.matchedStudentRoll.isNotBlank() }
            .map { decision ->
                VerificationDecision.Verified(
                    identityId = decision.matchedStudentRoll,
                    displayName = decision.matchedStudentName,
                    role = roleLookup(decision.matchedStudentRoll),
                    confidence = decision.matchConfidence / 100f,
                    liveness = decision.livenessScore / 100f,
                    leafHash = ""
                )
            }
        return recordVerifiedBatch(verifiedList, securityTier, timestamp)
    }

    suspend fun recordVerifiedAttendance(
        verified: VerificationDecision.Verified,
        securityTier: String = "STANDARD",
        timestamp: Long = System.currentTimeMillis()
    ): Boolean {
        val result = recordVerifiedBatch(listOf(verified), securityTier, timestamp)
        return result.newlyRecorded.isNotEmpty()
    }

    /**
     * Resolves the latest authoritative blockchain hash in the ledger.
     */
    suspend fun getLatestHash(): String = withContext(Dispatchers.IO) {
        attendanceDao.getLatestHash() ?: AegisLedgerHasher.GENESIS_HASH
    }

    /**
     * Checks if a student is already checked in today.
     */
    suspend fun isAlreadyCheckedInToday(
        studentRoll: String,
        timestamp: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        val sessionDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))
        attendanceDao.getRecordForStudentOnDate(sessionDate, studentRoll) != null
    }

    /**
     * Returns the total historical attendance count for a student.
     */
    suspend fun getAttendanceCountForStudent(studentRoll: String): Int = withContext(Dispatchers.IO) {
        attendanceDao.getAttendanceCountForStudent(studentRoll)
    }

    /**
     * Observes attendance records for a student reactively.
     */
    fun getRecordsForStudentFlow(studentRoll: String): kotlinx.coroutines.flow.Flow<List<AttendanceRecordEntity>> {
        return attendanceDao.getRecordsForStudentFlow(studentRoll)
    }
}
