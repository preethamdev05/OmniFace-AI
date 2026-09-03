package com.omniface.ai.hardware

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.omniface.ai.security.AndroidSecurityUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object TurnstileRelayController {
    private const val TAG = "TurnstileRelay"
    private val mainHandler = Handler(Looper.getMainLooper())

    var isRelayConnected: Boolean = false
        private set

    var isDoorUnlocked: Boolean = false
        private set

    var webhookUrl: String = ""
    var gateId: String = "GATE_ALPHA_01"
    var bearerToken: String = ""

    /**
     * Per-device HMAC secret for signing webhook payloads.
     * Loaded lazily from EncryptedSharedPreferences on first access via initWithContext().
     * Never falls back to the old hardcoded literal string.
     */
    private var _hmacSecret: String = ""
    val hmacSecret: String get() = _hmacSecret

    var isWebhookEnabled: Boolean = false

    /**
     * Must be called once from OmniFaceApplication.onCreate() (or before first relay use)
     * to load (or generate) the per-device HMAC secret from EncryptedSharedPreferences.
     */
    fun initWithContext(context: Context) {
        if (_hmacSecret.isBlank()) {
            _hmacSecret = AndroidSecurityUtils.getOrCreateHmacSecret(context)
        }
    }

    fun checkUsbRelayAttached(context: Context): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val deviceList: HashMap<String, UsbDevice> = usbManager.deviceList
        for ((_, device) in deviceList) {
            val vid = device.vendorId
            if (vid == 0x0403 || vid == 0x1A86 || vid == 0x10C4 || vid == 0x067B || vid == 0x2341) {
                isRelayConnected = true
                return true
            }
        }
        isRelayConnected = false
        return false
    }

    private val LCUS_RELAY_OPEN = byteArrayOf(0xA0.toByte(), 0x01.toByte(), 0x01.toByte(), 0xA2.toByte())
    private val LCUS_RELAY_CLOSE = byteArrayOf(0xA0.toByte(), 0x01.toByte(), 0x00.toByte(), 0xA1.toByte())

    fun sendUsbRelayCommand(context: Context, isEnergized: Boolean) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val deviceList = usbManager.deviceList
        for ((_, device) in deviceList) {
            val vid = device.vendorId
            if (vid == 0x0403 || vid == 0x1A86 || vid == 0x10C4 || vid == 0x067B || vid == 0x2341) {
                try {
                    val connection = usbManager.openDevice(device) ?: continue
                    val iface = device.getInterface(0)
                    connection.claimInterface(iface, true)

                    if (iface.endpointCount > 0) {
                        val endpoint = iface.getEndpoint(0)
                        val command = if (isEnergized) LCUS_RELAY_OPEN else LCUS_RELAY_CLOSE
                        connection.bulkTransfer(endpoint, command, command.size, 1000)
                    }

                    connection.releaseInterface(iface)
                    connection.close()
                    Log.i(TAG, "Sent binary relay command to USB device [VID: 0x${Integer.toHexString(vid)}]: ${if (isEnergized) "ENERGIZED" else "DE-ENERGIZED"}")
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "USB relay transmission error: ${e.message}")
                }
            }
        }
    }

    /**
     * Sends digital 5V pulse over USB-Serial / GPIO relay and dispatches HTTP Webhook to unlock doorway.
     */
    fun triggerDoorUnlock(
        durationMs: Long = 1500L,
        studentRoll: String = "",
        studentName: String = "",
        confidencePct: Float = 0f,
        sha256Proof: String = "",
        context: Context? = null,
        onUnlocked: () -> Unit = {},
        onLocked: () -> Unit = {}
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                isDoorUnlocked = true
                Log.d(TAG, "⚡ [RELAY TRIGGER] Doorway Unlocked: 5V High Pulse Sent ($durationMs ms)")
                context?.let { sendUsbRelayCommand(it, true) }
                mainHandler.post { onUnlocked() }

                // Asynchronous IoT / HTTP Webhook Dispatch
                if (isWebhookEnabled && webhookUrl.isNotBlank()) {
                    dispatchWebhookUnlock(studentRoll, studentName, confidencePct, sha256Proof, durationMs)
                }

                delay(durationMs)

                isDoorUnlocked = false
                Log.d(TAG, "🔒 [RELAY TRIGGER] Doorway Locked: 0V Low Closed")
                context?.let { sendUsbRelayCommand(it, false) }
                mainHandler.post { onLocked() }
            } catch (e: Exception) {
                Log.e(TAG, "Relay Trigger Error: ${e.message}")
            }
        }
    }

    private fun computeHmacSha256(data: String, secret: String): String {
        return try {
            val algorithm = "HmacSHA256"
            val key = javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), algorithm)
            val mac = javax.crypto.Mac.getInstance(algorithm)
            mac.init(key)
            val hmacBytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
            hmacBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        } catch (e: Throwable) {
            Log.e(TAG, "HMAC computation failed — refusing downgrade")
            throw IllegalStateException("HMAC unavailable", e)
        }
    }

    suspend fun testWebhookConnection(): Pair<Boolean, String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (webhookUrl.isBlank()) return@withContext Pair(false, "Webhook URL is not configured")
        try {
            val url = URL(webhookUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 4000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (bearerToken.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $bearerToken")
                }
            }

            val payload = JSONObject().apply {
                put("event", "PING_TEST")
                put("gateId", gateId)
                put("timestamp", System.currentTimeMillis())
                put("app", "OmniFace AI Turnstile Access Control")
            }
            val payloadStr = payload.toString()
            conn.setRequestProperty("X-OmniFace-Signature", computeHmacSha256(payloadStr, hmacSecret))

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(payloadStr)
                writer.flush()
            }

            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) {
                Pair(true, "HTTP $code: Connection Successful")
            } else {
                Pair(false, "HTTP $code: Server responded with error")
            }
        } catch (e: Exception) {
            Pair(false, "Connection Failed: ${e.localizedMessage ?: e.message}")
        }
    }

    private fun dispatchWebhookUnlock(
        studentRoll: String,
        studentName: String,
        confidencePct: Float,
        sha256Proof: String,
        durationMs: Long
    ) {
        try {
            val url = URL(webhookUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 3000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (bearerToken.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $bearerToken")
                }
            }

            val payload = JSONObject().apply {
                put("event", "DOOR_UNLOCK_TRIGGERED")
                put("gateId", gateId)
                put("studentRoll", studentRoll)
                put("studentName", studentName)
                put("confidencePct", confidencePct)
                put("sha256Proof", sha256Proof)
                put("pulseDurationMs", durationMs)
                put("timestamp", System.currentTimeMillis())
            }

            val payloadStr = payload.toString()
            conn.setRequestProperty("X-OmniFace-Signature", computeHmacSha256(payloadStr, hmacSecret))

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(payloadStr)
                writer.flush()
            }

            val responseCode = conn.responseCode
            Log.d(TAG, "🌐 [WEBHOOK] Response: $responseCode from $webhookUrl")
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Webhook dispatch failed: ${e.message}")
        }
    }
}
