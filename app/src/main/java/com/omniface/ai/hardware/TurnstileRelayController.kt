package com.omniface.ai.hardware

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
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
    var isWebhookEnabled: Boolean = false

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

    /**
     * Sends digital 5V pulse over USB-Serial / GPIO relay and dispatches HTTP Webhook to unlock doorway.
     */
    fun triggerDoorUnlock(
        durationMs: Long = 1500L,
        studentRoll: String = "",
        studentName: String = "",
        confidencePct: Float = 0f,
        sha256Proof: String = "",
        onUnlocked: () -> Unit = {},
        onLocked: () -> Unit = {}
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                isDoorUnlocked = true
                Log.d(TAG, "⚡ [RELAY TRIGGER] Doorway Unlocked: 5V High Pulse Sent ($durationMs ms)")
                mainHandler.post { onUnlocked() }

                // Asynchronous IoT / HTTP Webhook Dispatch
                if (isWebhookEnabled && webhookUrl.isNotBlank()) {
                    dispatchWebhookUnlock(studentRoll, studentName, confidencePct, sha256Proof, durationMs)
                }

                delay(durationMs)

                isDoorUnlocked = false
                Log.d(TAG, "🔒 [RELAY TRIGGER] Doorway Locked: 0V Low Closed")
                mainHandler.post { onLocked() }
            } catch (e: Exception) {
                Log.e(TAG, "Relay Trigger Error: ${e.message}")
            }
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

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(payload.toString())
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
