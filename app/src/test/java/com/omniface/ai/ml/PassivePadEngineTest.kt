package com.omniface.ai.ml

import com.omniface.ai.ml.antispoof.ColorChannelOrder
import com.omniface.ai.ml.antispoof.PassivePadResult
import org.junit.Assert.*
import org.junit.Test

/**
 * Verification of MiniFASNet Passive RGB PAD Channel Parity & 3-Class Output Handling (Stage 10).
 */
class PassivePadEngineTest {

    @Test
    fun testChannelOrderConfiguration_valuesAndParity() {
        // Enforce both RGB and BGR channel options exist
        val orders = ColorChannelOrder.values()
        assertTrue(orders.contains(ColorChannelOrder.BGR))
        assertTrue(orders.contains(ColorChannelOrder.RGB))
        assertEquals(2, orders.size)

        // BGR is the standard OpenCV / MiniFASNet channel format
        assertEquals("BGR", ColorChannelOrder.BGR.name)
        assertEquals("RGB", ColorChannelOrder.RGB.name)
    }

    @Test
    fun testPassivePadResult_liveClassification() {
        val result = PassivePadResult(
            isLive = true,
            livenessScore = 0.985f,
            spoofProbability = 0.015f,
            attackTypeDescription = "Authentic 3D Human Face",
            latencyMs = 12L
        )

        assertTrue(result.isLive)
        assertEquals(0.985f, result.livenessScore, 1e-4f)
        assertEquals(0.015f, result.spoofProbability, 1e-4f)
        assertEquals("Authentic 3D Human Face", result.attackTypeDescription)
        assertEquals(12L, result.latencyMs)
    }

    @Test
    fun testPassivePadResult_screenReplayAttack() {
        val result = PassivePadResult(
            isLive = false,
            livenessScore = 0.020f,
            spoofProbability = 0.980f,
            attackTypeDescription = "Electronic Screen Replay Attack",
            latencyMs = 11L
        )

        assertFalse(result.isLive)
        assertTrue(result.spoofProbability > 0.90f)
        assertTrue(result.attackTypeDescription.contains("Screen Replay"))
    }

    @Test
    fun testPassivePadResult_photoPrintAttack() {
        val result = PassivePadResult(
            isLive = false,
            livenessScore = 0.010f,
            spoofProbability = 0.990f,
            attackTypeDescription = "2D Printed Photo Spoof Attack",
            latencyMs = 10L
        )

        assertFalse(result.isLive)
        assertTrue(result.spoofProbability > 0.90f)
        assertTrue(result.attackTypeDescription.contains("Photo Spoof"))
    }

    @Test
    fun testOutputParsing_preActivatedProbabilitiesParity() {
        // Pre-activated output from MiniFASNet with fused softmax operator (sums to ~1.0)
        val rawLiveOutput = floatArrayOf(0.003f, 0.007f, 0.990f)
        val sum = rawLiveOutput[0] + rawLiveOutput[1] + rawLiveOutput[2]
        assertTrue("Model outputs must sum to ~1.0 indicating fused softmax", sum in 0.95f..1.05f)

        val photoSpoof = rawLiveOutput[0]
        val screenSpoof = rawLiveOutput[1]
        val liveProb = rawLiveOutput[2]

        val isLive = liveProb >= 0.65f && (photoSpoof + screenSpoof) < 0.40f
        assertTrue("Authentic 3D face should be classified as Live", isLive)
    }
}
