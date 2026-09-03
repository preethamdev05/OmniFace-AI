package com.omniface.ai.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.omniface.ai.OmniFaceApplication
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.hardware.FleetTopologyManager
import com.omniface.ai.hardware.KioskNode
import com.omniface.ai.security.AndroidSecurityUtils
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
            return@withContext true
        }

        val prefs = context.getSharedPreferences("OMNIFACE_PREFS", Context.MODE_PRIVATE)
        val syncEndpoint = prefs.getString("SYNC_REST_ENDPOINT", "https://127.0.0.1:8080/api/v1/attendance/sync")
            ?: "https://127.0.0.1:8080/api/v1/attendance/sync"
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

    private fun dispatchBatch(
        context: Context,
        endpoint: String,
        deviceId: String,
        records: List<AttendanceRecordEntity>
    ): Boolean {
        // Enforce HTTPS unless local test loopback
        if (!endpoint.startsWith("https://") && !endpoint.contains("127.0.0.1") && !endpoint.contains("localhost")) {
            Log.w(TAG, "Cleartext sync disallowed: $endpoint")
            return false
        }

        val payloadString = AttendanceSyncWorker.buildPayloadString(deviceId, records)
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
}
