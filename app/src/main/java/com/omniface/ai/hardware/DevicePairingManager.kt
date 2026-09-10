package com.omniface.ai.hardware

import android.content.Context
import android.os.Build
import android.util.Log
import com.omniface.ai.security.AndroidSecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class PairedDeviceInfo(
    val isPaired: Boolean,
    val deviceId: String,
    val deviceName: String,
    val organizationId: String,
    val deviceToken: String?,
    val pairedAt: Long
)

sealed interface PairingResult {
    data class Success(val deviceId: String, val deviceName: String, val organizationId: String) : PairingResult
    data class Failure(val message: String) : PairingResult
}

object DevicePairingManager {
    private const val TAG = "DevicePairingManager"
    private const val PREFS_NAME = "OMNIFACE_PREFS"

    private const val KEY_IS_PAIRED = "IS_PAIRED"
    private const val KEY_DEVICE_ID = "DEVICE_ID"
    private const val KEY_DEVICE_NAME = "DEVICE_NAME"
    private const val KEY_DEVICE_TOKEN = "DEVICE_TOKEN"
    private const val KEY_ORG_ID = "ORGANIZATION_ID"
    private const val KEY_PAIRED_AT = "DEVICE_PAIRED_AT"
    private const val KEY_SYNC_ENDPOINT = "SYNC_REST_ENDPOINT"

    private val _pairingState = MutableStateFlow<PairedDeviceInfo?>(null)
    val pairingState: StateFlow<PairedDeviceInfo?> = _pairingState.asStateFlow()

    fun initialize(context: Context) {
        _pairingState.value = getPairedDeviceInfo(context)
    }

    fun isDevicePaired(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_IS_PAIRED, false) && !prefs.getString(KEY_DEVICE_TOKEN, null).isNullOrBlank()
    }

    fun getPairedDeviceInfo(context: Context): PairedDeviceInfo {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isPaired = prefs.getBoolean(KEY_IS_PAIRED, false)
        val deviceId = prefs.getString(KEY_DEVICE_ID, "OMNIFACE-TERMINAL-01") ?: "OMNIFACE-TERMINAL-01"
        val deviceName = prefs.getString(KEY_DEVICE_NAME, "Android Attendance Kiosk") ?: "Android Attendance Kiosk"
        val orgId = prefs.getString(KEY_ORG_ID, "default-org") ?: "default-org"
        val deviceToken = prefs.getString(KEY_DEVICE_TOKEN, null)
        val pairedAt = prefs.getLong(KEY_PAIRED_AT, 0L)

        return PairedDeviceInfo(
            isPaired = isPaired,
            deviceId = deviceId,
            deviceName = deviceName,
            organizationId = orgId,
            deviceToken = deviceToken,
            pairedAt = pairedAt
        )
    }

    suspend fun pairWithCode(
        context: Context,
        pairingCode: String,
        customDeviceName: String? = null
    ): PairingResult = withContext(Dispatchers.IO) {
        val code = pairingCode.trim()
        if (code.length != 6) {
            return@withContext PairingResult.Failure("Pairing code must be exactly 6 digits.")
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val syncEndpoint = prefs.getString(KEY_SYNC_ENDPOINT, "https://omniface.vercel.app/api/v1/attendance/sync")
            ?: "https://omniface.vercel.app/api/v1/attendance/sync"

        // Derive pair endpoint from sync endpoint
        val pairEndpoint = if (syncEndpoint.contains("/attendance/sync")) {
            syncEndpoint.replace("/attendance/sync", "/devices/pair")
        } else {
            "https://omniface.vercel.app/api/v1/devices/pair"
        }

        val currentDeviceId = prefs.getString(KEY_DEVICE_ID, null) ?: "OMNIFACE-${Build.MODEL.take(6).uppercase()}-${System.currentTimeMillis() % 10000}"
        val deviceName = customDeviceName ?: "${Build.MANUFACTURER} ${Build.MODEL} (${currentDeviceId.takeLast(4)})"
        val hardwareHash = try {
            AndroidSecurityUtils.computeSha256("${currentDeviceId}_${Build.FINGERPRINT}")
        } catch (_: Exception) {
            "hw_${currentDeviceId.hashCode()}"
        }

        val payload = JSONObject().apply {
            put("pairingCode", code)
            put("deviceIdentifier", currentDeviceId)
            put("deviceName", deviceName)
            put("hardwareHash", hardwareHash)
            put("appVersion", "v2.0.0")
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        }

        val url = URL(pairEndpoint)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }

        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            val responseText = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                val err = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                Log.w(TAG, "Pair HTTP error: $responseCode, response: $err")
                val json = try { JSONObject(err) } catch (_: Exception) { null }
                val errMsg = json?.optString("error") ?: "Server returned HTTP $responseCode"
                return@withContext PairingResult.Failure(errMsg)
            }

            val jsonResponse = JSONObject(responseText)
            val success = jsonResponse.optBoolean("success", false)

            if (success) {
                val token = jsonResponse.optString("deviceToken", "")
                val deviceId = jsonResponse.optString("deviceId", currentDeviceId)
                val assignedName = jsonResponse.optString("deviceName", deviceName)
                val orgId = jsonResponse.optString("organizationId", "default-org")
                val now = System.currentTimeMillis()

                prefs.edit().apply {
                    putBoolean(KEY_IS_PAIRED, true)
                    putString(KEY_DEVICE_ID, deviceId)
                    putString(KEY_DEVICE_NAME, assignedName)
                    putString(KEY_DEVICE_TOKEN, token)
                    putString(KEY_ORG_ID, orgId)
                    putLong(KEY_PAIRED_AT, now)
                    apply()
                }

                val newInfo = PairedDeviceInfo(
                    isPaired = true,
                    deviceId = deviceId,
                    deviceName = assignedName,
                    organizationId = orgId,
                    deviceToken = token,
                    pairedAt = now
                )
                _pairingState.value = newInfo
                Log.i(TAG, "🎉 Device paired successfully: $deviceId -> $orgId")
                PairingResult.Success(deviceId, assignedName, orgId)
            } else {
                val errMsg = jsonResponse.optString("error", "Pairing rejected by server.")
                PairingResult.Failure(errMsg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pairing network failure: ${e.message}", e)
            PairingResult.Failure("Connection failed: ${e.localizedMessage ?: "Unknown error"}")
        } finally {
            connection.disconnect()
        }
    }

    fun unpairDevice(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_IS_PAIRED, false)
            remove(KEY_DEVICE_TOKEN)
            apply()
        }
        _pairingState.value = getPairedDeviceInfo(context)
        Log.i(TAG, "Device unpaired from cloud dashboard.")
    }
}
