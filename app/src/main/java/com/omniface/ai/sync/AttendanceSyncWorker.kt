package com.omniface.ai.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.omniface.ai.OmniFaceApplication
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.security.AndroidSecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class AttendanceSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (runAttemptCount > 5) {
            return@withContext Result.failure()
        }

        val db = OmniFaceApplication.instance.database
        val unsynced = db.attendanceDao().getUnsyncedRecords()
        if (unsynced.isEmpty()) {
            return@withContext Result.success()
        }

        val prefs = applicationContext.getSharedPreferences("OMNIFACE_PREFS", Context.MODE_PRIVATE)
        val syncEndpoint = prefs.getString("SYNC_REST_ENDPOINT", "http://127.0.0.1:8080/api/v1/attendance/sync") ?: "http://127.0.0.1:8080/api/v1/attendance/sync"
        val deviceId = prefs.getString("DEVICE_ID", "OMNIFACE-TERMINAL-01") ?: "OMNIFACE-TERMINAL-01"

        try {
            val isSuccess = dispatchSyncPayload(syncEndpoint, deviceId, unsynced)
            if (isSuccess) {
                val syncedIds = unsynced.map { it.recordId }
                db.attendanceDao().markAsSynced(syncedIds)
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun dispatchSyncPayload(endpoint: String, deviceId: String, records: List<AttendanceRecordEntity>): Boolean {
        val jsonPayload = JSONObject().apply {
            put("device_id", deviceId)
            val jsonArray = JSONArray()
            for (r in records) {
                val item = JSONObject().apply {
                    put("record_id", r.recordId)
                    put("student_roll", r.studentRoll)
                    put("student_name", r.studentName)
                    put("session_date", r.sessionDate)
                    put("timestamp", r.timestamp)
                    put("confidence_pct", r.confidencePct.toDouble())
                    put("security_tier", r.securityTier)
                    put("sha256_hash", r.sha256Hash)
                }
                jsonArray.put(item)
            }
            put("records", jsonArray)
        }

        val deviceFingerprint = AndroidSecurityUtils.computeSha256(deviceId + "_OMNIFACE_KEYSTORE_HW")
        val url = URL(endpoint)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Device-Fingerprint", deviceFingerprint)
        }

        return try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonPayload.toString())
                writer.flush()
            }
            val responseCode = connection.responseCode
            responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }
}
