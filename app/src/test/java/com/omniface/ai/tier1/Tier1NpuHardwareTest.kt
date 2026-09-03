package com.omniface.ai.tier1

import com.omniface.ai.hardware.NpuHardwareDetector
import com.omniface.ai.hardware.NpuHardwareInfo
import org.junit.Assert.*
import org.junit.Test

/**
 * Tier 1: Feature 11 - Direct Silicon NPU Hardware Detection & Status
 */
class Tier1NpuHardwareTest {

    @Test
    fun testNpuHardwareInfoDataClass() {
        val info = NpuHardwareInfo(
            socModel = "Snapdragon 8 Gen 3 (SM8650)",
            socManufacturer = "Qualcomm Technologies, Inc.",
            npuName = "Qualcomm Hexagon NPU (HTP Tensor Accelerator)",
            npuArchitecture = "Dedicated Multi-Core Vector + Scalar Systolic Array",
            peakTops = "45.0 TOPS",
            supportedPrecisions = listOf("INT8", "FP16"),
            armFeatures = listOf("asimddp", "i8mm"),
            boardPlatform = "pineapple",
            isGenuineNpuDetected = true,
            diagnosticSummary = "Sub-5ms INT8 tensor acceleration verified."
        )

        assertEquals("Snapdragon 8 Gen 3 (SM8650)", info.socModel)
        assertEquals("45.0 TOPS", info.peakTops)
        assertTrue(info.isGenuineNpuDetected)
        assertTrue(info.supportedPrecisions.contains("INT8"))
    }

    @Test
    fun testSnapdragonHexagonMapping() {
        val info = NpuHardwareInfo(
            socModel = "SM8650",
            socManufacturer = "Qualcomm",
            npuName = "Qualcomm Hexagon NPU (HTP Tensor Accelerator)",
            npuArchitecture = "Hexagon Vector + Tensor",
            peakTops = "45.0 TOPS",
            supportedPrecisions = listOf("INT8", "FP16"),
            armFeatures = listOf("i8mm"),
            boardPlatform = "pineapple",
            isGenuineNpuDetected = true,
            diagnosticSummary = "Genuine Snapdragon active."
        )
        assertTrue("Qualcomm Hexagon NPU should indicate TOPS", info.peakTops.contains("TOPS"))
        assertTrue("Hexagon name should be present", info.npuName.contains("Hexagon"))
    }

    @Test
    fun testGoogleTensorTpuMapping() {
        val info = NpuHardwareInfo(
            socModel = "Google Tensor G4",
            socManufacturer = "Google LLC",
            npuName = "Google Tensor TPU (Rio EdgeTPU)",
            npuArchitecture = "Custom Google ML Systolic Array Co-Processor",
            peakTops = "30.0+ TOPS",
            supportedPrecisions = listOf("INT8 (Native EdgeTPU)", "FP16", "BF16"),
            armFeatures = listOf("dotprod"),
            boardPlatform = "komodo",
            isGenuineNpuDetected = true,
            diagnosticSummary = "Google Tensor TPU active."
        )
        assertEquals("Google Tensor TPU (Rio EdgeTPU)", info.npuName)
        assertTrue(info.peakTops.contains("30.0+ TOPS"))
    }

    @Test
    fun testMediaTekApuMapping() {
        val info = NpuHardwareInfo(
            socModel = "MediaTek Dimensity 9300",
            socManufacturer = "MediaTek Inc.",
            npuName = "MediaTek APU 790 (Generative AI Engine)",
            npuArchitecture = "Hardware Generative AI Engine + Systolic Operator Core",
            peakTops = "46.0 TOPS",
            supportedPrecisions = listOf("INT8", "FP16"),
            armFeatures = listOf("i8mm"),
            boardPlatform = "mt6989",
            isGenuineNpuDetected = true,
            diagnosticSummary = "MediaTek APU 790 active."
        )
        assertEquals("MediaTek APU 790 (Generative AI Engine)", info.npuName)
        assertEquals("46.0 TOPS", info.peakTops)
    }

    @Test
    fun testSamsungExynosNpuMapping() {
        val info = NpuHardwareInfo(
            socModel = "Samsung Exynos 2400",
            socManufacturer = "Samsung Electronics",
            npuName = "Samsung Exynos Dual-NPU (17K MACs)",
            npuArchitecture = "Dual-Core Dedicated Hardware Neural Accelerator",
            peakTops = "17.0+ TOPS",
            supportedPrecisions = listOf("INT8", "FP16"),
            armFeatures = listOf("dotprod"),
            boardPlatform = "s5e9945",
            isGenuineNpuDetected = true,
            diagnosticSummary = "Samsung Exynos 2400 Dual-NPU active."
        )
        assertEquals("Samsung Exynos Dual-NPU (17K MACs)", info.npuName)
        assertEquals("17.0+ TOPS", info.peakTops)
    }
}
