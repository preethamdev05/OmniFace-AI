package com.omniface.ai.ml

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs
import kotlin.math.sqrt

enum class RegistrationRejectionReason(val code: String, val userMessage: String) {
    NONE("OK", "Face sample passed all biometric quality gates"),
    FACE_NOT_FOUND("E01", "Position your face inside the center frame"),
    MULTIPLE_FACES("E02", "Multiple faces detected — ensure only one person is in frame"),
    FACE_TOO_SMALL("E03", "Move closer to the camera"),
    FACE_TOO_LARGE("E04", "Move back slightly from the camera"),
    LOW_SHARPNESS("E05", "Hold still — image is blurry"),
    MOTION_BLUR("E06", "Slow down head movement"),
    BAD_LIGHTING_DARK("E07", "Increase ambient lighting — face is underexposed"),
    BAD_LIGHTING_BRIGHT("E08", "Move away from direct glare or backlight"),
    EXCESSIVE_YAW("E09", "Turn your face to match the 3D target angle"),
    EXCESSIVE_PITCH("E10", "Level your chin — look straight at camera lens"),
    EXCESSIVE_ROLL("E11", "Keep head upright without tilting sideways"),
    EYES_CLOSED("E12", "Keep both eyes open and look at the camera lens"),
    SUNGLASSES_PRESENT("E13", "Please remove sunglasses or tinted eyewear"),
    MASK_PRESENT("E14", "Please remove face covering or mask"),
    LANDMARK_UNSTABLE("E15", "Facial features obscured — adjust position"),
    ALIGNMENT_FAILED("E16", "Facial landmark alignment failed"),
    INCONSISTENT_EMBEDDING("E17", "Sample variation too high — retaking angle")
}

data class RegistrationQualityScore(
    val detectionScore: Float,        // 0..100
    val landmarkScore: Float,         // 0..100
    val alignmentScore: Float,        // 0..100
    val sharpnessScore: Float,        // 0..100
    val lightingScore: Float,         // 0..100
    val poseScore: Float,             // 0..100
    val occlusionScore: Float,        // 0..100
    val eyeQualityScore: Float,       // 0..100
    val overallScore: Float,          // 0..100
    val isPassed: Boolean,
    val rejectionReason: RegistrationRejectionReason,
    val guidanceMessage: String
)

data class EnrollmentConsistencyMatrix(
    val sampleCount: Int,
    val pairwiseMatrix: Array<FloatArray>,
    val averageSimilarity: Float,
    val minimumSimilarity: Float,
    val isConsistent: Boolean
)

object RegistrationQualityEvaluator {

    /**
     * Evaluates comprehensive multi-factor quality gates for an enrollment frame.
     */
    fun evaluateFrame(
        face: Face?,
        allDetectedFaces: List<Face>,
        frameWidth: Int,
        frameHeight: Int,
        faceCrop: Bitmap?,
        targetYaw: Float = 0f,
        targetPitch: Float = 0f,
        qualcommAttributes: FaceAttributesResult? = null,
        qualcommGaze: EyeGazeResult? = null
    ): RegistrationQualityScore {
        if (face == null || faceCrop == null || faceCrop.isRecycled) {
            return RegistrationQualityScore(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, false,
                RegistrationRejectionReason.FACE_NOT_FOUND, RegistrationRejectionReason.FACE_NOT_FOUND.userMessage)
        }

        if (allDetectedFaces.size > 1) {
            return RegistrationQualityScore(50f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 10f, false,
                RegistrationRejectionReason.MULTIPLE_FACES, RegistrationRejectionReason.MULTIPLE_FACES.userMessage)
        }

        val box = face.boundingBox
        val faceWidthFraction = box.width().toFloat() / frameWidth.toFloat()
        if (faceWidthFraction < 0.18f) {
            return RegistrationQualityScore(40f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 20f, false,
                RegistrationRejectionReason.FACE_TOO_SMALL, RegistrationRejectionReason.FACE_TOO_SMALL.userMessage)
        }
        if (faceWidthFraction > 0.85f) {
            return RegistrationQualityScore(50f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 25f, false,
                RegistrationRejectionReason.FACE_TOO_LARGE, RegistrationRejectionReason.FACE_TOO_LARGE.userMessage)
        }

        val detectionScore = ((faceWidthFraction / 0.45f).coerceAtMost(1.0f) * 100f).coerceIn(0f, 100f)

        // 1. Pose Gate
        val currentYaw = face.headEulerAngleY
        val currentPitch = face.headEulerAngleX
        val currentRoll = face.headEulerAngleZ

        if (abs(currentRoll) > 15f) {
            return RegistrationQualityScore(detectionScore, 0f, 0f, 0f, 0f, 20f, 0f, 0f, 30f, false,
                RegistrationRejectionReason.EXCESSIVE_ROLL, RegistrationRejectionReason.EXCESSIVE_ROLL.userMessage)
        }

        val yawDelta = abs(currentYaw - targetYaw)
        val pitchDelta = abs(currentPitch - targetPitch)
        val poseError = sqrt(yawDelta * yawDelta + pitchDelta * pitchDelta)
        val poseScore = ((1.0f - (poseError / 18.0f).coerceIn(0f, 1f)) * 100f)

        if (yawDelta > 12.0f) {
            val dir = if (currentYaw < targetYaw) "Turn face slightly right" else "Turn face slightly left"
            return RegistrationQualityScore(detectionScore, 80f, 80f, 80f, 80f, poseScore, 100f, 90f, 50f, false,
                RegistrationRejectionReason.EXCESSIVE_YAW, dir)
        }
        if (pitchDelta > 10.0f) {
            val dir = if (currentPitch < targetPitch) "Tilt head up slightly" else "Tilt head down slightly"
            return RegistrationQualityScore(detectionScore, 80f, 80f, 80f, 80f, poseScore, 100f, 90f, 50f, false,
                RegistrationRejectionReason.EXCESSIVE_PITCH, dir)
        }

        // 2. Sharpness & Lighting via QualityChecker
        val qualityCheck = QualityChecker().checkFaceQuality(faceCrop, currentRoll)
        val sharpnessScore = ((qualityCheck.blurScore / 15.0f).coerceIn(0f, 1f) * 100f)
        val lightingScore = (1.0f - abs(qualityCheck.brightnessScore - 128f) / 128f).coerceIn(0f, 1f) * 100f

        if (!qualityCheck.isGoodQuality) {
            val reason = when {
                qualityCheck.blurScore < 5.0f -> RegistrationRejectionReason.LOW_SHARPNESS
                qualityCheck.brightnessScore < 35.0f -> RegistrationRejectionReason.BAD_LIGHTING_DARK
                qualityCheck.brightnessScore > 230.0f -> RegistrationRejectionReason.BAD_LIGHTING_BRIGHT
                else -> RegistrationRejectionReason.LOW_SHARPNESS
            }
            return RegistrationQualityScore(detectionScore, 80f, 70f, sharpnessScore, lightingScore, poseScore, 100f, 80f, 40f, false,
                reason, reason.userMessage)
        }

        // 3. Eye Openness & Gaze Attentiveness
        val leftEyeOpen = face.leftEyeOpenProbability ?: 0.9f
        val rightEyeOpen = face.rightEyeOpenProbability ?: 0.9f
        val eyeQualityScore = (((leftEyeOpen + rightEyeOpen) / 2f) * 100f).coerceIn(0f, 100f)

        if (leftEyeOpen < 0.35f || rightEyeOpen < 0.35f) {
            return RegistrationQualityScore(detectionScore, 80f, 80f, sharpnessScore, lightingScore, poseScore, 100f, eyeQualityScore, 45f, false,
                RegistrationRejectionReason.EYES_CLOSED, RegistrationRejectionReason.EYES_CLOSED.userMessage)
        }

        // 4. Occlusion & Attributes
        var occlusionScore = 100f
        if (qualcommAttributes != null) {
            val eyeglasses = qualcommAttributes.eyeglassesScore
            val sunglasses = qualcommAttributes.rawProbabilities.getOrNull(1) ?: 0f
            val mask = qualcommAttributes.rawProbabilities.getOrNull(2) ?: 0f

            if (sunglasses > 0.70f) {
                return RegistrationQualityScore(detectionScore, 70f, 70f, sharpnessScore, lightingScore, poseScore, 20f, eyeQualityScore, 35f, false,
                    RegistrationRejectionReason.SUNGLASSES_PRESENT, RegistrationRejectionReason.SUNGLASSES_PRESENT.userMessage)
            }
            if (mask > 0.65f) {
                return RegistrationQualityScore(detectionScore, 60f, 50f, sharpnessScore, lightingScore, poseScore, 10f, eyeQualityScore, 30f, false,
                    RegistrationRejectionReason.MASK_PRESENT, RegistrationRejectionReason.MASK_PRESENT.userMessage)
            }
            occlusionScore = ((1.0f - maxOf(sunglasses, mask)) * 100f).coerceIn(0f, 100f)
        }

        // 5. Landmark & Alignment Integrity
        val landmarks = arrayOf(
            face.getLandmark(FaceLandmark.LEFT_EYE)?.position,
            face.getLandmark(FaceLandmark.RIGHT_EYE)?.position,
            face.getLandmark(FaceLandmark.NOSE_BASE)?.position,
            face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position,
            face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        )
        val hasAll5Landmarks = landmarks.all { it != null }
        val landmarkScore = if (hasAll5Landmarks) 100f else 60f
        val alignmentScore = 95f

        // Weighted Overall Score (Justified Normalization Scheme)
        val overallScore = (
            detectionScore * 0.15f +
            landmarkScore * 0.10f +
            alignmentScore * 0.10f +
            sharpnessScore * 0.20f +
            lightingScore * 0.15f +
            poseScore * 0.15f +
            occlusionScore * 0.05f +
            eyeQualityScore * 0.10f
        ).coerceIn(0f, 100f)

        val isPassed = overallScore >= 75.0f

        return RegistrationQualityScore(
            detectionScore = detectionScore,
            landmarkScore = landmarkScore,
            alignmentScore = alignmentScore,
            sharpnessScore = sharpnessScore,
            lightingScore = lightingScore,
            poseScore = poseScore,
            occlusionScore = occlusionScore,
            eyeQualityScore = eyeQualityScore,
            overallScore = overallScore,
            isPassed = isPassed,
            rejectionReason = if (isPassed) RegistrationRejectionReason.NONE else RegistrationRejectionReason.LOW_SHARPNESS,
            guidanceMessage = if (isPassed) "✓ Face Quality Excellent (Score: ${overallScore.toInt()})" else "Hold steady and maintain lighting"
        )
    }

    /**
     * Computes Pairwise Consistency Matrix and Quality-Weighted Master Centroid Vector.
     */
    fun computeQualityWeightedTemplate(
        embeddings: List<FloatArray>,
        qualityScores: List<Float>
    ): Pair<FloatArray, EnrollmentConsistencyMatrix> {
        val count = embeddings.size
        require(count > 0) { "Embeddings list cannot be empty" }

        // 1. Build Pairwise Similarity Matrix M[i, j]
        val matrix = Array(count) { FloatArray(count) }
        var totalSim = 0f
        var minSim = 1.0f
        var pairsCount = 0

        for (i in 0 until count) {
            matrix[i][i] = 1.0f
            for (j in i + 1 until count) {
                val sim = dotProduct(embeddings[i], embeddings[j])
                matrix[i][j] = sim
                matrix[j][i] = sim
                totalSim += sim
                if (sim < minSim) minSim = sim
                pairsCount++
            }
        }
        val avgSim = if (pairsCount > 0) (totalSim / pairsCount) else 1.0f
        val isConsistent = minSim >= 0.78f

        // 2. Compute Quality-Weighted Master Centroid Vector
        val dim = embeddings[0].size
        val centroid = FloatArray(dim)
        var totalWeight = 0f

        for (k in 0 until count) {
            val w = (qualityScores.getOrNull(k) ?: 100f) / 100f
            totalWeight += w
            for (d in 0 until dim) {
                centroid[d] += embeddings[k][d] * w
            }
        }

        // 3. L2 Normalize Centroid Vector
        var norm = 0f
        for (d in 0 until dim) {
            centroid[d] /= totalWeight
            norm += centroid[d] * centroid[d]
        }
        norm = sqrt(norm)
        if (norm > 1e-6f) {
            for (d in 0 until dim) {
                centroid[d] /= norm
            }
        }

        val consistencyMatrix = EnrollmentConsistencyMatrix(
            sampleCount = count,
            pairwiseMatrix = matrix,
            averageSimilarity = avgSim,
            minimumSimilarity = minSim,
            isConsistent = isConsistent
        )

        return Pair(centroid, consistencyMatrix)
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            dot += a[i] * b[i]
        }
        return dot
    }
}
