package com.omniface.ai.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.omniface.ai.OmniFaceApplication
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.security.AndroidSecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
        if (isStopped) return@withContext Result.failure()

        val db = OmniFaceApplication.instance.database
        // Paginate to avoid OOM — fetch at most 200 unsynced records per attempt
        val unsynced = db.attendanceDao().getUnsyncedRecordsPaged(limit = 200)
        if (unsynced.isEmpty()) {
            return@withContext Result.success()
        }

        val prefs = applicationContext.getSharedPreferences("OMNIFACE_PREFS", Context.MODE_PRIVATE)
        val syncEndpoint = prefs.getString("SYNC_REST_ENDPOINT", "https://127.0.0.1:8080/api/v1/attendance/sync")
            ?: "https://127.0.0.1:8080/api/v1/attendance/sync"
        val deviceId = prefs.getString("DEVICE_ID", "OMNIFACE-TERMINAL-01") ?: "OMNIFACE-TERMINAL-01"

        // Enforce HTTPS in production — refuse cleartext
        if (!syncEndpoint.startsWith("https://") && !syncEndpoint.contains("127.0.0.1") && !syncEndpoint.contains("localhost")) {
            Log.w("AttendanceSync", "Refusing cleartext sync endpoint: $syncEndpoint")
            return@withContext Result.failure()
        }

        try {
            ensureActive()
            val isSuccess = dispatchSyncPayload(syncEndpoint, deviceId, unsynced)
            if (isSuccess) {
                val syncedIds = unsynced.map { it.recordId }
                db.attendanceDao().markAsSynced(syncedIds)
                Log.i("AttendanceSync", "Synced ${unsynced.size} records to $syncEndpoint")
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("AttendanceSync", "Sync failed: ${e.message}")
            Result.retry()
        }
    }

    private fun dispatchSyncPayload(endpoint: String, deviceId: String, records: List<AttendanceRecordEntity>): Boolean {
        val payloadString = buildPayloadString(deviceId, records)
        val requestTimestamp = System.currentTimeMillis()
        val hmacSecret = try {
            AndroidSecurityUtils.getOrCreateHmacSecret(applicationContext)
        } catch (_: Throwable) {
            "OMNIFACE_DEFAULT_SYNC_SECRET"
        }
        val signingData = generateSigningData(deviceId, requestTimestamp, payloadString)
        val hmacSignature = AndroidSecurityUtils.computeHmacSha256(hmacSecret, signingData)
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
            setRequestProperty("X-Device-ID", deviceId)
            setRequestProperty("X-Timestamp", requestTimestamp.toString())
            setRequestProperty("X-Signature-Algorithm", "HMAC-SHA256")
            setRequestProperty("X-HMAC-Signature", hmacSignature)
        }

        return try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payloadString)
                writer.flush()
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                Log.w("AttendanceSync", "Sync HTTP error: $responseCode, response: $errorBody")
                return false
            }

            val responseBody = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val serverSignature = connection.getHeaderField("X-Server-Signature")
            if (!serverSignature.isNullOrBlank()) {
                val isServerValid = AndroidSecurityUtils.verifyHmacSha256(hmacSecret, responseBody, serverSignature)
                if (!isServerValid) {
                    Log.w("AttendanceSync", "Server response HMAC signature verification failed!")
                    return false
                }
            }

            validateSyncResponse(responseBody, records.size)
        } catch (e: Exception) {
            Log.w("AttendanceSync", "Sync transport failed: ${e.message}")
            false
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun buildPayloadString(deviceId: String, records: List<AttendanceRecordEntity>): String {
            val recordsJson = records.joinToString(separator = ",", prefix = "[", postfix = "]") { r ->
                """{"record_id":"${escapeJson(r.recordId)}","student_roll":"${escapeJson(r.studentRoll)}","student_name":"${escapeJson(r.studentName)}","session_date":"${escapeJson(r.sessionDate)}","timestamp":${r.timestamp},"confidence_pct":${r.confidencePct},"security_tier":"${escapeJson(r.securityTier)}","sha256_hash":"${escapeJson(r.sha256Hash)}"}"""
            }
            return """{"device_id":"${escapeJson(deviceId)}","records":$recordsJson}"""
        }

        private fun escapeJson(str: String): String {
            return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        }

        fun generateSigningData(deviceId: String, timestamp: Long, payload: String): String {
            return "${deviceId}:${timestamp}:${payload}"
        }

        fun validateSyncResponse(responseBody: String, expectedRecordCount: Int = 0): Boolean {
            val trimmed = responseBody.trim()
            if (trimmed.isEmpty()) return true
            val lower = trimmed.lowercase()
            if (lower.contains("\"status\":\"error\"") || lower.contains("\"status\": \"error\"") ||
                lower.contains("\"status\":\"failed\"") || lower.contains("\"status\": \"failed\"") ||
                lower.contains("\"status\":\"rejected\"") || lower.contains("\"status\": \"rejected\"") ||
                lower.contains("\"success\":false") || lower.contains("\"success\": false") ||
                lower.contains("\"error\":") || lower.contains("\"error\" :") ||
                lower.startsWith("error") || lower.startsWith("failed") || lower.contains("internal server error")
            ) {
                return false
            }
            return true
        }
    }
}
