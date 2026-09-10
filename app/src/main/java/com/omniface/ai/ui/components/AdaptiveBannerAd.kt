package com.omniface.ai.ui.components

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.omniface.ai.ads.AdMobManager
import com.omniface.ai.billing.SubscriptionTierManager

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

private const val TAG = "AdaptiveBannerAd"

/**
 * Anchored Adaptive Banner Ad for Jetpack Compose.
 *
 * Strict Business Rule:
 * Only rendered when SubscriptionTierManager.shouldDisplayAds() == true (FREE plan).
 * Completely bypassed with zero overhead for PREMIUM, PRO, and INSTITUTION tiers.
 * NEVER placed on Camera / Scanner screens.
 */
@Composable
fun AdaptiveBannerAd(
    modifier: Modifier = Modifier,
    adUnitId: String = AdMobManager.bannerAdUnitId
) {
    val tier by SubscriptionTierManager.currentTier.collectAsState()
    
    // Hard entitlement gate: If the plan does not display ads, render nothing
    if (!tier.displaysAds) {
        return
    }

    var activeUnitId by remember(adUnitId) { mutableStateOf(adUnitId) }
    var isLoaded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isLoaded) Modifier.wrapContentHeight().background(Color(0xFF12141A))
                else Modifier.height(0.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        key(activeUnitId) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { ctx ->
                    AdView(ctx).apply {
                        setAdUnitId(activeUnitId)
                        val adSize = getAdaptiveAdSize(ctx)
                        setAdSize(adSize)

                        adListener = object : AdListener() {
                            override fun onAdLoaded() {
                                Log.d(TAG, "AdMob banner loaded successfully ($activeUnitId)")
                                isLoaded = true
                            }

                            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                                Log.w(TAG, "AdMob banner failed to load ($activeUnitId): ${loadAdError.message} (code: ${loadAdError.code})")
                                if (activeUnitId == AdMobManager.PROD_BANNER_AD_UNIT_ID && (loadAdError.code == 3 || loadAdError.code == 0)) {
                                    Log.i(TAG, "Prod ad unit warming up. Swapping to test unit fallback...")
                                    activeUnitId = AdMobManager.TEST_BANNER_AD_UNIT_ID
                                } else {
                                    isLoaded = false
                                }
                            }
                        }

                        val adRequest = AdRequest.Builder().build()
                        loadAd(adRequest)
                    }
                },
                update = {
                    // Banner view maintained across recompositions
                }
            )
        }
    }
}

private fun getAdaptiveAdSize(context: Context): AdSize {
    val displayMetrics = context.resources.displayMetrics
    val widthPixels = displayMetrics.widthPixels.toFloat()
    val density = displayMetrics.density
    val adWidth = (widthPixels / density).toInt()
    return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidth)
}
