package com.omniface.ai.hardware

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Normalized Hardware Capabilities for Biometric Edge Execution.
 */
enum class HardwareCapability {
    INT8_PRECISION,
    FP16_PRECISION,
    DOT_PROD,
    I8MM,
    BF16,
    NNAPI_ACCELERATION,
    GPU_DELEGATE,
    XNNPACK_THREADING
}

enum class AcceleratorType(val displayName: String) {
    DEDICATED_NPU("Dedicated NPU"),
    INTEGRATED_APU("Integrated APU"),
    MOBILE_GPU("Mobile GPU"),
    CPU_FALLBACK("Multi-Core CPU")
}

enum class PerformanceTier(val label: String) {
    VERY_HIGH("Very High"),
    HIGH("High"),
    BALANCED("Balanced"),
    STANDARD("Standard")
}

/**
 * Normalized Hardware Profile.
 *
 * Encapsulates SoC, CPU, GPU, accelerators, RAM, and OS level into a single
 * capability-driven model, strictly separating identity from execution capability.
 */
data class HardwareProfile(
    val soc: String,
    val socManufacturer: String,
    val cpuCores: Int,
    val cpuArchitecture: String,
    val gpuRenderer: String,
    val hasAccelerator: Boolean,
    val acceleratorType: AcceleratorType,
    val acceleratorName: String,
    val totalRamMb: Long,
    val androidApiLevel: Int,
    val capabilities: Set<HardwareCapability>,
    val performanceTier: PerformanceTier
) {
    val cpuDisplayLabel: String
        get() = "$cpuCores cores · $cpuArchitecture"

    val acceleratorDisplayLabel: String
        get() = if (hasAccelerator) "Available ($acceleratorName)" else "CPU Only"

    val isInt8Supported: Boolean
        get() = capabilities.contains(HardwareCapability.INT8_PRECISION)

    val isFp16Supported: Boolean
        get() = capabilities.contains(HardwareCapability.FP16_PRECISION)
}

/**
 * Normalizes device discovery into an authoritative, capability-oriented HardwareProfile.
 */
object CapabilityNormalizer {

    @Volatile
    private var cachedProfile: HardwareProfile? = null

    fun resolve(context: Context? = null): HardwareProfile {
        cachedProfile?.let { return it }

        val npuInfo = NpuHardwareDetector.detectNpuHardware()
        val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val apiLevel = try { Build.VERSION.SDK_INT } catch (_: Throwable) { 26 }
        val cpuArch = try {
            val abis = Build.SUPPORTED_64_BIT_ABIS
            if (abis != null && abis.isNotEmpty()) "ARM64-v8a" else "ARMv7"
        } catch (_: Throwable) {
            "ARM64-v8a"
        }

        val ramMb = context?.let { ctx ->
            try {
                val actMgr = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                actMgr?.getMemoryInfo(memInfo)
                memInfo.totalMem / (1024 * 1024)
            } catch (_: Throwable) { 6144L }
        } ?: 6144L

        val caps = mutableSetOf<HardwareCapability>(
            HardwareCapability.XNNPACK_THREADING
        )

        val features = npuInfo.armFeatures.map { it.lowercase() }
        if (features.contains("asimddp") || features.contains("dotprod")) caps.add(HardwareCapability.DOT_PROD)
        if (features.contains("i8mm")) caps.add(HardwareCapability.I8MM)
        if (features.contains("bf16")) caps.add(HardwareCapability.BF16)

        // All modern Android >= 26 devices support FP16 GPU delegates
        if (apiLevel >= Build.VERSION_CODES.O) {
            caps.add(HardwareCapability.GPU_DELEGATE)
            caps.add(HardwareCapability.FP16_PRECISION)
        }

        // Check accelerator type & precision
        val accType: AcceleratorType
        val hasAcc: Boolean
        if (npuInfo.isGenuineNpuDetected) {
            hasAcc = true
            caps.add(HardwareCapability.NNAPI_ACCELERATION)
            caps.add(HardwareCapability.INT8_PRECISION)
            accType = if (npuInfo.npuName.contains("APU", ignoreCase = true) || npuInfo.npuName.contains("NeuroPilot", ignoreCase = true)) {
                AcceleratorType.INTEGRATED_APU
            } else {
                AcceleratorType.DEDICATED_NPU
            }
        } else if (caps.contains(HardwareCapability.GPU_DELEGATE)) {
            hasAcc = true
            accType = AcceleratorType.MOBILE_GPU
        } else {
            hasAcc = false
            accType = AcceleratorType.CPU_FALLBACK
        }

        val perfTier = when {
            hasAcc && npuInfo.isGenuineNpuDetected && (caps.contains(HardwareCapability.I8MM) || npuInfo.peakTops.contains("TOPS")) -> PerformanceTier.VERY_HIGH
            hasAcc && npuInfo.isGenuineNpuDetected -> PerformanceTier.HIGH
            caps.contains(HardwareCapability.GPU_DELEGATE) -> PerformanceTier.BALANCED
            else -> PerformanceTier.STANDARD
        }

        val gpuName = if (npuInfo.socManufacturer.contains("Qualcomm", ignoreCase = true)) {
            "Qualcomm Adreno GPU"
        } else if (npuInfo.socManufacturer.contains("MediaTek", ignoreCase = true)) {
            "ARM Mali / Immortalis GPU"
        } else if (npuInfo.socManufacturer.contains("Samsung", ignoreCase = true)) {
            "Samsung Xclipse / Mali GPU"
        } else {
            "Mobile Hardware GPU"
        }

        val profile = HardwareProfile(
            soc = npuInfo.socModel,
            socManufacturer = npuInfo.socManufacturer,
            cpuCores = cpuCores,
            cpuArchitecture = cpuArch,
            gpuRenderer = gpuName,
            hasAccelerator = hasAcc,
            acceleratorType = accType,
            acceleratorName = npuInfo.shortNpuLabel,
            totalRamMb = ramMb,
            androidApiLevel = apiLevel,
            capabilities = caps,
            performanceTier = perfTier
        )

        cachedProfile = profile
        return profile
    }
}
