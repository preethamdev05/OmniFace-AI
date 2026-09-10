package com.omniface.ai.billing

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class SubscriptionTierManagerTest {

    @Test
    fun testFreeTierInvariants() {
        val tier = SubscriptionTier.FREE
        assertEquals("Free Starter", tier.title)
        assertEquals(25, tier.maxStudents)
        assertEquals(0, tier.priceInrMonthly)
        assertFalse(tier.allowsReportExports)
        assertFalse(tier.allowsCloudSync)
        assertFalse(tier.allowsMultiDevice)
        assertTrue(tier.displaysAds)
        assertFalse(tier.hasWebDashboard)
    }

    @Test
    fun testPremiumTierInvariants() {
        val tier = SubscriptionTier.PREMIUM
        assertEquals("Premium Pro", tier.title)
        assertEquals(250, tier.maxStudents)
        assertEquals(199, tier.priceInrMonthly)
        assertTrue(tier.allowsReportExports)
        assertTrue(tier.allowsCloudSync)
        assertTrue(tier.allowsMultiDevice)
        assertFalse(tier.displaysAds)
        assertFalse(tier.hasWebDashboard)
    }

    @Test
    fun testBusinessTierInvariants() {
        val tier = SubscriptionTier.BUSINESS
        assertEquals("Enterprise Business", tier.title)
        assertEquals(Int.MAX_VALUE, tier.maxStudents)
        assertEquals(999, tier.priceInrMonthly)
        assertTrue(tier.allowsReportExports)
        assertTrue(tier.allowsCloudSync)
        assertTrue(tier.allowsMultiDevice)
        assertFalse(tier.displaysAds)
        assertTrue(tier.hasWebDashboard)
    }

    @Test
    fun testOfflineGracePeriodIs30Days() {
        val expectedGraceMs = TimeUnit.DAYS.toMillis(30)
        assertEquals(expectedGraceMs, SubscriptionTierManager.OFFLINE_GRACE_PERIOD_MS)
    }

    @Test
    fun testPaywallTriggerReasonsExist() {
        val studentLimit = PaywallTriggerReason.STUDENT_LIMIT_REACHED
        assertEquals("Free Plan Limit Reached", studentLimit.headline)
        assertTrue(studentLimit.description.contains("25-person Free Plan limit"))

        val exportLocked = PaywallTriggerReason.EXCEL_PDF_EXPORT_LOCKED
        assertEquals("Unlock Excel & PDF Reports", exportLocked.headline)

        val cloudSync = PaywallTriggerReason.CLOUD_SYNC_LOCKED
        assertEquals("Unlock Cloud & Multi-Device Sync", cloudSync.headline)

        val multiDevice = PaywallTriggerReason.MULTI_DEVICE_LOCKED
        assertEquals("Multi-Kiosk Fleet Access", multiDevice.headline)

        val adsRemoval = PaywallTriggerReason.ADS_REMOVAL
        assertEquals("Remove Advertisements", adsRemoval.headline)
    }

    @Test
    fun testCanEnrollMoreLogicWithTiers() {
        // Free tier (cap = 25)
        val freeTier = SubscriptionTier.FREE
        assertTrue(0 < freeTier.maxStudents)
        assertTrue(24 < freeTier.maxStudents)
        assertFalse(25 < freeTier.maxStudents)
        assertFalse(26 < freeTier.maxStudents)

        // Premium tier (cap = 250)
        val premiumTier = SubscriptionTier.PREMIUM
        assertTrue(25 < premiumTier.maxStudents)
        assertTrue(249 < premiumTier.maxStudents)
        assertFalse(250 < premiumTier.maxStudents)

        // Business tier (unlimited)
        val businessTier = SubscriptionTier.BUSINESS
        assertTrue(250 < businessTier.maxStudents)
        assertTrue(10_000 < businessTier.maxStudents)
    }
}
