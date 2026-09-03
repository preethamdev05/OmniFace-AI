package com.omniface.ai.hardware

import android.app.ActivityManager
import android.content.Context
import android.os.Build

enum class DeviceCapacityTier(
    val label: String,
    val badgeTitle: String,
    val description: String,
    val maxRecommendedAuxiliaryModels: Int
) {
    ULTRA(
        label = "Ultra Neural Silicon",
        badgeTitle = "Tier 1: Ultra Flagship",
        description = "Snapdragon 8 Gen 3/Elite or Dimensity 9300. Fully capable of concurrent real-time CavaFace, 3DMM, Mesh & HRNet at 120 FPS.",
        maxRecommendedAuxiliaryModels = 6
    ),
    FLAGSHIP(
        label = "Flagship Accelerator",
        badgeTitle = "Tier 2: Flagship",
        description = "Snapdragon 8 Gen 1/2, Google Tensor G2-G4, or Dimensity 9000. Smooth real-time MobileFaceNet, 3DMM, Eye Gaze & MediaPipe Mesh.",
        maxRecommendedAuxiliaryModels = 4
    ),
    BALANCED(
        label = "Balanced Neural Engine",
        badgeTitle = "Tier 3: Mid-Range",
        description = "Snapdragon 7/6 series, Dimensity 7000/8000, Exynos. Optimized for Bundled MobileFaceNet INT8 + MediaPipe Mesh + Passive PAD.",
        maxRecommendedAuxiliaryModels = 2
    ),
    ENTRY(
        label = "Lightweight CPU / NNAPI",
        badgeTitle = "Tier 4: Entry-Level",
        description = "Budget SoC or low RAM (<6GB). Running Bundled MobileFaceNet INT8/FP32 baseline. Large auxiliary model downloads are discouraged to prevent thermal lag.",
        maxRecommendedAuxiliaryModels = 1
    )
}

enum class ModelCompatibilityStatus(val label: String, val isRecommended: Boolean) {
    RECOMMENDED_OPTIMAL("Recommended", true),
    SUPPORTED_MODERATE_LOAD("Compatible", true),
    HIGH_LOAD_DISCOURAGED("High Load", false),
    NOT_SUPPORTED_HARDWARE("Unsupported", false)
}

data class ModelHardwareRequirement(
    val modelId: String,
    val displayName: String,
    val description: String,
    val fileSizeFormatted: String,
    val minRamGb: Int,
    val expectedFps: String,
    val targetProcessor: String,
    val compatibilityStatus: ModelCompatibilityStatus,
    val recommendationReason: String
)

data class DeviceCapacityProfile(
    val tier: DeviceCapacityTier,
    val totalRamGb: Float,
    val availableRamGb: Float,
    val npuInfo: NpuHardwareInfo,
    val isSnapdragonFlagship: Boolean,
    val summaryRecommendation: String
)

object DeviceCapacityGovernor {

    fun evaluateDeviceCapacity(context: Context): DeviceCapacityProfile {
        val npuInfo = NpuHardwareDetector.detectNpuHardware()
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)

        val totalRamGb = memInfo.totalMem.toFloat() / (1024f * 1024f * 1024f)
        val availRamGb = memInfo.availMem.toFloat() / (1024f * 1024f * 1024f)

        val isSnapdragon = NpuHardwareDetector.isQualcommAiHubDevice()
        val soc = npuInfo.socModel.uppercase()

        val tier = when {
            (isSnapdragon && (soc.contains("8 GEN 3") || soc.contains("SM8650") || soc.contains("8 ELITE") || soc.contains("SM8750")) ||
             soc.contains("DIMENSITY 93") || soc.contains("DIMENSITY 94") || soc.contains("EXYNOS 2400") || soc.contains("TENSOR G4")) && totalRamGb >= 10.0f -> {
                DeviceCapacityTier.ULTRA
            }
            (isSnapdragon || soc.contains("TENSOR G3") || soc.contains("TENSOR G2") || soc.contains("DIMENSITY 9") || soc.contains("DIMENSITY 83") || soc.contains("EXYNOS 2200")) && totalRamGb >= 7.0f -> {
                DeviceCapacityTier.FLAGSHIP
            }
            totalRamGb >= 4.5f -> {
                DeviceCapacityTier.BALANCED
            }
            else -> {
                DeviceCapacityTier.ENTRY
            }
        }

        val summary = when (tier) {
            DeviceCapacityTier.ULTRA -> "Your device (${npuInfo.socModel}, ${"%.1f".format(totalRamGb)} GB RAM) features top-tier neural acceleration (${npuInfo.npuName}). You can run all advanced face intelligence models simultaneously at maximum precision."
            DeviceCapacityTier.FLAGSHIP -> "Your device (${npuInfo.socModel}, ${"%.1f".format(totalRamGb)} GB RAM) easily runs real-time face matching, 3DMM depth topography, and eye gaze tracking on ${npuInfo.npuName}."
            DeviceCapacityTier.BALANCED -> "Your device (${npuInfo.socModel}) operates best with the bundled MobileFaceNet INT8 engine, EyeGaze, and 3D mesh. Heavy models will execute via GPU/NNAPI acceleration."
            DeviceCapacityTier.ENTRY -> "Your device is running the lightweight bundled MobileFaceNet INT8 engine. To ensure responsive 60 FPS scanning, auxiliary models run in lightweight mode."
        }

        return DeviceCapacityProfile(
            tier = tier,
            totalRamGb = totalRamGb,
            availableRamGb = availRamGb,
            npuInfo = npuInfo,
            isSnapdragonFlagship = isSnapdragon,
            summaryRecommendation = summary
        )
    }

    fun getModelRequirements(context: Context): List<ModelHardwareRequirement> {
        val profile = evaluateDeviceCapacity(context)
        val sMfg = profile.npuInfo.socManufacturer.uppercase()
        val sModel = profile.npuInfo.socModel.uppercase()

        val primaryAccelerator = when {
            sMfg.contains("QUALCOMM") || sModel.contains("SNAPDRAGON") -> "Hexagon NPU / Adreno GPU"
            sModel.contains("TENSOR") -> "Google EdgeTPU / Mali GPU"
            sMfg.contains("MEDIATEK") || sModel.contains("DIMENSITY") -> "MediaTek APU / Immortalis GPU"
            sMfg.contains("SAMSUNG") || sModel.contains("EXYNOS") -> "Samsung Dual-NPU / Xclipse GPU"
            else -> "Mobile GPU / Multi-Core CPU"
        }

        return listOf(
            ModelHardwareRequirement(
                modelId = "mobilefacenet_bundled",
                displayName = "MobileFaceNet INT8 (Bundled Core)",
                description = "Primary 512-D embedding extractor optimized for NPU & XNNPACK threadpools. Pre-installed, zero download required.",
                fileSizeFormatted = "1.54 MB",
                minRamGb = 2,
                expectedFps = if (profile.tier == DeviceCapacityTier.ULTRA) "120+ FPS" else "60-90 FPS",
                targetProcessor = primaryAccelerator,
                compatibilityStatus = ModelCompatibilityStatus.RECOMMENDED_OPTIMAL,
                recommendationReason = "Standard pre-installed engine for all devices."
            ),
            ModelHardwareRequirement(
                modelId = "mediapipe_face",
                displayName = "MediaPipe Face Mesh & Detector",
                description = "468-point 3D topological mesh wireframe & real-time face bounding box tracker.",
                fileSizeFormatted = "2.97 MB",
                minRamGb = 4,
                expectedFps = if (profile.tier >= DeviceCapacityTier.BALANCED) "60-120 FPS" else "30-60 FPS",
                targetProcessor = primaryAccelerator,
                compatibilityStatus = ModelCompatibilityStatus.RECOMMENDED_OPTIMAL,
                recommendationReason = "Lightweight 3MB model providing full 468-point spatial facial wireframes."
            ),
            ModelHardwareRequirement(
                modelId = "eyegaze",
                displayName = "EyeGaze Subpixel Tracking",
                description = "Deep gaze fixation vector and subpixel pupil ray direction estimator.",
                fileSizeFormatted = "9.7 MB",
                minRamGb = 4,
                expectedFps = if (profile.tier >= DeviceCapacityTier.FLAGSHIP) "60 FPS" else "30-45 FPS",
                targetProcessor = primaryAccelerator,
                compatibilityStatus = if (profile.tier >= DeviceCapacityTier.BALANCED) ModelCompatibilityStatus.RECOMMENDED_OPTIMAL else ModelCompatibilityStatus.SUPPORTED_MODERATE_LOAD,
                recommendationReason = "Adds real-time gaze vector rays and anti-spoof attentiveness checking across all chipsets."
            ),
            ModelHardwareRequirement(
                modelId = "facemap_3dmm",
                displayName = "FaceMap 3DMM Depth Topography",
                description = "265-parameter 3D Morphable Model for structural facial depth variance and planar anti-spoofing.",
                fileSizeFormatted = "21 MB",
                minRamGb = 6,
                expectedFps = if (profile.tier >= DeviceCapacityTier.FLAGSHIP) "45-60 FPS" else "25-35 FPS",
                targetProcessor = primaryAccelerator,
                compatibilityStatus = if (profile.tier >= DeviceCapacityTier.FLAGSHIP) ModelCompatibilityStatus.RECOMMENDED_OPTIMAL else ModelCompatibilityStatus.SUPPORTED_MODERATE_LOAD,
                recommendationReason = "Enhances anti-spoofing by analyzing true 3D facial depth topography on your GPU/NPU."
            ),
            ModelHardwareRequirement(
                modelId = "face_attrib_net",
                displayName = "FaceAttribNet Diagnostics",
                description = "5-attribute classification network (Smile, Eyeglasses, Facial Hair, Mask, Gaze).",
                fileSizeFormatted = "42 MB",
                minRamGb = 6,
                expectedFps = if (profile.tier >= DeviceCapacityTier.FLAGSHIP) "40-60 FPS" else "20-30 FPS",
                targetProcessor = primaryAccelerator,
                compatibilityStatus = if (profile.tier >= DeviceCapacityTier.FLAGSHIP) ModelCompatibilityStatus.RECOMMENDED_OPTIMAL else ModelCompatibilityStatus.SUPPORTED_MODERATE_LOAD,
                recommendationReason = "Provides live HUD attribute chips on recognized faces using universal neural inference."
            ),
            ModelHardwareRequirement(
                modelId = "hrnet_face",
                displayName = "HRNet Face Landmarks",
                description = "High-Resolution Network generating 29-channel subpixel heatmap landmark fiducials.",
                fileSizeFormatted = "37 MB",
                minRamGb = 8,
                expectedFps = if (profile.tier == DeviceCapacityTier.ULTRA) "30-45 FPS" else "15-25 FPS",
                targetProcessor = primaryAccelerator,
                compatibilityStatus = if (profile.tier == DeviceCapacityTier.ULTRA) ModelCompatibilityStatus.RECOMMENDED_OPTIMAL else if (profile.tier == DeviceCapacityTier.FLAGSHIP) ModelCompatibilityStatus.SUPPORTED_MODERATE_LOAD else ModelCompatibilityStatus.HIGH_LOAD_DISCOURAGED,
                recommendationReason = if (profile.tier >= DeviceCapacityTier.FLAGSHIP) "Ultra-accurate 29-landmark high-resolution heatmaps." else "High computational load. Recommended on Tier 1 Flagship silicon."
            ),
            ModelHardwareRequirement(
                modelId = "cavaface",
                displayName = "CavaFace Large ArcFace 512-D",
                description = "Heavy 250MB deep neural network for military-grade sub-1-in-1,000,000 biometric matching.",
                fileSizeFormatted = "250 MB",
                minRamGb = 10,
                expectedFps = if (profile.tier == DeviceCapacityTier.ULTRA) "30-50 FPS" else "15-25 FPS",
                targetProcessor = primaryAccelerator,
                compatibilityStatus = if (profile.tier == DeviceCapacityTier.ULTRA) ModelCompatibilityStatus.RECOMMENDED_OPTIMAL else if (profile.tier == DeviceCapacityTier.FLAGSHIP) ModelCompatibilityStatus.SUPPORTED_MODERATE_LOAD else ModelCompatibilityStatus.HIGH_LOAD_DISCOURAGED,
                recommendationReason = if (profile.tier >= DeviceCapacityTier.FLAGSHIP) "Heavy 250MB flagship model for ultra-high security biometric archives." else "Requires Tier 1 silicon and 8GB+ RAM to avoid thermal throttling."
            )
        )
    }
}
