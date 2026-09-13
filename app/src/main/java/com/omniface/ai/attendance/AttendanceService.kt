package com.omniface.ai.attendance

import android.util.Log
import androidx.room.withTransaction
import com.omniface.ai.data.local.AppDatabase
import com.omniface.ai.data.local.entity.AegisOutboxEntity
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.ml.verification.domain.VerificationDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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

    suspend fun recordVerifiedAttendance(
        verified: VerificationDecision.Verified,
        securityTier: String = "STANDARD",
        timestamp: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        val sessionDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))
        val recordId = UUID.randomUUID().toString()

        val executeBlock: suspend () -> Boolean = {
            // Check if already checked in today (duplicate prevention)
            val existing = attendanceDao.getRecordForStudentOnDate(sessionDate, verified.identityId)
            if (existing != null) {
                Log.i(TAG, "Skipping duplicate attendance for ${verified.identityId} on $sessionDate")
                false
            } else {
                // 1. Determine Authoritative Aegis Leaf Hash
                val effectiveHash = if (verified.leafHash.isNotBlank()) {
                    verified.leafHash
                } else {
                    val prevHash = attendanceDao.getLatestHash() ?: AegisLedgerHasher.GENESIS_HASH
                    AegisLedgerHasher.computeBlockHash(
                        previousHash = prevHash,
                        studentRoll = verified.identityId,
                        timestamp = timestamp,
                        confidencePct = verified.confidence * 100f
                    )
                }

                // 2. Persist Attendance Record
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

                // 3. Persist Aegis Outbox Entry (Atomic Durability Guarantee)
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
                Log.i(TAG, "Atomically persisted attendance & outbox for ${verified.identityId} ($recordId)")
                true
            }
        }

        try {
            if (database != null) {
                database.withTransaction { executeBlock() }
            } else {
                executeBlock()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to persist attendance for ${verified.identityId}: ${t.message}", t)
            false
        }
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
