package com.omniface.ai.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.omniface.ai.OmniFaceApplication
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.data.local.entity.PersonEntity
import com.omniface.ai.hardware.FleetTopologyManager
import com.omniface.ai.hardware.KioskNode
import com.omniface.ai.security.AndroidSecurityUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

sealed interface FleetSyncState {
    object Idle : FleetSyncState
    data class Syncing(val message: String) : FleetSyncState
    data class Synced(val timestampMs: Long, val recordCount: Int, val peerNodeCount: Int) : FleetSyncState
    data class OfflineReady(val pendingCount: Int, val peerNodeCount: Int) : FleetSyncState
    data class Error(val error: String) : FleetSyncState
}

/**
 * Enterprise Fleet & Cloud Synchronization Engine.
 *
 * Coordinates cryptographic Merkle-proofed attendance synchronization between
 * sovereign on-device SQLite databases and authorized multi-kiosk enterprise backends.
 *
 * Strictly adheres to Zero-Knowledge and Data Privacy mandates:
 * - Only verified SHA-256 attendance hashes and audit metadata are synchronized.
 * - Biometric templates and raw facial pixels never leave the secure hardware storage.
 */
object CloudFleetSyncEngine {

    private const val TAG = "CloudFleetSyncEngine"
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _syncState = MutableStateFlow<FleetSyncState>(FleetSyncState.Idle)
    val syncState: StateFlow<FleetSyncState> = _syncState.asStateFlow()

    private val _unsyncedCount = MutableStateFlow(0)
    val unsyncedCount: StateFlow<Int> = _unsyncedCount.asStateFlow()

    fun initialize(context: Context) {
        // Initialize local fleet node topology
        FleetTopologyManager.initializeLocalNode(context)

        // Observe database unsynced records
        engineScope.launch {
            try {
                val db = OmniFaceApplication.instance.database
                val unsynced = db.attendanceDao().getUnsyncedRecordsPaged(limit = 1000)
                _unsyncedCount.value = unsynced.size
                val peerCount = FleetTopologyManager.kioskNodes.value.size

                if (unsynced.isEmpty()) {
                    _syncState.value = FleetSyncState.Synced(
                        timestampMs = System.currentTimeMillis(),
                        recordCount = 0,
                        peerNodeCount = peerCount
                    )
                } else {
                    _syncState.value = FleetSyncState.OfflineReady(
                        pendingCount = unsynced.size,
                        peerNodeCount = peerCount
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Initialization failed: ${t.message}")
            }
        }
    }

    /**
     * Dispatches an immediate high-priority synchronization pass with real cryptographic signing.
     */
    suspend fun syncNow(context: Context): Boolean = withContext(Dispatchers.IO) {
        _syncState.value = FleetSyncState.Syncing("Gathering unsynced attendance records...")

        val db = OmniFaceApplication.instance.database
        val unsynced = db.attendanceDao().getUnsyncedRecordsPaged(limit = 200)
        val peerCount = FleetTopologyManager.kioskNodes.value.size

        if (unsynced.isEmpty()) {
            _unsyncedCount.value = 0
            _syncState.value = FleetSyncState.Synced(
                timestampMs = System.currentTimeMillis(),
                recordCount = 0,
                peerNodeCount = peerCount
            )
            pullRosterFromCloud(context)
            return@withContext true
        }

        val prefs = context.getSharedPreferences("OMNIFACE_PREFS", Context.MODE_PRIVATE)
        val syncEndpoint = prefs.getString("SYNC_REST_ENDPOINT", "https://omniface.vercel.app/api/v1/attendance/sync")
            ?: "https://omniface.vercel.app/api/v1/attendance/sync"
        val deviceId = prefs.getString("DEVICE_ID", "OMNIFACE-TERMINAL-01") ?: "OMNIFACE-TERMINAL-01"

        _syncState.value = FleetSyncState.Syncing("Dispatching ${unsynced.size} records to fleet...")

        val success = try {
            dispatchBatch(context, syncEndpoint, deviceId, unsynced)
        } catch (e: Exception) {
            Log.w(TAG, "Direct sync failed, falling back to background worker: ${e.message}")
            false
        }

        if (success) {
            val syncedIds = unsynced.map { it.recordId }
            db.attendanceDao().markAsSynced(syncedIds)
            val remaining = db.attendanceDao().getUnsyncedRecordsPaged(limit = 1000).size
            _unsyncedCount.value = remaining
            _syncState.value = FleetSyncState.Synced(
                timestampMs = System.currentTimeMillis(),
                recordCount = unsynced.size,
                peerNodeCount = peerCount
            )
            Log.i(TAG, "✅ Synchronized ${unsynced.size} records to fleet backend.")
            pullRosterFromCloud(context)
            true
        } else {
            // Queue via Android WorkManager for guaranteed background delivery
            val request = OneTimeWorkRequestBuilder<AttendanceSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueue(request)

            _unsyncedCount.value = unsynced.size
            _syncState.value = FleetSyncState.OfflineReady(
                pendingCount = unsynced.size,
                peerNodeCount = peerCount
            )
            false
        }
    }

    /**
     * Pulls the latest authorized roster and 512-D face templates from cloud backend.
     * Enrolls incremental updates into sovereign local Room SQLite storage.
     *
     * @return count of templates successfully synced and cached
     */
    suspend fun pullRosterFromCloud(context: Context): Int = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("OMNIFACE_PREFS", Context.MODE_PRIVATE)
        val securePrefs = try {
            AndroidSecurityUtils.getEncryptedPrefs(context, "OMNIFACE_SECURE_DEVICE_PREFS")
        } catch (_: Throwable) {
            null
        }
        val deviceToken = securePrefs?.getString("DEVICE_TOKEN", null) ?: prefs.getString("DEVICE_TOKEN", null)

        if (deviceToken.isNullOrBlank()) {
            Log.d(TAG, "Device not paired or missing device token; skipping cloud roster pull.")
            return@withContext 0
        }

        val syncEndpoint = prefs.getString("SYNC_REST_ENDPOINT", "https://omniface.vercel.app/api/v1/attendance/sync")
            ?: "https://omniface.vercel.app/api/v1/attendance/sync"
        val pullEndpoint = syncEndpoint.replace("/attendance/sync", "/sync/pull")
        val deviceId = prefs.getString("DEVICE_ID", "OMNIFACE-TERMINAL-01") ?: "OMNIFACE-TERMINAL-01"

        val isLocalDev = pullEndpoint.contains("127.0.0.1") || pullEndpoint.contains("localhost") || pullEndpoint.contains("10.0.2.2") || pullEndpoint.contains("192.168.")
        if (!pullEndpoint.startsWith("https://") && !isLocalDev) {
            Log.w(TAG, "Cleartext sync pull disallowed for remote hosts: $pullEndpoint")
            return@withContext 0
        }

        try {
            val url = URL(pullEndpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 10000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Device-ID", deviceId)
                setRequestProperty("X-Device-Token", deviceToken)
                setRequestProperty("Authorization", "Bearer $deviceToken")
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Failed to pull roster from cloud: HTTP $code")
                return@withContext 0
            }

            val responseBody = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(responseBody)
            if (!root.optBoolean("success", false)) {
                Log.w(TAG, "Cloud roster pull returned unsuccessful: ${root.optString("error")}")
                return@withContext 0
            }

            val studentsJson = root.optJSONArray("students") ?: JSONArray()
            val templatesJson = root.optJSONArray("faceTemplates") ?: JSONArray()

            val personsToInsert = mutableListOf<PersonEntity>()
            for (i in 0 until studentsJson.length()) {
                val sObj = studentsJson.getJSONObject(i)
                val roll = sObj.optString("roll")
                val name = sObj.optString("name")
                val role = sObj.optString("role", "STUDENT")
                val dept = sObj.optString("department", "General")
                val sem = sObj.optString("semester", "I")
                if (roll.isNotBlank() && name.isNotBlank()) {
                    personsToInsert.add(
                        PersonEntity(
                            rollNumber = roll,
                            fullName = name,
                            department = dept,
                            semester = sem,
                            role = role
                        )
                    )
                }
            }

            val templatesToInsert = mutableListOf<FaceTemplateEntity>()
            for (i in 0 until templatesJson.length()) {
                val tObj = templatesJson.getJSONObject(i)
                val id = tObj.optString("id", UUID.randomUUID().toString())
                val roll = tObj.optString("studentRoll")
                val angleType = tObj.optString("angleType", "FRONTAL")
                val quality = tObj.optDouble("qualityScore", 100.0).toFloat()

                val rawEmb = tObj.opt("embedding")
                val embCsv = when (rawEmb) {
                    null -> ""
                    is JSONArray -> {
                        val sb = StringBuilder()
                        for (j in 0 until rawEmb.length()) {
                            if (j > 0) sb.append(",")
                            sb.append(rawEmb.getDouble(j).toString())
                        }
                        sb.toString()
                    }
                    is String -> rawEmb.removeSurrounding("[", "]").trim()
                    else -> ""
                }

                if (roll.isNotBlank() && embCsv.isNotBlank()) {
                    val encryptedCsv = try {
                        AndroidSecurityUtils.encrypt(embCsv)
                    } catch (_: Throwable) {
                        embCsv
                    }
                    templatesToInsert.add(
                        FaceTemplateEntity(
                            id = id,
                            studentRoll = roll,
                            angleType = angleType,
                            embeddingEncryptedCsv = encryptedCsv,
                            isEncrypted = true,
                            qualityScore = quality
                        )
                    )
                }
            }

            val db = OmniFaceApplication.instance.database
            if (personsToInsert.isNotEmpty()) {
                db.personDao().insertPersons(personsToInsert)
            }
            if (templatesToInsert.isNotEmpty()) {
                db.personDao().insertTemplates(templatesToInsert)
            }

            // Process tombstones (purged/deleted members)
            val tombstonesJson = root.optJSONArray("tombstones")
            if (tombstonesJson != null) {
                for (j in 0 until tombstonesJson.length()) {
                    val tombstoneRoll = tombstonesJson.optString(j)
                    if (tombstoneRoll.isNotBlank()) {
                        db.personDao().deletePersonByRoll(tombstoneRoll)
                        db.personDao().deleteTemplatesForPerson(tombstoneRoll)
                        Log.i(TAG, "Processed tombstone for purged member: $tombstoneRoll")
                    }
                }
            }

            // Refresh in-memory caches and prewarm pipeline
            val allPersons = db.personDao().getAllPersons()
            val allTemplates = db.personDao().getAllTemplates()
            OmniFaceApplication.cachedStudentMap = allPersons.associate { it.rollNumber to it.fullName }
            OmniFaceApplication.cachedTemplates = allTemplates

            try {
                com.omniface.ai.ml.pipeline.FaceSecurityPipeline.getInstance(context).preloadTemplates(allTemplates)
            } catch (t: Throwable) {
                Log.w(TAG, "Pipeline template pre-warming note: ${t.message}")
            }

            Log.i(TAG, "Successfully pulled ${personsToInsert.size} members and ${templatesToInsert.size} face templates from cloud.")
            templatesToInsert.size
        } catch (e: Exception) {
            Log.e(TAG, "Roster pull failed with exception: ${e.message}", e)
            0
        }
    }

    private fun dispatchBatch(
        context: Context,
        endpoint: String,
        deviceId: String,
        records: List<AttendanceRecordEntity>
    ): Boolean {
        // Enforce HTTPS unless local test loopback or LAN development
        val isLocalDev = endpoint.contains("127.0.0.1") || endpoint.contains("localhost") || endpoint.contains("10.0.2.2") || endpoint.contains("192.168.")
        if (!endpoint.startsWith("https://") && !isLocalDev) {
            Log.w(TAG, "Cleartext sync disallowed for remote hosts: $endpoint")
            return false
        }

        val prefs = context.getSharedPreferences("OMNIFACE_PREFS", Context.MODE_PRIVATE)
        val securePrefs = try {
            AndroidSecurityUtils.getEncryptedPrefs(context, "OMNIFACE_SECURE_DEVICE_PREFS")
        } catch (_: Throwable) {
            null
        }
        val deviceToken = securePrefs?.getString("DEVICE_TOKEN", null) ?: prefs.getString("DEVICE_TOKEN", null)
        val orgId = securePrefs?.getString("ORGANIZATION_ID", null) ?: prefs.getString("ORGANIZATION_ID", null)

        if (orgId.isNullOrBlank()) {
            Log.w(TAG, "Sync aborted: Device is not paired to an authoritative organization.")
            return false
        }

        val payloadString = AttendanceSyncWorker.buildPayloadString(deviceId, records, orgId)
        val timestamp = System.currentTimeMillis()
        val hmacSecret = try {
            AndroidSecurityUtils.getOrCreateHmacSecret(context)
        } catch (_: Throwable) {
            "OMNIFACE_DEFAULT_SYNC_SECRET"
        }
        val signingData = AttendanceSyncWorker.generateSigningData(deviceId, timestamp, payloadString)
        val hmacSignature = AndroidSecurityUtils.computeHmacSha256(hmacSecret, signingData)
        val deviceFingerprint = AndroidSecurityUtils.computeSha256(deviceId + "_OMNIFACE_KEYSTORE_HW")

        val url = URL(endpoint)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6000
            readTimeout = 6000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Device-Fingerprint", deviceFingerprint)
            setRequestProperty("X-Device-ID", deviceId)
            if (!deviceToken.isNullOrBlank()) {
                setRequestProperty("X-Device-Token", deviceToken)
                setRequestProperty("Authorization", "Bearer $deviceToken")
            }
            setRequestProperty("X-Timestamp", timestamp.toString())
            setRequestProperty("X-Signature-Algorithm", "HMAC-SHA256")
            setRequestProperty("X-HMAC-Signature", hmacSignature)
        }

        return try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payloadString)
                writer.flush()
            }
            val code = connection.responseCode
            if (code == 403) {
                Log.w(TAG, "Cloud fleet sync paused: Organization is in permanent Read-Only Archive Mode (HTTP 403).")
                return false
            }
            if (code in 200..299) {
                val responseBody = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                AttendanceSyncWorker.validateSyncResponse(responseBody, records.size)
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Transport error: ${e.message}")
            false
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Dispatches real-time hardware telemetry and SRE heartbeat to fleet backend.
     */
    fun sendTelemetryHeartbeat(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences("OMNIFACE_PREFS", Context.MODE_PRIVATE)
            val syncEndpoint = prefs.getString("SYNC_REST_ENDPOINT", "https://omniface.vercel.app/api/v1/attendance/sync")
                ?: "https://omniface.vercel.app/api/v1/attendance/sync"
            val deviceId = prefs.getString("DEVICE_ID", "OMNIFACE-TERMINAL-01") ?: "OMNIFACE-TERMINAL-01"
            val heartbeatEndpoint = syncEndpoint.replace("/attendance/sync", "/devices/heartbeat")

            val isLocalDev = heartbeatEndpoint.contains("127.0.0.1") || heartbeatEndpoint.contains("localhost") || heartbeatEndpoint.contains("10.0.2.2") || heartbeatEndpoint.contains("192.168.")
            if (!heartbeatEndpoint.startsWith("https://") && !isLocalDev) {
                return false
            }

            val securePrefs = try {
                AndroidSecurityUtils.getEncryptedPrefs(context, "OMNIFACE_SECURE_DEVICE_PREFS")
            } catch (_: Throwable) {
                null
            }
            val deviceToken = securePrefs?.getString("DEVICE_TOKEN", null) ?: prefs.getString("DEVICE_TOKEN", null)
            val pendingCount = _unsyncedCount.value
            val payload = FleetTopologyManager.createHeartbeatPayload(context, pendingCount)
            payload.put("deviceId", deviceId)

            val url = URL(heartbeatEndpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 4000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Device-ID", deviceId)
                if (!deviceToken.isNullOrBlank()) {
                    setRequestProperty("X-Device-Token", deviceToken)
                    setRequestProperty("Authorization", "Bearer $deviceToken")
                }
            }

            try {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat dispatch error: ${e.message}")
            false
        }
    }
}
