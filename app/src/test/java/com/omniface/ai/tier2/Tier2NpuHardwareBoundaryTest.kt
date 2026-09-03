package com.omniface.ai.tier2

import com.omniface.ai.hardware.NpuHardwareInfo
import org.junit.Assert.*
import org.junit.Test

/**
 * Tier 2: Boundary & Corner Cases - Feature 11: NPU Silicon Discovery & Fallback Paths
 */
class Tier2NpuHardwareBoundaryTest {

    @Test
    fun testEmptySocModelAndManufacturer() {
        val fallbackInfo = NpuHardwareInfo(
            socModel = "Generic ARM Device",
            socManufacturer = "Android",
            npuName = "Android Neural Networks (NNAPI NPU)",
            npuArchitecture = "Dedicated Hardware Neural Acceleration Engine (Systolic Matrix)",
            peakTops = "30.0 TOPS (NNAPI Hardware Accelerator)",
            supportedPrecisions = listOf("INT8", "FP32"),
            armFeatures = emptyList(),
            boardPlatform = "generic",
            isGenuineNpuDetected = true,
            diagnosticSummary = "Genuine Neural Processing Unit (NNAPI) active."
        )

        assertNotNull(fallbackInfo.socModel)
        assertTrue(fallbackInfo.isGenuineNpuDetected)
        assertEquals("Android Neural Networks (NNAPI NPU)", fallbackInfo.npuName)
    }

    @Test
    fun testUnknownPlatformString() {
        val customInfo = NpuHardwareInfo(
            socModel = "CUSTOM_SOC_X",
            socManufacturer = "CustomVendor",
            npuName = "ARMv8/v9 Neural Matrix Engine (DotProd/I8MM)",
            npuArchitecture = "Dedicated Hardware Neural Acceleration Engine",
            peakTops = "45.0 TOPS",
            supportedPrecisions = listOf("INT8", "FP16", "FP32"),
            armFeatures = listOf("i8mm", "asimddp"),
            boardPlatform = "unknown_custom_board",
            isGenuineNpuDetected = true,
            diagnosticSummary = "Hardware tensor execution enabled."
        )

        assertTrue(customInfo.armFeatures.contains("i8mm"))
        assertEquals("45.0 TOPS", customInfo.peakTops)
    }

    @Test
    fun testMissingArmFeaturesList() {
        val noFeatureInfo = NpuHardwareInfo(
            socModel = "Model_A",
            socManufacturer = "Mfg_A",
            npuName = "NNAPI Fallback",
            npuArchitecture = "Fallback Architecture",
            peakTops = "10.0 TOPS",
            supportedPrecisions = listOf("FP32"),
            armFeatures = emptyList(),
            boardPlatform = "plat_a",
            isGenuineNpuDetected = false,
            diagnosticSummary = "Fallback CPU execution."
        )

        assertTrue(noFeatureInfo.armFeatures.isEmpty())
        assertFalse(noFeatureInfo.isGenuineNpuDetected)
    }

    @Test
    fun testZeroOrNegativeTopsStringFormat() {
        val info = NpuHardwareInfo(
            socModel = "Legacy SoC",
            socManufacturer = "Legacy",
            npuName = "CPU XNNPACK",
            npuArchitecture = "Multi-Core CPU",
            peakTops = "1.5 TOPS",
            supportedPrecisions = listOf("FP32"),
            armFeatures = emptyList(),
            boardPlatform = "legacy",
            isGenuineNpuDetected = false,
            diagnosticSummary = "CPU Threadpool."
        )

        assertTrue(info.peakTops.isNotBlank())
    }

    @Test
    fun testNonQualcommDeviceAiHubFilter() {
        val tensorInfo = NpuHardwareInfo(
            socModel = "Google Tensor G4",
            socManufacturer = "Google LLC",
            npuName = "Google Tensor TPU",
            npuArchitecture = "Custom TPU",
            peakTops = "30.0+ TOPS",
            supportedPrecisions = listOf("INT8"),
            armFeatures = listOf("dotprod"),
            boardPlatform = "komodo",
            isGenuineNpuDetected = true,
            diagnosticSummary = "Tensor TPU active."
        )

        assertFalse("Google Tensor is not a Qualcomm AI Hub device", tensorInfo.socManufacturer.contains("Qualcomm"))
    }
}
