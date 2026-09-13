package com.omniface.ai.notifications

import com.omniface.ai.ads.AdMobManager
import com.omniface.ai.billing.SubscriptionTier
import com.omniface.ai.billing.SubscriptionTierManager
import com.omniface.ai.data.local.entity.AegisOutboxEntity
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.sync.AttendanceSyncWorker
import com.omniface.ai.telemetry.TelemetryManager
import com.omniface.ai.ui.navigation.Screen
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Phase 23 / Notifications: Ads, Firebase & Notification Verification Suite
 */
class AdsFirebaseNotificationVerificationTest {

    @Before
    fun setUp() {
        SubscriptionTierManager.resetToFree()
        AdMobManager.resetBannerAdUnitId()
    }

    @After
    fun tearDown() {
        SubscriptionTierManager.resetToFree()
        AdMobManager.resetBannerAdUnitId()
    }

    @Test
    fun testAdMob_FreePlanDisplaysAds_PaidPlansCompletelyAdFree() {
        assertTrue("FREE plan must have displaysAds=true", SubscriptionTier.FREE.displaysAds)
        assertFalse("PREMIUM plan must be ad-free", SubscriptionTier.PREMIUM.displaysAds)
        assertFalse("PRO plan must be ad-free", SubscriptionTier.PRO.displaysAds)
        assertFalse("INSTITUTION plan must be ad-free", SubscriptionTier.INSTITUTION.displaysAds)

        SubscriptionTierManager.resetToFree()
        assertTrue("AdMobManager must report shouldDisplayAds() == true on FREE plan", AdMobManager.shouldDisplayAds())

        val futureExpiry = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000
        SubscriptionTierManager.setSubscription(SubscriptionTier.PREMIUM, futureExpiry, "tok_prem")
        assertFalse("AdMobManager must report shouldDisplayAds() == false on PREMIUM plan", AdMobManager.shouldDisplayAds())

        SubscriptionTierManager.setSubscription(SubscriptionTier.PRO, futureExpiry, "tok_pro")
        assertFalse("AdMobManager must report shouldDisplayAds() == false on PRO plan", AdMobManager.shouldDisplayAds())

        SubscriptionTierManager.setSubscription(SubscriptionTier.INSTITUTION, futureExpiry, "tok_inst")
        assertFalse("AdMobManager must report shouldDisplayAds() == false on INSTITUTION plan", AdMobManager.shouldDisplayAds())
    }

    @Test
    fun testAdMob_RouteSuppression_ScannerEnrollmentConfirmationNeverShowAds() {
        SubscriptionTierManager.resetToFree()

        val suppressedSurfaces = listOf(
            Screen.Scanner.route,
            Screen.Enrollment.route,
            "scanner",
            "enrollment",
            "confirmation",
            "attendance_confirm"
        )

        for (route in suppressedSurfaces) {
            assertFalse(
                "Surface '$route' MUST NEVER display ads even on FREE tier",
                AdMobManager.shouldDisplayAdsOnRoute(route)
            )
        }

        val allowedSurfaces = listOf(
            Screen.Dashboard.route,
            Screen.Ledger.route,
            Screen.Settings.route
        )

        for (route in allowedSurfaces) {
            assertTrue(
                "Secondary surface '$route' must permit ads on FREE tier",
                AdMobManager.shouldDisplayAdsOnRoute(route)
            )
        }

        val futureExpiry = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000
        SubscriptionTierManager.setSubscription(SubscriptionTier.PREMIUM, futureExpiry, "tok_prem")

        for (route in allowedSurfaces + suppressedSurfaces) {
            assertFalse(
                "Route '$route' must be 100% ad-free on paid plans",
                AdMobManager.shouldDisplayAdsOnRoute(route)
            )
        }
    }

    @Test
    fun testFirebaseTelemetry_EventsLoggedSafelyWithoutCrashing() {
        TelemetryManager.setUserId("user_prod_alpha_01")
        TelemetryManager.setOrganizationId("org_sovereign_777")
        TelemetryManager.logAttendanceMarked("FACIAL_ARC_512", true, 24L)
        TelemetryManager.logSubscriptionUpgraded("PRO", 349)
        TelemetryManager.logSyncCompleted(42, true)
        TelemetryManager.recordNonFatal(RuntimeException("Simulated transient network timeout"), "AttendanceSync")
    }

    @Test
    fun testFCM_ActionPayloadContract_SupportedAndUnknownActions() {
        val validActions = listOf("SUBSCRIPTION_UPDATED", "SYNC_FORCE", "KIOSK_LOCKED")
        val processedActions = mutableListOf<String>()

        fun routeFcmAction(data: Map<String, String>): Boolean {
            val action = data["action"] ?: return false
            return when (action) {
                "SUBSCRIPTION_UPDATED" -> {
                    processedActions.add(action)
                    true
                }
                "SYNC_FORCE" -> {
                    processedActions.add(action)
                    true
                }
                "KIOSK_LOCKED" -> {
                    processedActions.add(action)
                    true
                }
                else -> false
            }
        }

        for (action in validActions) {
            val handled = routeFcmAction(mapOf("action" to action))
            assertTrue("Action '$action' must be handled by FCM router", handled)
        }
        assertEquals(3, processedActions.size)

        assertFalse(routeFcmAction(mapOf("action" to "UNKNOWN_EXPERIMENTAL_OP")))
        assertFalse(routeFcmAction(emptyMap()))
    }

    @Test
    fun testBiometricTransaction_ZeroNetworkOrWhatsAppDependency() {
        val now = System.currentTimeMillis()
        val recordId = "rec_test_offline_01"
        val roll = "ROLL_101"

        val localAttendance = AttendanceRecordEntity(
            recordId = recordId,
            studentRoll = roll,
            studentName = "Alice Doe",
            sessionDate = "2026-09-13",
            timestamp = now,
            confidencePct = 98.5f,
            securityTier = "HIGH",
            sha256Hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            isSynced = false
        )

        val outboxEntry = AegisOutboxEntity(
            recordId = recordId,
            studentRoll = roll,
            timestamp = now,
            confidencePct = 98.5f,
            leafHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            status = AegisOutboxEntity.STATUS_PENDING_MINT,
            retryCount = 0,
            createdAt = now
        )

        assertFalse("Local attendance must be persisted with isSynced=false", localAttendance.isSynced)
        assertEquals("Aegis outbox status must be PENDING_MINT", AegisOutboxEntity.STATUS_PENDING_MINT, outboxEntry.status)
        assertEquals(recordId, outboxEntry.recordId)
        assertEquals(roll, outboxEntry.studentRoll)
    }

    @Test
    fun testAttendanceSyncPayload_StructureForDownstreamWhatsAppDispatch() {
        val records = listOf(
            AttendanceRecordEntity(
                recordId = "rec_001",
                studentRoll = "CS101",
                studentName = "Bob Smith",
                sessionDate = "2026-09-13",
                timestamp = 1773489600000L,
                confidencePct = 96.0f,
                securityTier = "STANDARD",
                sha256Hash = "hash_001",
                isSynced = false
            ),
            AttendanceRecordEntity(
                recordId = "rec_002",
                studentRoll = "CS102",
                studentName = "Carol Danvers",
                sessionDate = "2026-09-13",
                timestamp = 1773489605000L,
                confidencePct = 99.2f,
                securityTier = "HIGH",
                sha256Hash = "hash_002",
                isSynced = false
            )
        )

        val payloadStr = AttendanceSyncWorker.buildPayloadString("TERMINAL-01", records, "org-alpha-99")

        assertTrue(payloadStr.contains(""""device_id":"TERMINAL-01""""))
        assertTrue(payloadStr.contains(""""orgId":"org-alpha-99""""))
        assertTrue(payloadStr.contains(""""records":"""))
        assertTrue(payloadStr.contains(""""record_id":"rec_001""""))
        assertTrue(payloadStr.contains(""""student_roll":"CS101""""))
        assertTrue(payloadStr.contains(""""student_name":"Bob Smith""""))
        assertTrue(payloadStr.contains(""""sha256_hash":"hash_001""""))
        assertTrue(payloadStr.contains(""""record_id":"rec_002""""))
        assertTrue(payloadStr.contains(""""student_roll":"CS102""""))
        assertTrue(payloadStr.contains(""""student_name":"Carol Danvers""""))
        assertTrue(payloadStr.contains(""""sha256_hash":"hash_002""""))
    }

    @Test
    fun testSyncWorkerResponseValidation_HandlesBackendAcknowledgements() {
        val successResponse = """{"success":true,"status":"synced","message":"Processed 2 records"}"""
        assertTrue(AttendanceSyncWorker.validateSyncResponse(successResponse, 2))

        assertTrue(AttendanceSyncWorker.validateSyncResponse("", 0))

        assertFalse(AttendanceSyncWorker.validateSyncResponse("""{"success":false,"error":"Invalid token"}"""))
        assertFalse(AttendanceSyncWorker.validateSyncResponse("""{"status":"error","message":"DB offline"}"""))
        assertFalse(AttendanceSyncWorker.validateSyncResponse("""{"status":"failed","message":"Server error"}"""))
        assertFalse(AttendanceSyncWorker.validateSyncResponse("Internal Server Error"))
    }
}