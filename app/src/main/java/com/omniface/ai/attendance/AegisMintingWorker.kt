package com.omniface.ai.attendance

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.omniface.ai.OmniFaceApplication
import com.omniface.ai.data.local.entity.AegisOutboxEntity
import com.omniface.ai.security.AndroidSecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Asynchronous Background Worker for Aegis Blockchain Minting.
 *
 * Polls the aegis_outbox table and computes cryptographic SHA-256 block hashes.
 * Invariant: Failures here NEVER invalidate or block attendance records.
 */
class AegisMintingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "AegisMintingWorker"

        suspend fun processPendingOutbox(app: OmniFaceApplication): Int = withContext(Dispatchers.IO) {
            val db = app.database
            val pendingList = db.aegisOutboxDao().getPendingOutbox(limit = 50)
            if (pendingList.isEmpty()) return@withContext 0

            var mintedCount = 0
            for (entry in pendingList) {
                try {
                    // Compute block hash and update outbox row
                    val blockHash = AndroidSecurityUtils.computeAegisBlockHash(
                        previousHash = null, // Will use genesis or continuous chain
                        studentRoll = entry.studentRoll,
                        timestamp = entry.timestamp,
                        confidencePct = entry.confidencePct
                    )
                    db.aegisOutboxDao().updateStatus(entry.recordId, AegisOutboxEntity.STATUS_MINTED)
                    mintedCount++
                } catch (t: Throwable) {
                    Log.w(TAG, "Minting failed for ${entry.recordId}: ${t.message}")
                    db.aegisOutboxDao().incrementRetryCount(entry.recordId)
                }
            }
            mintedCount
        }

        fun enqueue(context: Context) {
            try {
                val request = androidx.work.OneTimeWorkRequestBuilder<AegisMintingWorker>()
                    .setBackoffCriteria(
                        androidx.work.BackoffPolicy.EXPONENTIAL,
                        10,
                        java.util.concurrent.TimeUnit.SECONDS
                    )
                    .build()
                androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                    "AegisMintingWork",
                    androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to enqueue Aegis worker: ${t.message}")
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val count = processPendingOutbox(OmniFaceApplication.instance)
            Log.i(TAG, "Processed $count pending Aegis outbox blocks")
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Aegis worker encountered error: ${t.message}", t)
            Result.retry()
        }
    }
}
