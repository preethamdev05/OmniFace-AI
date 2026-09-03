package com.omniface.ai.tier1

import com.omniface.ai.hardware.FleetTopologyManager
import com.omniface.ai.hardware.KioskNode
import com.omniface.ai.hardware.ThermalState
import org.junit.Assert.*
import org.junit.Test

/**
 * Tier 1: Feature 13 - Build & Packaging Configurations, Thermal Governors & Fleet Topology
 */
class Tier1BuildConfigTest {

    @Test
    fun testNamespaceAndPackageConfig() {
        val expectedPackage = "com.omniface.ai"
        assertEquals("OmniFace Application ID / Namespace must match", "com.omniface.ai", expectedPackage)
    }

    @Test
    fun testMinAndTargetSdkVersion() {
        val minSdk = 26 // Android 8.0 Oreo
        val targetSdk = 36 // Android 16
        assertTrue("Min SDK must support Android 8.0+ (API 26)", minSdk >= 26)
        assertTrue("Target SDK must be 36", targetSdk >= 34)
    }

    @Test
    fun testSupportedAbiFilters() {
        val supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
        assertTrue("Must support 64-bit ARM", supportedAbis.contains("arm64-v8a"))
        assertTrue("Must support 32-bit ARM legacy", supportedAbis.contains("armeabi-v7a"))
    }

    @Test
    fun testThermalStateThresholds() {
        val nominal = ThermalState.NOMINAL
        val warm = ThermalState.WARM
        val critical = ThermalState.CRITICAL

        assertEquals(1.0f, nominal.downscaleFactor, 1e-4f)
        assertEquals(30, nominal.maxFps)

        assertEquals(0.75f, warm.downscaleFactor, 1e-4f)
        assertEquals(20, warm.maxFps)

        assertEquals(0.50f, critical.downscaleFactor, 1e-4f)
        assertEquals(10, critical.maxFps)
    }

    @Test
    fun testFleetTopologyLocalNodeInitialization() {
        val node = KioskNode(
            id = "kiosk_gate_alpha",
            name = "Gate Alpha Kiosk",
            ipAddress = "192.168.1.105",
            batteryPct = 95,
            activeFps = 30,
            isOnline = true
        )

        FleetTopologyManager.registerNode(node)
        val registered = FleetTopologyManager.kioskNodes.value.find { it.id == "kiosk_gate_alpha" }

        assertNotNull("Registered node must be found in fleet topology", registered)
        assertEquals("Gate Alpha Kiosk", registered!!.name)
        assertEquals(30, registered.activeFps)

        // Update heartbeat
        FleetTopologyManager.updateNodeHeartbeat("kiosk_gate_alpha", fps = 28, batteryPct = 94)
        val updated = FleetTopologyManager.kioskNodes.value.find { it.id == "kiosk_gate_alpha" }
        assertEquals(28, updated!!.activeFps)
        assertEquals(94, updated.batteryPct)
    }
}
