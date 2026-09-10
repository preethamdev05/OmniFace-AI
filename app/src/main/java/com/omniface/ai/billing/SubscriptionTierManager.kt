package com.omniface.ai.billing

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

enum class SubscriptionTier(
    val title: String,
    val maxStudents: Int,
    val priceInrMonthly: Int,
    val allowsReportExports: Boolean,
    val allowsCloudSync: Boolean,
    val allowsMultiDevice: Boolean,
    val displaysAds: Boolean,
    val hasWebDashboard: Boolean
) {
    FREE(
        title = "Free Starter",
        maxStudents = 25,
        priceInrMonthly = 0,
        allowsReportExports = false,
        allowsCloudSync = false,
        allowsMultiDevice = false,
        displaysAds = true,
        hasWebDashboard = false
    ),
    PREMIUM(
        title = "Premium Pro",
        maxStudents = 250,
        priceInrMonthly = 199,
        allowsReportExports = true,
        allowsCloudSync = true,
        allowsMultiDevice = true,
        displaysAds = false,
        hasWebDashboard = false
    ),
    BUSINESS(
        title = "Enterprise Business",
        maxStudents = Int.MAX_VALUE,
        priceInrMonthly = 999,
        allowsReportExports = true,
        allowsCloudSync = true,
        allowsMultiDevice = true,
        displaysAds = false,
        hasWebDashboard = true
    )
}

enum class PaywallTriggerReason(val headline: String, val description: String) {
    STUDENT_LIMIT_REACHED(
        headline = "Free Plan Limit Reached",
        description = "You have reached the 25-person Free Plan limit. Upgrade to Premium to enroll up to 250 students/employees."
    ),
    EXCEL_PDF_EXPORT_LOCKED(
        headline = "Unlock Excel & PDF Reports",
        description = "Export comprehensive attendance audit spreadsheets and printable PDF registers with Premium (₹199/mo)."
    ),
    CLOUD_SYNC_LOCKED(
        headline = "Unlock Cloud & Multi-Device Sync",
        description = "Seamlessly synchronize student face embeddings and attendance across multiple kiosks with Premium."
    ),
    MULTI_DEVICE_LOCKED(
        headline = "Multi-Kiosk Fleet Access",
        description = "Manage distributed attendance points with real-time multi-device replication on Premium."
    ),
    ADS_REMOVAL(
        headline = "Remove Advertisements",
        description = "Enjoy a 100% ad-free experience across Dashboard, Reports, and Settings with Premium."
    )
}

object SubscriptionTierManager {
    private const val TAG = "SubscriptionTierManager"
    private const val PREFS_NAME = "omniface_subscription_vault"
    private const val KEY_TIER = "active_subscription_tier"
    private const val KEY_EXPIRY_MS = "subscription_expiry_timestamp_ms"
    private const val KEY_LAST_ONLINE_VERIFY = "last_online_verification_ms"
    private const val KEY_PURCHASE_TOKEN = "google_play_purchase_token"

    // 30 Days offline grace period for classroom/field kiosk operations
    val OFFLINE_GRACE_PERIOD_MS = TimeUnit.DAYS.toMillis(30)

    private val _currentTier = MutableStateFlow(SubscriptionTier.FREE)
    val currentTier: StateFlow<SubscriptionTier> = _currentTier.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        prefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "Falling back to standard SharedPreferences: ${e.message}")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        evaluateCurrentTier()
        PlayBillingManager.pullSubscriptionFromBackend()
    }

    fun evaluateCurrentTier(): SubscriptionTier {
        val p = prefs ?: return SubscriptionTier.FREE
        val tierName = p.getString(KEY_TIER, SubscriptionTier.FREE.name) ?: SubscriptionTier.FREE.name
        val rawTier = try {
            SubscriptionTier.valueOf(tierName)
        } catch (_: Exception) {
            SubscriptionTier.FREE
        }

        if (rawTier == SubscriptionTier.FREE) {
            _currentTier.value = SubscriptionTier.FREE
            return SubscriptionTier.FREE
        }

        val expiryMs = p.getLong(KEY_EXPIRY_MS, 0L)
        val now = System.currentTimeMillis()

        val isGraceValid = if (expiryMs > 0) {
            now <= (expiryMs + OFFLINE_GRACE_PERIOD_MS)
        } else {
            true
        }

        val resolved = if (isGraceValid) rawTier else SubscriptionTier.FREE
        _currentTier.value = resolved
        return resolved
    }

    fun canEnrollMore(currentCount: Int): Boolean {
        return currentCount < _currentTier.value.maxStudents
    }

    fun canExportReports(): Boolean {
        return _currentTier.value.allowsReportExports
    }

    fun canSyncCloud(): Boolean {
        return _currentTier.value.allowsCloudSync
    }

    fun canUseMultiDevice(): Boolean {
        return _currentTier.value.allowsMultiDevice
    }

    fun shouldDisplayAds(): Boolean {
        return _currentTier.value.displaysAds
    }

    fun isFreeTier(): Boolean {
        return _currentTier.value == SubscriptionTier.FREE
    }

    fun getMaxStudentsDisplay(): String {
        return when (_currentTier.value) {
            SubscriptionTier.FREE -> "25"
            SubscriptionTier.PREMIUM -> "250"
            SubscriptionTier.BUSINESS -> "Unlimited"
        }
    }

    fun setSubscription(tier: SubscriptionTier, expiryTimestampMs: Long, purchaseToken: String? = null) {
        prefs?.edit()?.apply {
            putString(KEY_TIER, tier.name)
            putLong(KEY_EXPIRY_MS, expiryTimestampMs)
            putLong(KEY_LAST_ONLINE_VERIFY, System.currentTimeMillis())
            if (purchaseToken != null) {
                putString(KEY_PURCHASE_TOKEN, purchaseToken)
            }
            apply()
        }
        _currentTier.value = tier
        Log.i(TAG, "🎉 Subscription updated to: ${tier.title} (expires: $expiryTimestampMs)")
    }

    fun resetToFree() {
        prefs?.edit()?.apply {
            putString(KEY_TIER, SubscriptionTier.FREE.name)
            putLong(KEY_EXPIRY_MS, 0L)
            apply()
        }
        _currentTier.value = SubscriptionTier.FREE
    }
}
