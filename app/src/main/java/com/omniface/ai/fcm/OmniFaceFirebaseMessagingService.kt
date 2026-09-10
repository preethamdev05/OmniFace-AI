@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.omniface.ai.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.omniface.ai.R
import com.omniface.ai.billing.PlayBillingManager
import com.omniface.ai.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class OmniFaceFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "🔥 New FCM Registration Token received: $token")

        // Persist token locally
        saveFcmTokenLocally(token)

        // Register token with Next.js multi-tenant backend
        registerTokenWithBackend(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.i(TAG, "📩 FCM Message received from: ${remoteMessage.from}")

        val data = remoteMessage.data
        if (data.isNotEmpty()) {
            Log.d(TAG, "Payload Data: $data")
            handleDataPayload(data)
        }

        remoteMessage.notification?.let { notification ->
            Log.d(TAG, "Notification Title: ${notification.title}, Body: ${notification.body}")
            showNotification(notification.title ?: "OmniFace Alert", notification.body ?: "")
        }
    }

    private fun handleDataPayload(data: Map<String, String>) {
        val action = data["action"] ?: return
        when (action) {
            "SUBSCRIPTION_UPDATED" -> {
                Log.i(TAG, "FCM signal: Refreshing subscription entitlements from backend")
                PlayBillingManager.pullSubscriptionFromBackend()
            }
            "SYNC_FORCE" -> {
                Log.i(TAG, "FCM signal: Force attendance synchronization requested")
                try {
                    com.omniface.ai.OmniFaceApplication.instance.schedulePeriodicSync()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to schedule sync: ${e.message}")
                }
            }
            "KIOSK_LOCKED" -> {
                Log.w(TAG, "FCM signal: Remote Kiosk Lock triggered by Administrator")
                try {
                    com.omniface.ai.hardware.KioskLockController.triggerRemoteLockdown(this)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to lock kiosk: ${e.message}")
                }
            }
            else -> {
                Log.d(TAG, "Unknown action received: $action")
            }
        }
    }

    private fun showNotification(title: String, message: String) {
        val channelId = "omniface_alerts_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "OmniFace SaaS Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Institutional alerts, subscription status, and sync notifications"
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun saveFcmTokenLocally(token: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
    }

    private fun registerTokenWithBackend(token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = getSharedPreferences("OMNIFACE_PREFS", Context.MODE_PRIVATE)
                val baseEndpoint = prefs.getString("SYNC_REST_ENDPOINT", "https://omniface.vercel.app/api/v1/attendance/sync")
                    ?: "https://omniface.vercel.app/api/v1/attendance/sync"
                val deviceId = prefs.getString("DEVICE_ID", "OMNIFACE-TERMINAL-01") ?: "OMNIFACE-TERMINAL-01"

                val baseUrl = if (baseEndpoint.contains("/api/v1")) {
                    baseEndpoint.substringBefore("/api/v1")
                } else {
                    "https://omniface.vercel.app"
                }

                val fcmUrl = URL("$baseUrl/api/v1/notifications/fcm-token")
                val conn = (fcmUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }

                val payload = JSONObject().apply {
                    put("fcmToken", token)
                    put("platform", "android")
                    put("deviceId", deviceId)
                }

                conn.outputStream.use { os ->
                    OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                        writer.write(payload.toString())
                        writer.flush()
                    }
                }

                val code = conn.responseCode
                Log.i(TAG, "Backend FCM token registration status: $code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register FCM token with backend: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "OmniFaceFCM"
        private const val PREFS_NAME = "omniface_fcm_vault"
        private const val KEY_FCM_TOKEN = "registered_fcm_token"

        fun getCachedToken(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_FCM_TOKEN, null)
        }
    }
}
