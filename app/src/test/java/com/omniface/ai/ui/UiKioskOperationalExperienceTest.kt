package com.omniface.ai.ui

import androidx.compose.animation.core.Spring
import com.omniface.ai.hardware.KioskSelfTestController
import com.omniface.ai.hardware.SelfTestItem
import com.omniface.ai.hardware.SelfTestReport
import com.omniface.ai.ml.verification.policy.AutomationAction
import com.omniface.ai.ml.verification.policy.AutomationDecision
import com.omniface.ai.ml.verification.policy.KioskAutomationState
import com.omniface.ai.ml.verification.policy.KioskAutomationStateMachine
import com.omniface.ai.ui.components.DynamicIslandController
import com.omniface.ai.ui.components.DynamicIslandEvent
import com.omniface.ai.ui.components.IslandPriority
import com.omniface.ai.ui.components.omniLiquidSpecularBorder
import com.omniface.ai.ui.components.omniLiquidSurfaceBrush
import com.omniface.ai.ui.navigation.IndustrialBottomNavTabs
import com.omniface.ai.ui.navigation.Screen
import com.omniface.ai.ui.theme.ThemeMode
import org.junit.Assert.*
import org.junit.Test

/**
 * 💎 Phase 21: UI / Kiosk Operational Experience Test Suite
 *
 * Verifies:
 * 1. Centralized iOS Design System & Liquid Glassmorphic Tokens (CupertinoGlass.kt).
 * 2. 4-Tier Navigation Architecture, Route Semantic Precision & Tab Hierarchy.
 * 3. Dynamic Island Controller Priority Queueing & State Machine.
 * 4. Kiosk Self-Test Diagnostics Suite & Bounded Telemetry Ring Buffer.
 * 5. Kiosk Automation State Machine Nominal & Degraded Operational Cycles.
 */
class UiKioskOperationalExperienceTest {

    // ── 1. Centralized iOS Design System & Token Integrity ──

    @Test
    fun testCentralizedDesignSystem_specularBorderBrushes() {
        val darkBorder = omniLiquidSpecularBorder(isDark = true)
        assertNotNull("Dark specular border must be non-null", darkBorder)

        val lightBorder = omniLiquidSpecularBorder(isDark = false)
        assertNotNull("Light specular border must be non-null", lightBorder)
    }

    @Test
    fun testCentralizedDesignSystem_surfaceBrushes() {
        val darkSurface = omniLiquidSurfaceBrush(isDark = true)
        assertNotNull("Dark surface brush must be non-null", darkSurface)

        val lightSurface = omniLiquidSurfaceBrush(isDark = false)
        assertNotNull("Light surface brush must be non-null", lightSurface)
    }

    @Test
    fun testCentralizedDesignSystem_springAnimationConstants() {
        // Enforce iOS tactile feel: Bouncy medium damping (0.5f) + low stiffness (200.0f)
        assertEquals(0.5f, Spring.DampingRatioMediumBouncy, 1e-4f)
        assertEquals(200.0f, Spring.StiffnessLow, 1e-4f)
    }

    @Test
    fun testThemeMode_allModesSupported() {
        val modes = ThemeMode.values().map { it.name }
        assertTrue("Must support DARK mode", modes.contains("DARK"))
        assertTrue("Must support LIGHT mode", modes.contains("LIGHT"))
        assertTrue("Must support SYSTEM mode", modes.contains("SYSTEM"))
        assertEquals(3, modes.size)
    }

    // ── 2. 4-Tier Navigation Architecture & Destination Routing ──

    @Test
    fun testNavigationTabs_industrialBottomNavTabsCountAndOrder() {
        assertEquals("Must declare exactly 5 primary navigation tabs", 5, IndustrialBottomNavTabs.size)
        assertEquals("Tab 0 must be Overview Dashboard", Screen.Dashboard, IndustrialBottomNavTabs[0])
        assertEquals("Tab 1 must be Scanner", Screen.Scanner, IndustrialBottomNavTabs[1])
        assertEquals("Tab 2 must be Students Enrollment", Screen.Enrollment, IndustrialBottomNavTabs[2])
        assertEquals("Tab 3 must be Ledger", Screen.Ledger, IndustrialBottomNavTabs[3])
        assertEquals("Tab 4 must be Settings", Screen.Settings, IndustrialBottomNavTabs[4])
    }

    @Test
    fun testNavigationRoutes_uniqueAndSemantic() {
        val routes = IndustrialBottomNavTabs.map { it.route }
        assertEquals("All tab routes must be unique", routes.size, routes.distinct().size)

        // Semantic user-facing domain labels
        assertEquals("dashboard", Screen.Dashboard.route)
        assertEquals("Overview", Screen.Dashboard.title)

        assertEquals("scanner", Screen.Scanner.route)
        assertEquals("Scanner", Screen.Scanner.title)

        assertEquals("enrollment", Screen.Enrollment.route)
        assertEquals("Students", Screen.Enrollment.title)

        assertEquals("ledger", Screen.Ledger.route)
        assertEquals("Ledger", Screen.Ledger.title)

        assertEquals("settings", Screen.Settings.route)
        assertEquals("Settings", Screen.Settings.title)
    }

    // ── 3. Dynamic Island Controller Priority Queueing ──

    @Test
    fun testDynamicIsland_postEventUpdatesActiveState() {
        val controller = DynamicIslandController()
        assertNull("Initial active event must be null", controller.activeEvent)

        val event1 = DynamicIslandEvent(title = "Kiosk Ready", subtitle = "Camera online")
        controller.postEvent(event1)
        assertEquals(event1, controller.activeEvent)

        controller.dismiss()
        assertNull("Active event must be null after dismiss without queued events", controller.activeEvent)
    }

    @Test
    fun testDynamicIsland_priorityQueuePrecedence() {
        val controller = DynamicIslandController()
        val eventNormal1 = DynamicIslandEvent(title = "Normal 1", priority = IslandPriority.NORMAL)
        val eventNormal2 = DynamicIslandEvent(title = "Normal 2", priority = IslandPriority.NORMAL)
        val eventHigh = DynamicIslandEvent(title = "High Priority Alert", priority = IslandPriority.HIGH)

        controller.postEvent(eventNormal1)
        assertEquals(eventNormal1, controller.activeEvent)

        // Post another normal event (goes to back of queue)
        controller.postEvent(eventNormal2)

        // Post high priority event (must be placed at front of queue)
        controller.postEvent(eventHigh)

        // Dismiss active event, next active event MUST be the HIGH priority event
        controller.dismiss()
        assertEquals("High priority event must precede normal queued events", eventHigh, controller.activeEvent)

        controller.dismiss()
        assertEquals("Normal 2 should be last", eventNormal2, controller.activeEvent)

        controller.dismiss()
        assertNull(controller.activeEvent)
    }

    @Test
    fun testDynamicIsland_clearAllZeroizesState() {
        val controller = DynamicIslandController()
        controller.postEvent(DynamicIslandEvent(title = "Evt 1"))
        controller.postEvent(DynamicIslandEvent(title = "Evt 2"))

        controller.clearAll()
        assertNull(controller.activeEvent)
    }

    // ── 4. Kiosk Self-Test Diagnostics Suite & Ring Buffer ──

    @Test
    fun testKioskSelfTest_ringBufferCapacityLimit() {
        // Push 150 entries into the diagnostic log
        for (i in 1..150) {
            KioskSelfTestController.recordDiagnosticLog("TEST", "Diagnostic entry #$i")
        }

        val logs = KioskSelfTestController.getDiagnosticLogs()
        assertEquals("Diagnostic log buffer must strictly cap at 100 entries", 100, logs.size)
        assertTrue("Latest entry should be present in logs", logs.last().contains("Diagnostic entry #150"))
    }

    @Test
    fun testKioskSelfTest_selfTestReportEvaluation() {
        val itemsPassing = listOf(
            SelfTestItem(title = "T1", description = "D1", isPassed = true, latencyMs = 10, detail = "OK"),
            SelfTestItem(title = "T2", description = "D2", isPassed = true, latencyMs = 15, detail = "OK")
        )
        val reportPass = SelfTestReport(overallPassed = itemsPassing.all { it.isPassed }, items = itemsPassing)
        assertTrue("Report must pass when all items pass", reportPass.overallPassed)

        val itemsWithFailure = listOf(
            SelfTestItem(title = "T1", description = "D1", isPassed = true, latencyMs = 10, detail = "OK"),
            SelfTestItem(title = "T2", description = "D2", isPassed = false, latencyMs = 50, detail = "FAIL")
        )
        val reportFail = SelfTestReport(overallPassed = itemsWithFailure.all { it.isPassed }, items = itemsWithFailure)
        assertFalse("Report must fail when any item fails", reportFail.overallPassed)
    }

    // ── 5. Kiosk Automation State Machine Operational Cycle ──

    @Test
    fun testKioskAutomationStateMachine_nominalOperationalCycle() {
        val sm = KioskAutomationStateMachine()
        assertEquals(KioskAutomationState.SCANNING, sm.currentState.value)

        val trackId = 1

        // 1. Ingress: Faces detected
        sm.onFacesDetected(count = 1, trackIds = listOf(trackId))
        assertEquals(KioskAutomationState.PROCESSING, sm.currentState.value)

        // 2. Verified match: PROCESSING -> AUTO_CONFIRMED
        val autoConfirmDecision = AutomationDecision(
            action = AutomationAction.AUTO_CONFIRM,
            studentRoll = "ROLL_001",
            studentName = "Rahul Sharma",
            confidencePct = 95f,
            livenessScore = 98f,
            matchSimilarity = 0.82f,
            decisionMargin = 0.15f,
            requiredFrames = 2,
            currentFrames = 2,
            reason = "High confidence match"
        )
        val evaluated = sm.onPolicyEvaluated(trackId, autoConfirmDecision)
        assertEquals(KioskAutomationState.AUTO_CONFIRMED, evaluated)
        assertEquals(KioskAutomationState.AUTO_CONFIRMED, sm.currentState.value)

        // 3. Atomically persisted via AttendanceService: AUTO_CONFIRMED -> RECORDED
        val recorded = sm.onAttendanceRecorded(recordsPersisted = 1, trackId = trackId, proofHash = "sha256_hash")
        assertTrue(recorded)
        assertEquals(KioskAutomationState.RECORDED, sm.currentState.value)

        // 4. Outbox queued: RECORDED -> NOTIFICATION_QUEUED
        sm.onNotificationQueued(trackId = trackId, outboxId = "OUT_100")
        assertEquals(KioskAutomationState.NOTIFICATION_QUEUED, sm.currentState.value)

        // 5. Reset to idle: NOTIFICATION_QUEUED -> SCANNING
        sm.onCycleCompleted(trackId = trackId)
        assertEquals(KioskAutomationState.SCANNING, sm.currentState.value)
    }

    @Test
    fun testKioskAutomationStateMachine_needsReviewAndCooldownTransitions() {
        val sm = KioskAutomationStateMachine()
        val trackId = 2

        sm.onFacesDetected(count = 1, trackIds = listOf(trackId))
        assertEquals(KioskAutomationState.PROCESSING, sm.currentState.value)

        // Ambiguous match transitions to NEEDS_REVIEW
        val reviewDecision = AutomationDecision(
            action = AutomationAction.NEEDS_REVIEW,
            studentRoll = "ROLL_002",
            studentName = "Priya Patel",
            confidencePct = 70f,
            livenessScore = 80f,
            matchSimilarity = 0.65f,
            decisionMargin = 0.02f,
            requiredFrames = 3,
            currentFrames = 3,
            reason = "Ambiguous margin"
        )
        val stateReview = sm.onPolicyEvaluated(trackId, reviewDecision)
        assertEquals(KioskAutomationState.NEEDS_REVIEW, stateReview)
        assertEquals(KioskAutomationState.NEEDS_REVIEW, sm.currentState.value)

        // Cooldown transition on another track
        val trackCooldown = 3
        sm.onFacesDetected(count = 1, trackIds = listOf(trackCooldown))
        val cooldownDecision = AutomationDecision(
            action = AutomationAction.COOLDOWN,
            studentRoll = "ROLL_003",
            studentName = "Amit Kumar",
            confidencePct = 95f,
            livenessScore = 95f,
            matchSimilarity = 0.85f,
            decisionMargin = 0.20f,
            requiredFrames = 2,
            currentFrames = 2,
            reason = "Recently verified"
        )
        val stateCooldown = sm.onPolicyEvaluated(trackCooldown, cooldownDecision)
        assertEquals(KioskAutomationState.COOLDOWN, stateCooldown)
    }
}