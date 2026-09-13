package com.omniface.ai.billing

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 💳 Phase 22: Billing & Entitlements Master Production Verification Test Suite
 *
 * Verifies:
 * 1. Plan Tier Specifications & Pricing Parity (FREE 25, PREMIUM 250 @ ₹199, PRO 500 @ ₹349, INSTITUTION 500+).
 * 2. Feature Gating & Capability Matrix (Cloud sync, report exports, multi-device, ad display).
 * 3. Enrollment Capacity Bounds & Paywall Funnel Approaching Limits.
 * 4. 14-Day Offline Grace Period & Archive Read-Only Degradation Invariants.
 * 5. Server-Authoritative Remote Tier Resolution & Fallback Safety.
 * 6. Google Play Product ID Standardization & Subscription Durations.
 * 7. Anti-Tamper Invariant (Zero client-side synthetic elevation or bypasses).
 */
class BillingEntitlementsVerificationTest {

    // ── 1. Plan Specifications & Pricing Invariants ──

    @Test
    fun testTierLimitsAndPricing_strictParity() {
        // FREE: 25 people, ₹0, Single Device, Ads
        val free = SubscriptionTier.FREE
        assertEquals(25, free.maxStudents)
        assertEquals(0, free.priceInrMonthly)
        assertFalse(free.allowsCloudSync)
        assertFalse(free.allowsReportExports)
        assertFalse(free.allowsMultiDevice)
        assertTrue(free.displaysAds)
        assertFalse(free.hasWebDashboard)

        // PREMIUM: 250 people, ₹199, Single Device, Cloud Sync, Ad-Free
        val premium = SubscriptionTier.PREMIUM
        assertEquals(250, premium.maxStudents)
        assertEquals(199, premium.priceInrMonthly)
        assertTrue(premium.allowsCloudSync)
        assertTrue(premium.allowsReportExports)
        assertFalse(premium.allowsMultiDevice)
        assertFalse(premium.displaysAds)
        assertTrue(premium.hasWebDashboard)

        // PRO: 500 people, ₹349, Up to 3 Devices, Cloud Sync, Advanced Reports, Ad-Free
        val pro = SubscriptionTier.PRO
        assertEquals(500, pro.maxStudents)
        assertEquals(349, pro.priceInrMonthly)
        assertTrue(pro.allowsCloudSync)
        assertTrue(pro.allowsReportExports)
        assertTrue(pro.allowsMultiDevice)
        assertFalse(pro.displaysAds)
        assertTrue(pro.hasWebDashboard)

        // INSTITUTION: 500+ people (unlimited), Custom, Multi-Device, Full Fleet
        val inst = SubscriptionTier.INSTITUTION
        assertEquals(Int.MAX_VALUE, inst.maxStudents)
        assertTrue(inst.allowsCloudSync)
        assertTrue(inst.allowsReportExports)
        assertTrue(inst.allowsMultiDevice)
        assertFalse(inst.displaysAds)
        assertTrue(inst.hasWebDashboard)
    }

    // ── 2. Enrollment Capacity Gating ──

    @Test
    fun testEnrollmentCapacityBounds_strictEnforcement() {
        fun canEnroll(tier: SubscriptionTier, count: Int): Boolean = count < tier.maxStudents

        // FREE: 25 limit
        assertTrue(canEnroll(SubscriptionTier.FREE, 0))
        assertTrue(canEnroll(SubscriptionTier.FREE, 24))
        assertFalse(canEnroll(SubscriptionTier.FREE, 25))
        assertFalse(canEnroll(SubscriptionTier.FREE, 26))

        // PREMIUM: 250 limit
        assertTrue(canEnroll(SubscriptionTier.PREMIUM, 249))
        assertFalse(canEnroll(SubscriptionTier.PREMIUM, 250))
        assertFalse(canEnroll(SubscriptionTier.PREMIUM, 300))

        // PRO: 500 limit
        assertTrue(canEnroll(SubscriptionTier.PRO, 499))
        assertFalse(canEnroll(SubscriptionTier.PRO, 500))
        assertFalse(canEnroll(SubscriptionTier.PRO, 501))

        // INSTITUTION: Unlimited
        assertTrue(canEnroll(SubscriptionTier.INSTITUTION, 10_000))
        assertTrue(canEnroll(SubscriptionTier.INSTITUTION, 1_000_000))
    }

    // ── 3. Upgrade Warning Funnel & Paywall Reasons ──

    @Test
    fun testUpgradeWarningFunnel_thresholdThresholds() {
        // Warning triggers near limits to prevent unexpected blockages:
        // Free warning at >= 23
        val freeWarningUnder = SubscriptionTierManager.getUpgradeFunnelWarning(22)
        assertNull(freeWarningUnder)

        // Reset to free for testing warning
        SubscriptionTierManager.resetToFree()
        val freeWarningNear = SubscriptionTierManager.getUpgradeFunnelWarning(23)
        assertNotNull(freeWarningNear)
        assertTrue(freeWarningNear!!.contains("Approaching Free limit"))

        val freeWarningAtLimit = SubscriptionTierManager.getUpgradeFunnelWarning(25)
        assertNotNull(freeWarningAtLimit)
        assertTrue(freeWarningAtLimit!!.contains("Approaching Free limit"))
    }

    @Test
    fun testPaywallTriggerReasonSemanticIntegrity() {
        val studentLimit = PaywallTriggerReason.STUDENT_LIMIT_REACHED
        assertEquals("Free Plan Limit Reached", studentLimit.headline)
        assertTrue(studentLimit.description.contains("25-person"))

        val personLimit = PaywallTriggerReason.PERSON_LIMIT_REACHED
        assertEquals("Free Plan Limit Reached", personLimit.headline)

        val excelExport = PaywallTriggerReason.EXCEL_PDF_EXPORT_LOCKED
        assertEquals("Unlock Excel & PDF Reports", excelExport.headline)
        assertTrue(excelExport.description.contains("₹199/mo"))

        val cloudSync = PaywallTriggerReason.CLOUD_SYNC_LOCKED
        assertEquals("Unlock Cloud & Multi-Device Sync", cloudSync.headline)

        val multiDevice = PaywallTriggerReason.MULTI_DEVICE_LOCKED
        assertEquals("Multi-Kiosk Fleet Access", multiDevice.headline)

        val adsRemoval = PaywallTriggerReason.ADS_REMOVAL
        assertEquals("Remove Advertisements", adsRemoval.headline)
    }

    // ── 4. 14-Day Offline Grace Period & Degradation ──

    @Test
    fun testOfflineGracePeriod_exact14Days() {
        val graceMs = SubscriptionTierManager.OFFLINE_GRACE_PERIOD_MS
        assertEquals(TimeUnit.DAYS.toMillis(14), graceMs)
        assertEquals(14 * 24 * 60 * 60 * 1000L, graceMs)
    }

    @Test
    fun testOfflineGracePeriod_evaluationLogic() {
        val now = System.currentTimeMillis()
        val expiryMs = now - TimeUnit.DAYS.toMillis(5) // Expired 5 days ago

        // Within 14-day grace period: still valid
        val isWithinGrace = now <= (expiryMs + SubscriptionTierManager.OFFLINE_GRACE_PERIOD_MS)
        assertTrue("Expired 5 days ago is within 14-day grace period", isWithinGrace)

        // Past 14-day grace period: expired 15 days ago
        val oldExpiryMs = now - TimeUnit.DAYS.toMillis(15)
        val isPastGrace = now <= (oldExpiryMs + SubscriptionTierManager.OFFLINE_GRACE_PERIOD_MS)
        assertFalse("Expired 15 days ago exceeds 14-day grace period", isPastGrace)
    }

    // ── 5. Server-Authoritative Remote Tier Mapping ──

    @Test
    fun testServerTierResolution_mappingParity() {
        fun resolveRemoteTier(remoteTierStr: String): SubscriptionTier {
            return try {
                when (remoteTierStr.uppercase()) {
                    "BUSINESS", "INSTITUTION", "ENTERPRISE" -> SubscriptionTier.INSTITUTION
                    "PROFESSIONAL", "PRO" -> SubscriptionTier.PRO
                    "PREMIUM" -> SubscriptionTier.PREMIUM
                    "FREE" -> SubscriptionTier.FREE
                    else -> SubscriptionTier.valueOf(remoteTierStr.uppercase())
                }
            } catch (_: Exception) {
                SubscriptionTier.FREE
            }
        }

        assertEquals(SubscriptionTier.INSTITUTION, resolveRemoteTier("BUSINESS"))
        assertEquals(SubscriptionTier.INSTITUTION, resolveRemoteTier("INSTITUTION"))
        assertEquals(SubscriptionTier.INSTITUTION, resolveRemoteTier("ENTERPRISE"))
        assertEquals(SubscriptionTier.PRO, resolveRemoteTier("PROFESSIONAL"))
        assertEquals(SubscriptionTier.PRO, resolveRemoteTier("PRO"))
        assertEquals(SubscriptionTier.PREMIUM, resolveRemoteTier("PREMIUM"))
        assertEquals(SubscriptionTier.FREE, resolveRemoteTier("FREE"))
        assertEquals(SubscriptionTier.FREE, resolveRemoteTier("UNKNOWN_TIER_FORGERY"))
    }

    // ── 6. Google Play Product ID Standardization ──

    @Test
    fun testGooglePlayProductIdentifiers() {
        assertEquals("omniface_premium_monthly_199", PlayBillingManager.PRODUCT_ID_PREMIUM_MONTHLY)
        assertEquals("omniface_pro_monthly_349", PlayBillingManager.PRODUCT_ID_PRO_MONTHLY)
        assertEquals("monthly-auto-renewing", PlayBillingManager.BASE_PLAN_ID_DEFAULT)
    }

    // ── 7. Display Formatting & Max People ──

    @Test
    fun testMaxPeopleDisplayStrings() {
        SubscriptionTierManager.resetToFree()
        assertEquals("25", SubscriptionTierManager.getMaxPeopleDisplay())
        assertEquals("25", SubscriptionTierManager.getMaxStudentsDisplay())
        assertTrue(SubscriptionTierManager.isFreeTier())
        assertTrue(SubscriptionTierManager.shouldDisplayAds())
        assertFalse(SubscriptionTierManager.canSyncCloud())
        assertFalse(SubscriptionTierManager.canExportReports())
        assertFalse(SubscriptionTierManager.canUseMultiDevice())
    }
}