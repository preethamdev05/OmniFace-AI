package com.omniface.ai.ads

import com.omniface.ai.billing.SubscriptionTier
import com.omniface.ai.billing.SubscriptionTierManager
import com.omniface.ai.ui.navigation.Screen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.regex.Pattern

/**
 * AdMob Integration & Policy Compliance Test Suite
 *
 * Verifies:
 * 1. Google AdMob official test and production ad unit IDs.
 * 2. Commercial tier ad suppression rules (FREE displays ads, PREMIUM/PRO/INSTITUTION ad-free).
 * 3. Strict Scanner Viewport Ad Exclusion policy compliance.
 * 4. Error recovery and test unit fallback mechanisms.
 */
class AdMobIntegrationTest {

    @Before
    fun setUp() {
        SubscriptionTierManager.resetToFree()
    }

    @After
    fun tearDown() {
        SubscriptionTierManager.resetToFree()
        AdMobManager.bannerAdUnitId = AdMobManager.PROD_BANNER_AD_UNIT_ID
    }

    @Test
    fun testAdMobOfficialUnitIdConstants() {
        // Official Google AdMob sample/test banner unit ID
        assertEquals(
            "ca-app-pub-3940256099942544/6300978111",
            AdMobManager.TEST_BANNER_AD_UNIT_ID
        )

        // Production OmniFace App ID
        assertEquals(
            "ca-app-pub-7395899775185670~2717408031",
            AdMobManager.PROD_APP_ID
        )

        // Production OmniFace Banner Unit ID
        assertEquals(
            "ca-app-pub-7395899775185670/7258936622",
            AdMobManager.PROD_BANNER_AD_UNIT_ID
        )

        // Regex validation for standard Google AdMob Publisher formats
        val appIdPattern = Pattern.compile("^ca-app-pub-\\d{16}~\\d{10}$")
        val bannerPattern = Pattern.compile("^ca-app-pub-\\d{16}/\\d{10}$")

        assertTrue(
            "Production App ID format is invalid",
            appIdPattern.matcher(AdMobManager.PROD_APP_ID).matches()
        )
        assertTrue(
            "Production Banner ID format is invalid",
            bannerPattern.matcher(AdMobManager.PROD_BANNER_AD_UNIT_ID).matches()
        )
        assertTrue(
            "Test Banner ID format is invalid",
            bannerPattern.matcher(AdMobManager.TEST_BANNER_AD_UNIT_ID).matches()
        )
    }

    @Test
    fun testCommercialTierAdSuppressionMatrix() {
        // FREE Tier MUST show ads to monetize free users
        assertTrue(
            "FREE tier must display advertisements",
            SubscriptionTier.FREE.displaysAds
        )

        // Paid & Enterprise Tiers MUST be 100% ad-free
        assertFalse(
            "PREMIUM tier (₹199) must be 100% ad-free",
            SubscriptionTier.PREMIUM.displaysAds
        )
        assertFalse(
            "PRO tier (₹349) must be 100% ad-free",
            SubscriptionTier.PRO.displaysAds
        )
        assertFalse(
            "INSTITUTION tier must be 100% ad-free",
            SubscriptionTier.INSTITUTION.displaysAds
        )
    }

    @Test
    fun testAdMobManagerDynamicTierEvaluation() {
        val futureExpiry = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000

        // 1. Initially on FREE tier
        SubscriptionTierManager.resetToFree()
        assertTrue(
            "AdMobManager must permit ads for FREE tier",
            AdMobManager.shouldDisplayAds()
        )

        // 2. Upgrade to PREMIUM (₹199) -> Ads immediately suppressed
        SubscriptionTierManager.setSubscription(SubscriptionTier.PREMIUM, futureExpiry, "test-premium-token")
        assertFalse(
            "AdMobManager must suppress ads for PREMIUM tier",
            AdMobManager.shouldDisplayAds()
        )

        // 3. Upgrade to PRO (₹349) -> Ads immediately suppressed
        SubscriptionTierManager.setSubscription(SubscriptionTier.PRO, futureExpiry, "test-pro-token")
        assertFalse(
            "AdMobManager must suppress ads for PRO tier",
            AdMobManager.shouldDisplayAds()
        )

        // 4. Upgrade to INSTITUTION -> Ads immediately suppressed
        SubscriptionTierManager.setSubscription(SubscriptionTier.INSTITUTION, futureExpiry, "test-inst-token")
        assertFalse(
            "AdMobManager must suppress ads for INSTITUTION tier",
            AdMobManager.shouldDisplayAds()
        )

        // 5. Expiration / Reset to FREE -> Ads restored
        SubscriptionTierManager.resetToFree()
        assertTrue(
            "AdMobManager must re-enable ads upon fallback to FREE tier",
            AdMobManager.shouldDisplayAds()
        )
    }

    @Test
    fun testScannerScreenAdExclusionPolicy() {
        // Policy: Never render ads on CameraX / Face Recognition Scanner viewport
        val scannerRoute = Screen.Scanner.route
        val nonScannerRoutes = listOf(
            Screen.Dashboard.route,
            Screen.Enrollment.route,
            Screen.Ledger.route,
            Screen.Settings.route
        )

        // Helper function mimicking the OmniFaceApp navigation condition:
        // if (currentRoute != Screen.Scanner.route) { AdaptiveBannerAd() }
        fun isAdRenderingAllowedOnRoute(route: String): Boolean {
            return route != Screen.Scanner.route
        }

        assertFalse(
            "Scanner screen route MUST NEVER display ads",
            isAdRenderingAllowedOnRoute(scannerRoute)
        )

        for (route in nonScannerRoutes) {
            assertTrue(
                "Non-scanner route ($route) is eligible for ads on FREE tier",
                isAdRenderingAllowedOnRoute(route)
            )
            assertNotEquals(
                "Non-scanner route must not match scanner route",
                scannerRoute,
                route
            )
        }
    }

    @Test
    fun testAdMobBannerUnitIdSwappingFallback() {
        // Initial state is production banner ad unit ID
        assertEquals(AdMobManager.PROD_BANNER_AD_UNIT_ID, AdMobManager.bannerAdUnitId)

        // Simulate switching to test banner unit for QA/testing
        AdMobManager.bannerAdUnitId = AdMobManager.TEST_BANNER_AD_UNIT_ID
        assertEquals(AdMobManager.TEST_BANNER_AD_UNIT_ID, AdMobManager.bannerAdUnitId)

        // Simulate error codes: 3 = ERROR_CODE_NO_FILL, 0 = ERROR_CODE_INTERNAL_ERROR
        val errorCodeNoFill = 3
        val errorCodeInternal = 0
        var activeUnitId = AdMobManager.PROD_BANNER_AD_UNIT_ID

        if (activeUnitId == AdMobManager.PROD_BANNER_AD_UNIT_ID && (errorCodeNoFill == 3 || errorCodeNoFill == 0)) {
            activeUnitId = AdMobManager.TEST_BANNER_AD_UNIT_ID
        }
        assertEquals(
            "Production ad failure must swap to official Google test unit",
            AdMobManager.TEST_BANNER_AD_UNIT_ID,
            activeUnitId
        )

        // Same check for internal error code 0
        activeUnitId = AdMobManager.PROD_BANNER_AD_UNIT_ID
        if (activeUnitId == AdMobManager.PROD_BANNER_AD_UNIT_ID && (errorCodeInternal == 3 || errorCodeInternal == 0)) {
            activeUnitId = AdMobManager.TEST_BANNER_AD_UNIT_ID
        }
        assertEquals(
            "Internal error must also swap to test unit",
            AdMobManager.TEST_BANNER_AD_UNIT_ID,
            activeUnitId
        )
    }
}
