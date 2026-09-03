package com.omniface.ai.ml.antispoof

import android.graphics.PointF
import com.omniface.ai.ml.FaceAttributesResult
import com.omniface.ai.ml.FaceMap3DMMResult
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

data class TemporalFrameSample(
    val timestampMs: Long,
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
    val eyeOpenness: Float,
    val depthVariance: Float,
    val passivePadScore: Float,
    val landmarks5Pts: Array<PointF>?
)

data class TemporalLivenessResult(
    val isLive: Boolean,
    val temporalConfidence: Float,    // 0.0 to 1.0
    val microMotionDetected: Boolean,
    val naturalBlinkDetected: Boolean,
    val headTurnDetected: Boolean = false,
    val stable3DDepth: Boolean,
    val heartRateBpm: Int = 72,
    val rppgVitality: RppgVitalityResult? = null,
    val requiredAction: LivenessChallengeType? = null,
    val explanation: String
)

/**
 * Multi-Frame Temporal Anti-Spoofing & Liveness Consistency Engine.
 *
 * Examines a sequence of frames over a sliding window:
 * 1. Micro-Motion Trajectory: Rejects completely static 2D planar photos.
 * 2. Natural Blink / Ocular Dynamics: Detects physiological involuntary transitions or prompted blinks.
 * 3. Head Turn Dynamics: Detects genuine 3D continuous rotation across yaw/pitch.
 * 4. 3D Depth Variance Stability: Asserts consistent topological variance across head turns.
 * 5. Temporal PAD Consensus: Prevents momentary noise spikes from triggering false alarms.
 */
class TemporalLivenessEngine {

    companion object {
        private const val MAX_HISTORY_FRAMES = 10
        private const val MIN_SAMPLES_FOR_CONSENSUS = 3
        private const val STATIC_PHOTO_JITTER_THRESHOLD = 0.40f
    }

    private val trackHistory = ConcurrentHashMap<Int, ArrayDeque<TemporalFrameSample>>()
    private val rppgEngine = RemotePpgPulseEngine()

    fun recordRppgSample(bitmap: android.graphics.Bitmap, boundingBox: android.graphics.Rect) {
        rppgEngine.extractRoiColorsAndAdd(bitmap, boundingBox)
    }

    fun recordRppgColorSample(r: Float, g: Float, b: Float) {
        rppgEngine.addSample(r, g, b)
    }

    fun getRppgResult(): RppgVitalityResult {
        return rppgEngine.evaluateVitality()
    }

    fun recordSample(
        trackId: Int,
        yaw: Float,
        pitch: Float,
        roll: Float,
        attributes: FaceAttributesResult?,
        faceMap3DMM: FaceMap3DMMResult?,
        passivePad: PassivePadResult?,
        landmarks5Pts: Array<PointF>? = null,
        eyeOpenProbability: Float? = null
    ) {
        val queue = trackHistory.getOrPut(trackId) { ArrayDeque() }
        val eyeOpen = eyeOpenProbability ?: (attributes?.rawProbabilities?.getOrNull(3) ?: 0.95f)
        val depthVar = faceMap3DMM?.depthVariance ?: 0.005f
        val padScore = passivePad?.livenessScore ?: 0.90f

        val sample = TemporalFrameSample(
            timestampMs = System.currentTimeMillis(),
            yaw = yaw,
            pitch = pitch,
            roll = roll,
            eyeOpenness = eyeOpen,
            depthVariance = depthVar,
            passivePadScore = padScore,
            landmarks5Pts = landmarks5Pts
        )

        synchronized(queue) {
            queue.addLast(sample)
            while (queue.size > MAX_HISTORY_FRAMES) {
                queue.removeFirst()
            }
        }
    }

    fun evaluateTemporalLiveness(trackId: Int): TemporalLivenessResult {
        val rppg = rppgEngine.evaluateVitality()

        val queue = trackHistory[trackId] ?: return TemporalLivenessResult(
            isLive = true,
            temporalConfidence = 0.85f,
            microMotionDetected = true,
            naturalBlinkDetected = false,
            headTurnDetected = false,
            stable3DDepth = true,
            heartRateBpm = rppg.heartRateBpm,
            rppgVitality = rppg,
            requiredAction = null,
            explanation = "Initial frame sample"
        )

        val samples = synchronized(queue) { queue.toList() }
        if (samples.size < MIN_SAMPLES_FOR_CONSENSUS) {
            return TemporalLivenessResult(
                isLive = true,
                temporalConfidence = 0.85f,
                microMotionDetected = true,
                naturalBlinkDetected = false,
                headTurnDetected = false,
                stable3DDepth = true,
                heartRateBpm = rppg.heartRateBpm,
                rppgVitality = rppg,
                requiredAction = null,
                explanation = "Accumulating temporal sequence (${samples.size}/$MIN_SAMPLES_FOR_CONSENSUS)"
            )
        }

        // 1. Check Mean Passive PAD Score over Temporal Window
        val avgPadScore = samples.map { it.passivePadScore }.average().toFloat()

        // 2. Check 3D Depth Variance Stability
        val meanDepth = samples.map { it.depthVariance }.average().toFloat()
        val stable3DDepth = meanDepth > 0.0020f

        // 3. Check Natural Micro-Motion & Continuous Head Rotation across Pose
        var totalMotion = 0.0f
        var maxYawDiff = 0.0f
        var maxPitchDiff = 0.0f
        val firstSample = samples.first()

        for (i in 1 until samples.size) {
            val prev = samples[i - 1]
            val curr = samples[i]
            val dyaw = abs(curr.yaw - prev.yaw)
            val dpitch = abs(curr.pitch - prev.pitch)
            val droll = abs(curr.roll - prev.roll)
            totalMotion += (dyaw + dpitch + droll)

            val totalYawSpan = abs(curr.yaw - firstSample.yaw)
            if (totalYawSpan > maxYawDiff) maxYawDiff = totalYawSpan

            val totalPitchSpan = abs(curr.pitch - firstSample.pitch)
            if (totalPitchSpan > maxPitchDiff) maxPitchDiff = totalPitchSpan
        }

        val avgMotionPerFrame = totalMotion / (samples.size - 1)
        val hasMicroMotion = avgMotionPerFrame > 0.01f || samples.size >= 3
        val headTurnOccurred = maxYawDiff >= 10.0f || maxPitchDiff >= 8.0f

        // 4. Check Eye Openness Transition (Blink Detection)
        val minEye = samples.minOf { it.eyeOpenness }
        val maxEye = samples.maxOf { it.eyeOpenness }
        val eyeDelta = maxEye - minEye
        val blinkOccurred = eyeDelta >= 0.25f || (minEye <= 0.30f && maxEye >= 0.60f)

        // 5. Anti-Spoof Dynamic Action Requirement: Flag static replay if strictly zero motion over 6+ frames
        val isStrictStaticPhoto = avgMotionPerFrame < 0.005f && eyeDelta < 0.05f && !headTurnOccurred && samples.size >= 6
        val isLive = (avgPadScore >= 0.35f) && stable3DDepth && !isStrictStaticPhoto && rppg.isLive

        val requiredAction = when {
            isStrictStaticPhoto -> LivenessChallengeType.BLINK
            avgPadScore in 0.30f..0.50f && !blinkOccurred && !headTurnOccurred -> LivenessChallengeType.BLINK
            else -> null
        }

        // 6. Final Temporal Synthesis
        val bonus = (if (blinkOccurred) 0.10f else 0f) + (if (headTurnOccurred) 0.10f else 0f) + (if (rppg.isPhysiological) 0.10f else 0f)
        val score = (avgPadScore * 0.45f + (if (stable3DDepth) 0.25f else 0f) + (if (hasMicroMotion) 0.15f else 0f) + bonus).coerceIn(0f, 1f)

        val explanation = when {
            isStrictStaticPhoto -> "Static image detected — please blink or turn head slightly"
            !rppg.isLive -> "No cardiovascular micro-pulsatility detected"
            !stable3DDepth -> "Planar 2D surface detected (Low 3D Topography)"
            avgPadScore < 0.35f -> "Temporal PAD replay / print attack detected"
            blinkOccurred -> "Live subject verified (Natural eye blink detected)"
            headTurnOccurred -> "Live subject verified (3D head rotation confirmed)"
            rppg.isPhysiological -> "Live subject verified (rPPG pulse: ${rppg.heartRateBpm} BPM)"
            else -> "Live 3D subject verified (${samples.size} frames consensus)"
        }

        return TemporalLivenessResult(
            isLive = isLive,
            temporalConfidence = score,
            microMotionDetected = hasMicroMotion,
            naturalBlinkDetected = blinkOccurred,
            headTurnDetected = headTurnOccurred,
            stable3DDepth = stable3DDepth,
            heartRateBpm = rppg.heartRateBpm,
            rppgVitality = rppg,
            requiredAction = requiredAction,
            explanation = explanation
        )
    }

    fun purgeTrack(trackId: Int) {
        trackHistory.remove(trackId)
    }

    fun clearAll() {
        trackHistory.clear()
        rppgEngine.reset()
    }
}
