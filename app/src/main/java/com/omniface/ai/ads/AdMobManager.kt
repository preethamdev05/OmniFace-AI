package com.omniface.ai.ads

import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.omniface.ai.billing.SubscriptionTierManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

object AdMobManager {
    private const val TAG = "AdMobManager"

    // Google's official AdMob test banner ad unit ID (safe for test & development)
    const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"

    // OmniFace Production AdMob App & Banner Unit IDs
    const val PROD_APP_ID = "ca-app-pub-7395899775185670~2717408031"
    const val PROD_BANNER_AD_UNIT_ID = "ca-app-pub-7395899775185670/7258936622"

    private val isInitializing = AtomicBoolean(false)
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    @Volatile
    var bannerAdUnitId: String = PROD_BANNER_AD_UNIT_ID

    fun initialize(context: Context) {
        if (_isInitialized.value || isInitializing.getAndSet(true)) {
            return
        }

        try {
            // Configure test device policy for emulator & Xiaomi 14 physical device
            val configuration = RequestConfiguration.Builder()
                .setTestDeviceIds(listOf(
                    AdRequest.DEVICE_ID_EMULATOR,
                    "9AD5DDFBFC35D586E06A27A48AB1B8B2" // Xiaomi 14 Physical Dev Hardware
                ))
                .build()
            MobileAds.setRequestConfiguration(configuration)

            MobileAds.initialize(context.applicationContext) { initializationStatus ->
                _isInitialized.value = true
                isInitializing.set(false)
                val statusMap = initializationStatus.adapterStatusMap
                Log.i(TAG, "AdMob SDK Initialized. Adapters: ${statusMap.keys.joinToString()}")
            }
        } catch (e: Exception) {
            isInitializing.set(false)
            Log.e(TAG, "Failed to initialize AdMob SDK: ${e.message}", e)
        }
    }

    /**
     * Authoritative entitlement gate: checks if current subscription tier displays ads.
     * FREE tier: displays ads on non-scanner screens.
     * PREMIUM / PRO / INSTITUTION: 100% ad-free.
     */
    fun shouldDisplayAds(): Boolean {
        return SubscriptionTierManager.shouldDisplayAds()
    }

    /**
     * Builds a standard AdRequest
     */
    fun buildAdRequest(): AdRequest {
        return AdRequest.Builder().build()
    }
}
