package com.omniface.ai.hardware

import org.junit.Assert.*
import org.junit.Test

/**
 * Verification of Normalized HardwareProfile and Capability-Driven Architecture.
 *
 * Confirms that hardware capabilities (INT8, FP16, GPU Delegate, NNAPI, XNNPACK)
 * are separated from raw chipset vendor strings, and that execution policies
 * select based on actual capabilities rather than chip brand.
 */
class HardwareProfileCapabilityTest {

    @Test
    fun testCapabilityNormalization_SeparatesIdentityFromCapability() {
        val profile = CapabilityNormalizer.resolve()

        // Verify fundamental invariants
        assertNotNull(profile.soc)
        assertNotNull(profile.socManufacturer)
        assertTrue("CPU cores must be at least 1", profile.cpuCores >= 1)
        assertTrue("RAM must be at least 512 MB", profile.totalRamMb >= 512L)
        assertNotNull(profile.performanceTier)
        assertTrue("Capabilities must include CPU threading", profile.capabilities.contains(HardwareCapability.XNNPACK_THREADING))

        // Ensure display labels are formatted for user clarity without raw jargon
        assertTrue(profile.cpuDisplayLabel.contains("cores"))
        assertNotNull(profile.acceleratorDisplayLabel)
    }

    @Test
    fun testSyntheticProfile_HighPerformanceNpu() {
        val syntheticNpuProfile = HardwareProfile(
            soc = "MediaTek Dimensity 9400",
            socManufacturer = "MediaTek",
            cpuCores = 8,
            cpuArchitecture = "ARM64-v8a",
            gpuRenderer = "ARM Immortalis GPU",
            hasAccelerator = true,
            acceleratorType = AcceleratorType.INTEGRATED_APU,
            acceleratorName = "MediaTek APU",
            totalRamMb = 12288L,
            androidApiLevel = 36,
            capabilities = setOf(
                HardwareCapability.INT8_PRECISION,
                HardwareCapability.FP16_PRECISION,
                HardwareCapability.NNAPI_ACCELERATION,
                HardwareCapability.GPU_DELEGATE,
                HardwareCapability.XNNPACK_THREADING,
                HardwareCapability.I8MM
            ),
            performanceTier = PerformanceTier.VERY_HIGH
        )

        assertTrue("Profile must support INT8", syntheticNpuProfile.isInt8Supported)
        assertTrue("Profile must support FP16", syntheticNpuProfile.isFp16Supported)
        assertEquals(PerformanceTier.VERY_HIGH, syntheticNpuProfile.performanceTier)
        assertEquals(AcceleratorType.INTEGRATED_APU, syntheticNpuProfile.acceleratorType)
    }

    @Test
    fun testSyntheticProfile_CpuFallbackOnly() {
        val cpuOnlyProfile = HardwareProfile(
            soc = "Generic ARMv8",
            socManufacturer = "Unknown",
            cpuCores = 4,
            cpuArchitecture = "ARM64-v8a",
            gpuRenderer = "Software Renderer",
            hasAccelerator = false,
            acceleratorType = AcceleratorType.CPU_FALLBACK,
            acceleratorName = "None",
            totalRamMb = 2048L,
            androidApiLevel = 26,
            capabilities = setOf(HardwareCapability.XNNPACK_THREADING),
            performanceTier = PerformanceTier.STANDARD
        )

        assertFalse("CPU-only profile has no dedicated accelerator", cpuOnlyProfile.hasAccelerator)
        assertFalse("CPU-only profile has no INT8 accelerator", cpuOnlyProfile.isInt8Supported)
        assertEquals(PerformanceTier.STANDARD, cpuOnlyProfile.performanceTier)
        assertEquals("CPU Only", cpuOnlyProfile.acceleratorDisplayLabel)
    }
}
