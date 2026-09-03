package com.omniface.ai.tier2

import com.omniface.ai.hardware.EmergencyEvacuationController
import com.omniface.ai.hardware.KioskLockController
import com.omniface.ai.hardware.TurnstileRelayController
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Tier 2: Boundary & Corner Cases - Feature 12: Kiosk Lockout Bounds & Relay Fail-Safes
 */
class Tier2KioskRelayBoundaryTest {

    @Test
    fun testEmptyOrBlankPinVerification() {
        KioskLockController.setMasterAdminPin("123456")
        assertFalse("Blank PIN must fail verification", KioskLockController.verifyAdminPin(""))
        assertFalse("Whitespace PIN must fail verification", KioskLockController.verifyAdminPin("   "))
    }

    @Test
    fun testTurnstileWebhookEmptyUrlConnection() = runTest {
        TurnstileRelayController.webhookUrl = ""
        val (success, message) = TurnstileRelayController.testWebhookConnection()
        assertFalse(success)
        assertEquals("Webhook URL is not configured", message)
    }

    @Test
    fun testEmergencyEvacuationEmptyReason() {
        EmergencyEvacuationController.triggerEmergencyEvacuation("")
        assertTrue(EmergencyEvacuationController.isEvacuationActive.value)
        assertEquals("", EmergencyEvacuationController.evacuationReason.value)
        EmergencyEvacuationController.resetEvacuation()
    }

    @Test
    fun testKioskLockRemainingSecondsBounded() {
        val remaining = KioskLockController.getRemainingLockoutSeconds()
        assertTrue("Remaining lockout seconds must be non-negative", remaining >= 0)
    }

    @Test
    fun testEmergencyEvacuationMultipleTriggers() {
        EmergencyEvacuationController.triggerEmergencyEvacuation("Alarm 1")
        EmergencyEvacuationController.triggerEmergencyEvacuation("Alarm 2 (Updated)")
        assertTrue(EmergencyEvacuationController.isEvacuationActive.value)
        assertEquals("Alarm 2 (Updated)", EmergencyEvacuationController.evacuationReason.value)
        EmergencyEvacuationController.resetEvacuation()
    }
}
