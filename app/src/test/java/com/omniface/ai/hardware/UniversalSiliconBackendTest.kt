package com.omniface.ai.hardware

import com.omniface.ai.ml.core.BackendType
import com.omniface.ai.ml.core.InferenceBackend
import org.junit.Assert.*
import org.junit.Test

/**
 * Verification of Multi-Silicon NPU Hardware Detection and Inference Backend Routing.
 *
 * Confirms proper recognition and label formatting across all major silicon vendors:
 * Qualcomm Hexagon, Google Tensor, MediaTek NeuroPilot, Samsung Exynos, and ARM64 CPU.
 */
class UniversalSiliconBackendTest {

    @Test
    fun testSiliconBackendLabels() {
        val qualcommInfo = NpuHardwareInfo(
            socModel = "Snapdragon 8 Gen 3 (SM8650)",
            socManufacturer = "Qualcomm Technologies, Inc.",
            npuName = "Qualcomm Hexagon NPU (HTP Tensor Accelerator)",
            npuArchitecture = "Dedicated Multi-Core Vector + Scalar Systolic Array",
            peakTops = "45.0 TOPS",
            supportedPrecisions = listOf("INT8", "FP16"),
            armFeatures = listOf("i8mm", "asimddp"),
            boardPlatform = "pineapple",
            isGenuineNpuDetected = true,
            diagnosticSummary = "Snapdragon 8 Gen 3 Hexagon HTP active."
        )

        val tensorInfo = NpuHardwareInfo(
            socModel = "Google Tensor G4",
            socManufacturer = "Google LLC",
            npuName = "Google Tensor TPU (Rio EdgeTPU)",
            npuArchitecture = "Custom Google ML Systolic Array Co-Processor",
            peakTops = "30.0+ TOPS",
            supportedPrecisions = listOf("INT8", "BF16"),
            armFeatures = listOf("i8mm", "asimddp", "bf16"),
            boardPlatform = "komodo",
            isGenuineNpuDetected = true,
            diagnosticSummary = "Google Tensor G4 TPU active."
        )

        val mtkInfo = NpuHardwareInfo(
            socModel = "MediaTek Dimensity 9300",
            socManufacturer = "MediaTek Inc.",
            npuName = "MediaTek APU 790 (Generative AI Engine)",
            npuArchitecture = "Hardware Generative AI Engine + Systolic Operator Core",
            peakTops = "46.0 TOPS",
            supportedPrecisions = listOf("INT8", "FP16"),
            armFeatures = listOf("i8mm", "asimddp"),
            boardPlatform = "mt6989",
            isGenuineNpuDetected = true,
            diagnosticSummary = "MediaTek APU 790 active."
        )

        val exynosInfo = NpuHardwareInfo(
            socModel = "Samsung Exynos 2400",
            socManufacturer = "Samsung Electronics Co., Ltd.",
            npuName = "Samsung Exynos Dual-NPU (Generative AI NPU)",
            npuArchitecture = "Dual-Core Hardware Matrix NPU + Xclipse GPU",
            peakTops = "44.0 TOPS",
            supportedPrecisions = listOf("INT8", "FP16"),
            armFeatures = listOf("i8mm", "asimddp"),
            boardPlatform = "s5e9945",
            isGenuineNpuDetected = true,
            diagnosticSummary = "Samsung Exynos 2400 Dual-NPU active."
        )

        val qLabel = InferenceBackend.resolveBackendLabel(BackendType.QUALCOMM_NPU, qualcommInfo)
        val tLabel = InferenceBackend.resolveBackendLabel(BackendType.GOOGLE_TENSOR_TPU, tensorInfo)
        val mLabel = InferenceBackend.resolveBackendLabel(BackendType.MEDIATEK_APU, mtkInfo)
        val eLabel = InferenceBackend.resolveBackendLabel(BackendType.SAMSUNG_EXYNOS_NPU, exynosInfo)

        assertTrue("Qualcomm label should contain Hexagon", qLabel.contains("Hexagon"))
        assertTrue("Tensor label should contain Tensor or TPU", tLabel.contains("Tensor") || tLabel.contains("TPU"))
        assertTrue("MediaTek label should contain APU", mLabel.contains("APU"))
        assertTrue("Exynos label should contain Exynos", eLabel.contains("Exynos"))
    }

    @Test
    fun testBackendTypeEnumCompleteness() {
        val types = BackendType.values().map { it.name }
        assertTrue(types.contains("QUALCOMM_NPU"))
        assertTrue(types.contains("GOOGLE_TENSOR_TPU"))
        assertTrue(types.contains("MEDIATEK_APU"))
        assertTrue(types.contains("SAMSUNG_EXYNOS_NPU"))
        assertTrue(types.contains("ADRENO_GPU"))
        assertTrue(types.contains("CPU_XNNPACK"))
    }
}
