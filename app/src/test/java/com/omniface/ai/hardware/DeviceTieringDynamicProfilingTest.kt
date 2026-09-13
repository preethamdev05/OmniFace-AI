package com.omniface.ai.hardware

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit Test Suite for Phase 16: Device Tiering & Dynamic Profiling.
 *
 * Verifies:
 * 1. Silicon Architecture Mapping: Verifies Qualcomm Hexagon NPU, Google Tensor TPU, MediaTek APU, and Samsung Exynos NPU identification.
 * 2. ARM Vector ISA Detection: Validates extraction of i8mm, asimddp (dotprod), and bf16 neural instructions.
 * 3. Normalized Capability Mapping: Asserts correct assignment of HardwareCapability flags (INT8, FP16, GPU_DELEGATE, NNAPI_ACCELERATION).
 * 4. Performance Tier Evaluation: Validates VERY_HIGH, HIGH, BALANCED, and STANDARD tier classification.
 * 5. Dynamic SIMD Vector Benchmarking: Measures on-device vector throughput (MFLOPS) and latency estimations.
 * 6. Model Compatibility & Auxiliary Budget: Validates memory and model recommendations across capacity tiers.
 */
class DeviceTieringDynamicProfilingTest {

    @Before
    fun setUp() {
        CapabilityNormalizer.resetCache()
    }

    // ── Test 1: Qualcomm Snapdragon 8 Gen 3 Genuine NPU Mapping ──
    @Test
    fun testSiliconMapping_Snapdragon8Gen3_NpuDetected() {
        val npuInfo = NpuHardwareInfo(
            socModel = "Snapdragon 8 Gen 3 (SM8650)",
            socManufacturer = "Qualcomm Technologies, Inc.",
            npuName = "Qualcomm Hexagon NPU (HTP Tensor Accelerator)",
            npuArchitecture = "Dedicated Multi-Core Vector + Scalar Systolic Array",
            peakTops = "45.0 TOPS",
            supportedPrecisions = listOf("INT4", "INT8", "INT16", "FP16"),
            armFeatures = listOf("fp", "asimd", "evtstrm", "aes", "pmull", "sha1", "sha2", "crc32", "atomics", "fphp", "asimdhp", "cpuid", "asimdrdm", "jscvt", "fcma", "lrcpc", "dcpop", "sha3", "sm3", "sm4", "asimddp", "sha512", "sve", "i8mm", "bf16"),
            boardPlatform = "pineapple",
            isGenuineNpuDetected = true,
            diagnosticSummary = "Qualcomm Snapdragon 8 Gen 3 • Hexagon NPU (HTP Tensor Accelerator, 45.0 TOPS)"
        )

        val profile = CapabilityNormalizer.profileCustom(
            npuInfo = npuInfo,
            cpuCores = 8,
            ramMb = 12288L,
            apiLevel = 34
        )

        assertEquals("Snapdragon 8 Gen 3 (SM8650)", profile.soc)
        assertEquals("Qualcomm Technologies, Inc.", profile.socManufacturer)
        assertTrue("Must detect accelerator", profile.hasAccelerator)
        assertEquals(AcceleratorType.DEDICATED_NPU, profile.acceleratorType)
        assertEquals("Hexagon NPU", profile.acceleratorName)
        assertEquals(PerformanceTier.VERY_HIGH, profile.performanceTier)
        assertTrue("Must support INT8", profile.isInt8Supported)
        assertTrue("Must support FP16", profile.isFp16Supported)
        assertTrue("Must include I8MM", profile.capabilities.contains(HardwareCapability.I8MM))
        assertTrue("Must include DOT_PROD", profile.capabilities.contains(HardwareCapability.DOT_PROD))
        assertTrue("Must include BF16", profile.capabilities.contains(HardwareCapability.BF16))
    }

    // ── Test 2: Google Tensor G4 TPU Mapping ──
    @Test
    fun testSiliconMapping_GoogleTensorG4_EdgeTpuDetected() {
        val npuInfo = NpuHardwareInfo(
            socModel = "Google Tensor G4",
            socManufacturer = "Google LLC",
            npuName = "Google Tensor TPU (EdgeTPU Engine)",
            npuArchitecture = "Dedicated Matrix Multiply Unit + Vector Engine",
            peakTops = "30.0 TOPS",
            supportedPrecisions = listOf("INT8", "FP16", "BF16"),
            armFeatures = listOf("fp", "asimd", "asimddp", "i8mm", "bf16"),
            boardPlatform = "zuma_pro",
            isGenuineNpuDetected = true,
            diagnosticSummary = "Google Tensor G4 • Google Tensor TPU (EdgeTPU Engine, 30.0 TOPS)"
        )

        val profile = CapabilityNormalizer.profileCustom(
            npuInfo = npuInfo,
            cpuCores = 8,
            ramMb = 16384L,
            apiLevel = 34
        )

        assertEquals("Google Tensor G4", profile.soc)
        assertTrue(profile.hasAccelerator)
        assertEquals("Tensor TPU", profile.acceleratorName)
        assertEquals(PerformanceTier.VERY_HIGH, profile.performanceTier)
        assertTrue(profile.capabilities.contains(HardwareCapability.NNAPI_ACCELERATION))
    }

    // ── Test 3: MediaTek Dimensity 9300 APU Mapping ──
    @Test
    fun testSiliconMapping_MediaTekDimensity9300_ApuDetected() {
        val npuInfo = NpuHardwareInfo(
            socModel = "Dimensity 9300",
            socManufacturer = "MediaTek Inc.",
            npuName = "MediaTek APU 790 (NeuroPilot Engine)",
            npuArchitecture = "Hardware Generative AI Accelerator + Dual MAC Arrays",
            peakTops = "46.0 TOPS",
            supportedPrecisions = listOf("INT8", "INT16", "FP16"),
            armFeatures = listOf("fp", "asimd", "asimddp", "i8mm"),
            boardPlatform = "mt6989",
            isGenuineNpuDetected = true,
            diagnosticSummary = "MediaTek Dimensity 9300 • MediaTek APU 790 (46.0 TOPS)"
        )

        val profile = CapabilityNormalizer.profileCustom(
            npuInfo = npuInfo,
            cpuCores = 8,
            ramMb = 16384L,
            apiLevel = 34
        )

        assertEquals(AcceleratorType.INTEGRATED_APU, profile.acceleratorType)
        assertEquals("MediaTek APU", profile.acceleratorName)
        assertEquals("ARM Mali / Immortalis GPU", profile.gpuRenderer)
        assertEquals(PerformanceTier.VERY_HIGH, profile.performanceTier)
    }

    // ── Test 4: Samsung Exynos 2400 Dual-NPU Mapping ──
    @Test
    fun testSiliconMapping_SamsungExynos2400_DualNpuDetected() {
        val npuInfo = NpuHardwareInfo(
            socModel = "Exynos 2400",
            socManufacturer = "Samsung Electronics Co., Ltd.",
            npuName = "Samsung Exynos Dual-NPU (17K MACs)",
            npuArchitecture = "Dual-Core Deep Learning Processing Unit + DSP",
            peakTops = "17.0K MACs",
            supportedPrecisions = listOf("INT8", "FP16"),
            armFeatures = listOf("fp", "asimd", "asimddp"),
            boardPlatform = "s5e9945",
            isGenuineNpuDetected = true,
            diagnosticSummary = "Samsung Exynos 2400 • Dual-NPU (17K MACs)"
        )

        val profile = CapabilityNormalizer.profileCustom(
            npuInfo = npuInfo,
            cpuCores = 10,
            ramMb = 12288L,
            apiLevel = 34
        )

        assertEquals("Exynos NPU", profile.acceleratorName)
        assertEquals("Samsung Xclipse / Mali GPU", profile.gpuRenderer)
        assertEquals(10, profile.cpuCores)
        assertTrue(profile.isInt8Supported)
    }

    // ── Test 5: Standard Tier CPU Fallback When No Accelerator Present ──
    @Test
    fun testStandardTier_CpuFallbackWhenNoAccelerator() {
        val npuInfo = NpuHardwareInfo(
            socModel = "Legacy SoC",
            socManufacturer = "Generic",
            npuName = "ARMv8 Cortex Matrix Engine",
            npuArchitecture = "SIMD Vector Engine",
            peakTops = "0.8 TOPS",
            supportedPrecisions = listOf("FP32"),
            armFeatures = listOf("fp", "asimd"),
            boardPlatform = "generic_arm64",
            isGenuineNpuDetected = false,
            diagnosticSummary = "Legacy SoC • Generic CPU"
        )

        val profile = CapabilityNormalizer.profileCustom(
            npuInfo = npuInfo,
            cpuCores = 4,
            ramMb = 3072L,
            apiLevel = 24 // Below Android O (no GPU delegate fallback)
        )

        assertFalse(profile.hasAccelerator)
        assertEquals(AcceleratorType.CPU_FALLBACK, profile.acceleratorType)
        assertEquals(PerformanceTier.STANDARD, profile.performanceTier)
        assertEquals("CPU Only", profile.acceleratorDisplayLabel)
    }

    // ── Test 6: Dynamic SIMD Vector Benchmark Execution ──
    @Test
    fun testDynamicVectorBenchmark_CalculatesThroughputAndLatency() {
        val benchmark = CapabilityNormalizer.runDynamicBenchmark(iterations = 500)

        assertTrue("Throughput must be positive MFLOPS", benchmark.vectorThroughputMflops > 0.0)
        assertTrue("Estimated latency must be within reasonable bounds (1.5ms to 45ms)",
            benchmark.estimatedInferenceLatencyMs in 1.5f..45.0f)
        assertNotNull("Measured tier must be resolved", benchmark.measuredTier)
    }

    // ── Test 7: Device Capacity Tiers and Auxiliary Model Budgets ──
    @Test
    fun testDeviceCapacityTier_AuxiliaryModelBudgets() {
        assertEquals(6, DeviceCapacityTier.ULTRA.maxRecommendedAuxiliaryModels)
        assertEquals(4, DeviceCapacityTier.FLAGSHIP.maxRecommendedAuxiliaryModels)
        assertEquals(2, DeviceCapacityTier.BALANCED.maxRecommendedAuxiliaryModels)
        assertEquals(1, DeviceCapacityTier.ENTRY.maxRecommendedAuxiliaryModels)

        assertTrue(DeviceCapacityTier.ULTRA.maxConcurrentFaces >= 3)
        assertTrue(DeviceCapacityTier.FLAGSHIP.maxConcurrentFaces >= 2)
        assertTrue(DeviceCapacityTier.BALANCED.maxConcurrentFaces >= 1)
        assertTrue(DeviceCapacityTier.ENTRY.maxConcurrentFaces >= 1)
    }
}
