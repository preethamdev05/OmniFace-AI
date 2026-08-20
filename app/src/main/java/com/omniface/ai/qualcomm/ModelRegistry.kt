package com.omniface.ai.qualcomm

import android.content.Context
import java.io.File

enum class ModelRole {
    RECOGNITION_EMBEDDING,
    LANDMARK_GEOMETRY,
    ATTRIBUTE_CLASSIFIER,
    EYE_GAZE_DIRECTION,
    HEATMAP_LANDMARKS,
    FACE_DETECTION
}

data class QualcommModelSpec(
    val modelId: String,
    val displayName: String,
    val releaseVersion: String = "v0.60.0",
    val role: ModelRole,
    val inputShape: List<Int>,
    val outputShape: List<Int>,
    val quantization: String = "float32",
    val parameterCount: String,
    val memoryRequirementMb: Float,
    val supportedChipsets: List<SnapdragonChipset>,
    val fallbackModelId: String,
    val officialAiHubUrl: String,
    val githubUrl: String
)

object ModelRegistry {

    val CAVAFACE = QualcommModelSpec(
        modelId = "cavaface",
        displayName = "Qualcomm CavaFace IR-SE-100 HD",
        role = ModelRole.RECOGNITION_EMBEDDING,
        inputShape = listOf(1, 112, 112, 3),
        outputShape = listOf(1, 512),
        parameterCount = "65.5M",
        memoryRequirementMb = 250.0f,
        supportedChipsets = listOf(
            SnapdragonChipset.SNAPDRAGON_8_ELITE_GEN5,
            SnapdragonChipset.SNAPDRAGON_8_ELITE,
            SnapdragonChipset.SNAPDRAGON_8_GEN3,
            SnapdragonChipset.SNAPDRAGON_8_GEN2,
            SnapdragonChipset.SNAPDRAGON_8_GEN1,
            SnapdragonChipset.SNAPDRAGON_888
        ),
        fallbackModelId = "mobilefacenet_512d_fp16",
        officialAiHubUrl = "https://aihub.qualcomm.com/mobile/models/cavaface",
        githubUrl = "https://github.com/qualcomm/ai-hub-models/tree/v0.60.0/src/qai_hub_models/models/cavaface"
    )

    val FACEMAP_3DMM = QualcommModelSpec(
        modelId = "facemap_3dmm",
        displayName = "Qualcomm FaceMap 3DMM (Landmark Geometry)",
        role = ModelRole.LANDMARK_GEOMETRY,
        inputShape = listOf(1, 128, 128, 3),
        outputShape = listOf(1, 265),
        parameterCount = "5.4M",
        memoryRequirementMb = 20.7f,
        supportedChipsets = listOf(
            SnapdragonChipset.SNAPDRAGON_8_ELITE_GEN5,
            SnapdragonChipset.SNAPDRAGON_8_ELITE,
            SnapdragonChipset.SNAPDRAGON_8_GEN3,
            SnapdragonChipset.SNAPDRAGON_8_GEN2,
            SnapdragonChipset.SNAPDRAGON_8_GEN1,
            SnapdragonChipset.SNAPDRAGON_888
        ),
        fallbackModelId = "mlkit_landmarks",
        officialAiHubUrl = "https://aihub.qualcomm.com/mobile/models/facemap_3dmm",
        githubUrl = "https://github.com/qualcomm/ai-hub-models/tree/v0.60.0/src/qai_hub_models/models/facemap_3dmm"
    )

    val FACE_ATTRIB_NET = QualcommModelSpec(
        modelId = "face_attrib_net",
        displayName = "Qualcomm Face Attribute Network",
        role = ModelRole.ATTRIBUTE_CLASSIFIER,
        inputShape = listOf(1, 128, 128, 3),
        outputShape = listOf(1, 5),
        parameterCount = "12.1M",
        memoryRequirementMb = 42.0f,
        supportedChipsets = listOf(
            SnapdragonChipset.SNAPDRAGON_8_ELITE_GEN5,
            SnapdragonChipset.SNAPDRAGON_8_ELITE,
            SnapdragonChipset.SNAPDRAGON_8_GEN3,
            SnapdragonChipset.SNAPDRAGON_8_GEN2,
            SnapdragonChipset.SNAPDRAGON_8_GEN1,
            SnapdragonChipset.SNAPDRAGON_888
        ),
        fallbackModelId = "mlkit_attributes",
        officialAiHubUrl = "https://aihub.qualcomm.com/mobile/models/face_attrib_net",
        githubUrl = "https://github.com/qualcomm/ai-hub-models/tree/v0.60.0/src/qai_hub_models/models/face_attrib_net"
    )

    val EYEGAZE = QualcommModelSpec(
        modelId = "eyegaze",
        displayName = "Qualcomm EyeGaze (Pupil Vector & Attentiveness)",
        role = ModelRole.EYE_GAZE_DIRECTION,
        inputShape = listOf(1, 96, 160),
        outputShape = listOf(1, 2),
        parameterCount = "2.5M",
        memoryRequirementMb = 9.7f,
        supportedChipsets = listOf(
            SnapdragonChipset.SNAPDRAGON_8_ELITE_GEN5,
            SnapdragonChipset.SNAPDRAGON_8_ELITE,
            SnapdragonChipset.SNAPDRAGON_8_GEN3,
            SnapdragonChipset.SNAPDRAGON_8_GEN2,
            SnapdragonChipset.SNAPDRAGON_8_GEN1,
            SnapdragonChipset.SNAPDRAGON_888
        ),
        fallbackModelId = "heuristic_gaze",
        officialAiHubUrl = "https://aihub.qualcomm.com/mobile/models/eyegaze",
        githubUrl = "https://github.com/qualcomm/ai-hub-models/tree/v0.60.0/src/qai_hub_models/models/eyegaze"
    )

    val HRNET_FACE = QualcommModelSpec(
        modelId = "hrnet_face",
        displayName = "Qualcomm HRNetFace (29 Landmark Heatmaps)",
        role = ModelRole.HEATMAP_LANDMARKS,
        inputShape = listOf(1, 256, 256, 3),
        outputShape = listOf(1, 29, 64, 64),
        parameterCount = "9.6M",
        memoryRequirementMb = 36.9f,
        supportedChipsets = listOf(
            SnapdragonChipset.SNAPDRAGON_8_ELITE_GEN5,
            SnapdragonChipset.SNAPDRAGON_8_ELITE,
            SnapdragonChipset.SNAPDRAGON_8_GEN3,
            SnapdragonChipset.SNAPDRAGON_8_GEN2,
            SnapdragonChipset.SNAPDRAGON_8_GEN1,
            SnapdragonChipset.SNAPDRAGON_888
        ),
        fallbackModelId = "mlkit_landmarks",
        officialAiHubUrl = "https://aihub.qualcomm.com/mobile/models/hrnet_face",
        githubUrl = "https://github.com/qualcomm/ai-hub-models/tree/v0.60.0/src/qai_hub_models/models/hrnet_face"
    )

    val MEDIAPIPE_FACE = QualcommModelSpec(
        modelId = "mediapipe_face",
        displayName = "Qualcomm MediaPipe Face (Detector + 468 3D Mesh)",
        role = ModelRole.FACE_DETECTION,
        inputShape = listOf(1, 256, 256, 3),
        outputShape = listOf(1, 468, 3),
        parameterCount = "0.7M",
        memoryRequirementMb = 2.9f,
        supportedChipsets = listOf(
            SnapdragonChipset.SNAPDRAGON_8_ELITE_GEN5,
            SnapdragonChipset.SNAPDRAGON_8_ELITE,
            SnapdragonChipset.SNAPDRAGON_8_GEN3,
            SnapdragonChipset.SNAPDRAGON_8_GEN2,
            SnapdragonChipset.SNAPDRAGON_8_GEN1,
            SnapdragonChipset.SNAPDRAGON_888
        ),
        fallbackModelId = "mlkit_face",
        officialAiHubUrl = "https://aihub.qualcomm.com/mobile/models/mediapipe_face",
        githubUrl = "https://github.com/qualcomm/ai-hub-models/tree/v0.60.0/src/qai_hub_models/models/mediapipe_face"
    )

    val ALL_MODELS = listOf(CAVAFACE, FACEMAP_3DMM, FACE_ATTRIB_NET, EYEGAZE, HRNET_FACE, MEDIAPIPE_FACE)

    fun resolveArtifactFile(context: Context, spec: QualcommModelSpec): File? {
        // Path 1: App Sandbox private external storage
        val extDir = context.getExternalFilesDir("models/qualcomm_suite/${spec.modelId}")
        if (extDir != null) {
            val primaryFile = File(extDir, "${spec.modelId}.tflite")
            if (primaryFile.exists() && primaryFile.canRead() && primaryFile.length() > 1024) return primaryFile
            val directFile = File(extDir, "${spec.modelId}-tflite-float/${spec.modelId}.tflite")
            if (directFile.exists() && directFile.canRead() && directFile.length() > 1024) return directFile
            val meshFile = File(extDir, "${spec.modelId}-tflite-float/face_landmark_detector.tflite")
            if (meshFile.exists() && meshFile.canRead() && meshFile.length() > 1024) return meshFile
        }

        // Path 2: Pre-placed ADB / Storage paths
        val legacy1 = File("/storage/emulated/0/AI-HUB/FR/models/qualcomm_suite/${spec.modelId}/${spec.modelId}-tflite-float/${spec.modelId}.tflite")
        if (legacy1.exists() && legacy1.canRead()) return legacy1

        val legacy2 = File("/storage/emulated/0/AI-HUB/FR/models/qualcomm_${spec.modelId}/${spec.modelId}-tflite-float/${spec.modelId}.tflite")
        if (legacy2.exists() && legacy2.canRead()) return legacy2

        val legacy3 = File("/storage/emulated/0/AI-HUB/FR/models/qualcomm_suite/${spec.modelId}/${spec.modelId}.tflite")
        if (legacy3.exists() && legacy3.canRead()) return legacy3

        val legacyMesh = File("/storage/emulated/0/AI-HUB/FR/models/qualcomm_suite/mediapipe_face/mediapipe_face-tflite-float/face_landmark_detector.tflite")
        if (spec.modelId == "mediapipe_face" && legacyMesh.exists() && legacyMesh.canRead()) return legacyMesh

        return null
    }
}
