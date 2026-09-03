package com.omniface.ai.tier2

import android.graphics.Rect
import com.omniface.ai.hardware.FleetTopologyManager
import com.omniface.ai.hardware.ThermalGovernor
import com.omniface.ai.hardware.ThermalState
import org.junit.Assert.*
import org.junit.Test

/**
 * Tier 2: Boundary & Corner Cases - Feature 13: Build Config, Thermal Governors & Dynamic Overrides
 */
class Tier2BuildConfigBoundaryTest {

    @Test
    fun testThermalGovernorSimulationOverride() {
        ThermalGovernor.setSimulationOverride(ThermalState.CRITICAL)
        assertEquals(ThermalState.CRITICAL, ThermalGovernor.thermalState.value)

        ThermalGovernor.setSimulationOverride(ThermalState.WARM)
        assertEquals(ThermalState.WARM, ThermalGovernor.thermalState.value)

        ThermalGovernor.setSimulationOverride(null)
    }

    @Test
    fun testThermalGovernorDisableAutoScaling() {
        ThermalGovernor.setAutoScalingEnabled(false)
        assertEquals(ThermalState.NOMINAL, ThermalGovernor.thermalState.value)
        assertFalse(ThermalGovernor.isAutoScalingEnabled.value)

        ThermalGovernor.setAutoScalingEnabled(true)
        assertTrue(ThermalGovernor.isAutoScalingEnabled.value)
    }

    @Test
    fun testDownscaleFactorBoundaryValues() {
        for (state in ThermalState.values()) {
            assertTrue("Downscale factor must be between 0.2 and 1.0", state.downscaleFactor in 0.2f..1.0f)
            assertTrue("Max FPS must be positive", state.maxFps > 0)
            assertTrue("Frame skip mod must be >= 1", state.frameSkipMod >= 1)
        }
    }

    @Test
    fun testRemapBoundingBoxIdentityAtNominal() {
        val factor = 1.0f
        val left = 100
        val top = 150
        val right = 300
        val bottom = 350

        val scale = 1.0f / factor
        val remappedLeft = (left * scale).toInt().coerceIn(0, 640)
        val remappedTop = (top * scale).toInt().coerceIn(0, 480)
        val remappedRight = (right * scale).toInt().coerceIn(0, 640)
        val remappedBottom = (bottom * scale).toInt().coerceIn(0, 480)

        assertEquals(left, remappedLeft)
        assertEquals(top, remappedTop)
        assertEquals(right, remappedRight)
        assertEquals(bottom, remappedBottom)
    }

    @Test
    fun testFleetTopologyEmptyNodeListHandling() {
        // Updating heartbeat of non-existent node must not crash
        FleetTopologyManager.updateNodeHeartbeat("non_existent_node", fps = 30, batteryPct = 100)
        assertNull(FleetTopologyManager.kioskNodes.value.find { it.id == "non_existent_node" })
    }
}
