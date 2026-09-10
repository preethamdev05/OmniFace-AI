package com.omniface.ai.telemetry

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

object TelemetryManager {
    private const val TAG = "TelemetryManager"

    private var analytics: FirebaseAnalytics? = null
    private var crashlytics: FirebaseCrashlytics? = null

    fun initialize(context: Context) {
        try {
            analytics = FirebaseAnalytics.getInstance(context.applicationContext)
            crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics?.setCrashlyticsCollectionEnabled(true)
            Log.i(TAG, "Firebase Telemetry & Crashlytics initialized.")
        } catch (e: Exception) {
            Log.w(TAG, "Telemetry initialization warning: ${e.message}")
        }
    }

    fun setUserId(userId: String) {
        try {
            analytics?.setUserId(userId)
            crashlytics?.setUserId(userId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set user ID: ${e.message}")
        }
    }

    fun setOrganizationId(orgId: String) {
        try {
            crashlytics?.setCustomKey("organization_id", orgId)
            analytics?.setUserProperty("organization_id", orgId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set org ID: ${e.message}")
        }
    }

    fun logAttendanceMarked(method: String, isSuccess: Boolean, latencyMs: Long) {
        try {
            val bundle = Bundle().apply {
                putString("recognition_method", method)
                putBoolean("is_success", isSuccess)
                putLong("latency_ms", latencyMs)
            }
            analytics?.logEvent("attendance_marked", bundle)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log attendance: ${e.message}")
        }
    }

    fun logSubscriptionUpgraded(tier: String, priceInr: Int) {
        try {
            val bundle = Bundle().apply {
                putString("tier", tier)
                putInt("price_inr", priceInr)
            }
            analytics?.logEvent("subscription_upgraded", bundle)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log subscription: ${e.message}")
        }
    }

    fun logSyncCompleted(recordCount: Int, success: Boolean) {
        try {
            val bundle = Bundle().apply {
                putInt("record_count", recordCount)
                putBoolean("sync_success", success)
            }
            analytics?.logEvent("attendance_sync_finished", bundle)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log sync: ${e.message}")
        }
    }

    fun recordNonFatal(throwable: Throwable, contextTag: String = "Generic") {
        try {
            crashlytics?.setCustomKey("error_context", contextTag)
            crashlytics?.recordException(throwable)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record non-fatal: ${e.message}")
        }
    }
}
