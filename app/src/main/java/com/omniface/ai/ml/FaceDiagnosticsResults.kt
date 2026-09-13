package com.omniface.ai.ml

/**
 * Facial Surface Topography and 3D Morphable Model (3DMM) representation.
 */
data class FaceMap3DMMResult(
    val parameters265: FloatArray = FloatArray(265),
    val depthVariance: Float = 0.0f,
    val isTrue3DSurface: Boolean = true,
    val executionTimeMs: Float = 0.0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FaceMap3DMMResult
        return isTrue3DSurface == other.isTrue3DSurface && depthVariance == other.depthVariance
    }

    override fun hashCode(): Int {
        var result = depthVariance.hashCode()
        result = 31 * result + isTrue3DSurface.hashCode()
        return result
    }
}

/**
 * Facial Attribute Analysis (expressions, occlusions, eye states).
 */
data class FaceAttributesResult(
    val smileScore: Float = 0.0f,
    val eyeglassesScore: Float = 0.0f,
    val poseYawScore: Float = 0.0f,
    val rawProbabilities: FloatArray = FloatArray(5),
    val executionTimeMs: Float = 0.0f,
    val leftEyeOpenScore: Float = 1.0f,
    val rightEyeOpenScore: Float = 1.0f,
    val maskScore: Float = 0.0f,
    val sunglassesScore: Float = 0.0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FaceAttributesResult
        return maskScore == other.maskScore && sunglassesScore == other.sunglassesScore
    }

    override fun hashCode(): Int {
        var result = maskScore.hashCode()
        result = 31 * result + sunglassesScore.hashCode()
        return result
    }
}

/**
 * Optical Eye Gaze and Attention Tracking Vectors.
 */
data class EyeGazeResult(
    val pitch: Float = 0.0f,
    val yaw: Float = 0.0f,
    val gazeVectorNorm: Float = 0.0f,
    val eyeLandmarks34x2: Array<FloatArray> = emptyArray(),
    val isGazeAttentive: Boolean = true,
    val executionTimeMs: Float = 0.0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EyeGazeResult
        return pitch == other.pitch && yaw == other.yaw && isGazeAttentive == other.isGazeAttentive
    }

    override fun hashCode(): Int {
        var result = pitch.hashCode()
        result = 31 * result + yaw.hashCode()
        result = 31 * result + isGazeAttentive.hashCode()
        return result
    }
}

/**
 * High-Resolution Facial Landmark Heatmaps.
 */
data class HRNetFaceResult(
    val landmarks29x2: Array<FloatArray> = emptyArray(),
    val landmarkConfidences: FloatArray = FloatArray(0),
    val executionTimeMs: Float = 0.0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HRNetFaceResult
        return executionTimeMs == other.executionTimeMs
    }

    override fun hashCode(): Int {
        return executionTimeMs.hashCode()
    }
}

/**
 * Dense 468-point 3D Facial Mesh Topology.
 */
data class MediaPipeMeshResult(
    val landmarks468x3: Array<FloatArray> = emptyArray(),
    val faceScore: Float = 1.0f,
    val meshDepthVariance: Float = 0.0f,
    val executionTimeMs: Float = 0.0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MediaPipeMeshResult
        return faceScore == other.faceScore && meshDepthVariance == other.meshDepthVariance
    }

    override fun hashCode(): Int {
        var result = faceScore.hashCode()
        result = 31 * result + meshDepthVariance.hashCode()
        return result
    }
}
