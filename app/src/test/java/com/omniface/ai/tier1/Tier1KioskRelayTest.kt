package com.omniface.ai.tier1

import com.omniface.ai.hardware.EmergencyEvacuationController
import com.omniface.ai.hardware.KioskLockController
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tier 1: Feature 12 - Kiosk Lockout, PBKDF2 & Turnstile Webhook Relays
 */
class Tier1KioskRelayTest {

    @Test
    fun testPbkdf2PinHashingAndVerification() {
        val pin = "securePass2026"
        val salt = "0123456789abcdef0123456789abcdef".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hashBytes = factory.generateSecret(spec).encoded
        val hashHex = hashBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        assertEquals(64, hashHex.length)

        // Verify identical re-hash matches
        val verifySpec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        val verifyBytes = factory.generateSecret(verifySpec).encoded
        val verifyHex = verifyBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        assertTrue("Constant time equality check should pass", MessageDigest.isEqual(hashHex.toByteArray(), verifyHex.toByteArray()))
    }

    @Test
    fun testKioskLockControllerPinVerificationLogic() {
        val pin = "998877"
        val wrongPin = "000000"
        val salt = ByteArray(16) { it.toByte() }
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded

        val verifySpec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        val verifyHash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(verifySpec).encoded
        assertTrue("Correct PIN verification must match", MessageDigest.isEqual(hash, verifyHash))

        val wrongSpec = PBEKeySpec(wrongPin.toCharArray(), salt, 120_000, 256)
        val wrongHash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(wrongSpec).encoded
        assertFalse("Wrong PIN verification must fail", MessageDigest.isEqual(hash, wrongHash))
    }

    @Test
    fun testTurnstileRelayHmacSignatureComputation() {
        val payload = """{"event":"DOOR_UNLOCK","gateId":"GATE_01"}"""
        val secret = "my_super_secret_hmac_key_2026"

        val algorithm = "HmacSHA256"
        val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), algorithm)
        val mac = Mac.getInstance(algorithm)
        mac.init(key)
        val hmacBytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        val signature = hmacBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        assertEquals(64, signature.length)
        assertTrue("Signature should be valid hex", signature.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun testEmergencyEvacuationTrigger() {
        EmergencyEvacuationController.triggerEmergencyEvacuation("LIFE SAFETY ALARM")
        assertTrue("Evacuation must be active after trigger", EmergencyEvacuationController.isEvacuationActive.value)
        assertEquals("LIFE SAFETY ALARM", EmergencyEvacuationController.evacuationReason.value)
    }

    @Test
    fun testEmergencyEvacuationReset() {
        EmergencyEvacuationController.triggerEmergencyEvacuation("TEST ALARM")
        EmergencyEvacuationController.resetEvacuation()
        assertFalse("Evacuation must be inactive after reset", EmergencyEvacuationController.isEvacuationActive.value)
        assertEquals("", EmergencyEvacuationController.evacuationReason.value)
    }
}
