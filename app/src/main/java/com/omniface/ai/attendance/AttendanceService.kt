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
    private val database: AppDatabase
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

        try {
            database.withTransaction {
                // Check if already checked in today (duplicate prevention)
                val existing = database.attendanceDao().getRecordForStudentOnDate(sessionDate, verified.identityId)
                if (existing != null) {
                    Log.i(TAG, "Skipping duplicate attendance for ${verified.identityId} on $sessionDate")
                    return@withTransaction false
                }

                // 1. Persist Attendance Record
                val record = AttendanceRecordEntity(
                    recordId = recordId,
                    studentRoll = verified.identityId,
                    studentName = verified.displayName,
                    sessionDate = sessionDate,
                    timestamp = timestamp,
                    confidencePct = verified.confidence * 100f,
                    securityTier = securityTier,
                    sha256Hash = verified.leafHash,
                    isSynced = false
                )
                database.attendanceDao().insertRecord(record)

                // 2. Persist Aegis Outbox Entry (Atomic Durability Guarantee)
                val outbox = AegisOutboxEntity(
                    recordId = recordId,
                    studentRoll = verified.identityId,
                    timestamp = timestamp,
                    confidencePct = verified.confidence * 100f,
                    leafHash = verified.leafHash,
                    status = AegisOutboxEntity.STATUS_PENDING_MINT,
                    retryCount = 0,
                    createdAt = timestamp
                )
                database.aegisOutboxDao().insertOutbox(outbox)
                Log.i(TAG, "Atomically persisted attendance & outbox for ${verified.identityId} ($recordId)")
                true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to persist attendance for ${verified.identityId}: ${t.message}", t)
            false
        }
    }
}
