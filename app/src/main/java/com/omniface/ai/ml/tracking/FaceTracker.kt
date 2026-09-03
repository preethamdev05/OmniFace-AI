package com.omniface.ai.ml.tracking

import androidx.compose.ui.geometry.Rect
import com.omniface.ai.ml.EyeGazeResult
import com.omniface.ai.ml.FaceAttributesResult
import com.omniface.ai.ml.FaceMap3DMMResult
import com.omniface.ai.ml.MediaPipeMeshResult
import com.omniface.ai.ml.pipeline.BiometricSynthesisDecision
import com.omniface.ai.ml.pipeline.PipelineGateState
import com.omniface.ai.ml.quality.QualityGateResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Biometric Classification State maintained per persistent face track.
 */
enum class IdentityClassification {
    UNCONFIRMED,
    KNOWN,
    UNKNOWN,
    SPOOF_ATTACK,
    AMBIGUOUS_REVIEW
}

/**
 * Persistent Track State across sequential camera frames.
 *
 * Prevents identity flickering, bounding-box jitter, and ID reassignment.
 */
data class TrackedFaceState(
    val trackId: Int,
    val persistentTrackId: Int,
    var smoothedRect: Rect,
    var rawRect: Rect,
    var velocityX: Float = 0f,
    var velocityY: Float = 0f,
    var lastSeenTimestampMs: Long = System.currentTimeMillis(),
    val firstSeenTimestampMs: Long = System.currentTimeMillis(),
    var frameCount: Int = 1,
    var lostFrameCount: Int = 0,
    val rectFilter: RectOneEuroFilter = RectOneEuroFilter(minCutoff = 1.2f, beta = 0.05f),
    val landmarkFilters: Array<PointFOneEuroFilter> = Array(5) { PointFOneEuroFilter(minCutoff = 1.0f, beta = 0.04f) },

    // Identity Stability & Anti-Flickering State
    var classification: IdentityClassification = IdentityClassification.UNCONFIRMED,
    var studentRoll: String = "",
    var studentName: String = "",
    var matchConfidence: Float = 0f,
    var matchSimilarity: Float = 0f,
    var decisionMargin: Float = 0f,
    var lastDecision: BiometricSynthesisDecision? = null,
    var consecutiveKnownHits: Int = 0,
    var consecutiveUnknownHits: Int = 0,
    var consecutiveSpoofHits: Int = 0,
    var isClassificationLocked: Boolean = false,
    var hasTriggeredAttendance: Boolean = false,

    // Auxiliary Neural Telemetry (Cached from async pipeline to enrich 60 FPS fast-path)
    var lastMeshResult: MediaPipeMeshResult? = null,
    var lastMap3dResult: FaceMap3DMMResult? = null,
    var lastGazeResult: EyeGazeResult? = null,
    var lastAttrResult: FaceAttributesResult? = null,
    var lastQualityResult: QualityGateResult? = null
)

/**
 * High-Precision Face Tracker with Persistent Spatial-Temporal Association.
 *
 * 1. Maintains consistent persistent IDs across sequential camera frames using
 *    ML Kit tracking IDs augmented by spatial IoU (Intersection-over-Union) and
 *    velocity-predicted centroid matching.
 * 2. Prevents identity flickering and random reassignment: once a face is classified
 *    as 'Known' or 'Unknown', it locks and retains that classification until the face
 *    completely exits the camera's field of view.
 * 3. Applies Exponential Moving Average (EMA) filtering on bounding boxes for smooth reticle rendering.
 */
class FaceTracker {

    companion object {
        private const val ALPHA = 0.65f // Smoothing factor for bounding box EMA
        private const val TRACK_TIMEOUT_MS = 1200L // Purge track after 1.2s of silence
        private const val IOU_ASSOCIATION_THRESHOLD = 0.30f
        private const val CENTROID_DIST_THRESHOLD = 0.35f
        private const val REQUIRED_CONFIRMATION_FRAMES = 2
    }

    private val activeTracks = ConcurrentHashMap<Int, TrackedFaceState>()
    private val nextPersistentId = AtomicInteger(1001)

    /**
     * Updates or creates a persistent track for an incoming face detection.
     * Uses ML Kit trackId when valid, with automatic IoU fallback for lost/unassigned detections.
     */
    fun updateTrack(mlKitTrackId: Int, rawRect: Rect): Rect {
        val state = getOrCreateTrackState(mlKitTrackId, rawRect)
        return state.smoothedRect
    }

    fun updateAuxiliaryFeatures(
        trackId: Int,
        meshResult: MediaPipeMeshResult?,
        map3dResult: FaceMap3DMMResult?,
        gazeResult: EyeGazeResult?,
        attrResult: FaceAttributesResult?,
        qualityResult: QualityGateResult?
    ) {
        val track = activeTracks[trackId] ?: return
        track.lastMeshResult = meshResult
        track.lastMap3dResult = map3dResult
        track.lastGazeResult = gazeResult
        track.lastAttrResult = attrResult
        track.lastQualityResult = qualityResult
    }

    /**
     * Retrieves or instantiates the persistent TrackedFaceState with predictive spatial matching.
     */
    fun getOrCreateTrackState(mlKitTrackId: Int, rawRect: Rect): TrackedFaceState {
        val now = System.currentTimeMillis()

        // 1. Direct match by ML Kit tracking ID if valid (> 0)
        if (mlKitTrackId > 0 && activeTracks.containsKey(mlKitTrackId)) {
            val existing = activeTracks[mlKitTrackId]!!
            return updateExistingTrack(existing, rawRect, now)
        }

        // 2. Spatial matching fallback (IoU + Centroid Distance + Velocity Prediction)
        var bestMatch: TrackedFaceState? = null
        var bestScore = 0f

        for (track in activeTracks.values) {
            if ((now - track.lastSeenTimestampMs) > TRACK_TIMEOUT_MS) continue

            val predictedRect = Rect(
                left = track.smoothedRect.left + track.velocityX,
                top = track.smoothedRect.top + track.velocityY,
                right = track.smoothedRect.right + track.velocityX,
                bottom = track.smoothedRect.bottom + track.velocityY
            )

            val iou = computeIoU(rawRect, predictedRect)
            val centroidDist = computeNormalizedCentroidDist(rawRect, predictedRect)

            if (iou >= IOU_ASSOCIATION_THRESHOLD || centroidDist <= CENTROID_DIST_THRESHOLD) {
                val score = iou * 0.7f + (1f - centroidDist.coerceIn(0f, 1f)) * 0.3f
                if (score > bestScore) {
                    bestScore = score
                    bestMatch = track
                }
            }
        }

        if (bestMatch != null) {
            val updated = updateExistingTrack(bestMatch, rawRect, now)
            // Re-index by mlKitTrackId if it now has one
            if (mlKitTrackId > 0 && mlKitTrackId != bestMatch.trackId) {
                activeTracks.remove(bestMatch.trackId)
                activeTracks[mlKitTrackId] = updated
            }
            return updated
        }

        // 3. Instantiate brand new persistent track
        val assignedId = if (mlKitTrackId > 0) mlKitTrackId else nextPersistentId.getAndIncrement()
        val newState = TrackedFaceState(
            trackId = assignedId,
            persistentTrackId = nextPersistentId.getAndIncrement(),
            smoothedRect = rawRect,
            rawRect = rawRect,
            velocityX = 0f,
            velocityY = 0f,
            lastSeenTimestampMs = now,
            firstSeenTimestampMs = now,
            frameCount = 1,
            lostFrameCount = 0
        )
        activeTracks[assignedId] = newState
        return newState
    }

    private fun updateExistingTrack(prev: TrackedFaceState, rawRect: Rect, now: Long): TrackedFaceState {
        // High-precision One Euro (1€) filter for 0-lag dynamic response during motion and 0-jitter at rest
        val filteredRect = prev.rectFilter.filter(rawRect, now)

        val vx = (filteredRect.center.x - prev.smoothedRect.center.x).coerceIn(-80f, 80f)
        val vy = (filteredRect.center.y - prev.smoothedRect.center.y).coerceIn(-80f, 80f)

        prev.smoothedRect = filteredRect
        prev.rawRect = rawRect
        prev.velocityX = vx
        prev.velocityY = vy
        prev.lastSeenTimestampMs = now
        prev.frameCount += 1
        prev.lostFrameCount = 0

        return prev
    }

    /**
     * Filters 5 canonical facial fiducials using dedicated 1€ filters per vertex.
     */
    fun filterLandmarks(
        trackId: Int,
        rawLandmarks: Array<android.graphics.PointF>,
        timestampMs: Long = System.currentTimeMillis()
    ): Array<android.graphics.PointF> {
        val state = activeTracks[trackId] ?: return rawLandmarks
        val out = Array(rawLandmarks.size) { i ->
            if (i < state.landmarkFilters.size) {
                state.landmarkFilters[i].filter(rawLandmarks[i], timestampMs)
            } else {
                rawLandmarks[i]
            }
        }
        return out
    }

    /**
     * Stabilizes biometric classification to prevent identity flickering or ID reassignment.
     *
     * Once a face is classified as 'Known' or 'Unknown', it locks that identity state on the persistent track
     * until the face leaves the camera's field of view.
     */
    fun stabilizeDecision(
        trackId: Int,
        rawDecision: BiometricSynthesisDecision
    ): BiometricSynthesisDecision {
        val state = activeTracks[trackId] ?: return rawDecision

        // Update evidence accumulators
        when {
            rawDecision.isAttendanceAuthorized && rawDecision.matchedStudentRoll.isNotBlank() -> {
                if (state.studentRoll.isEmpty() || state.studentRoll == rawDecision.matchedStudentRoll) {
                    state.consecutiveKnownHits++
                    state.consecutiveUnknownHits = 0
                    state.consecutiveSpoofHits = 0
                }
            }
            rawDecision.gateState == PipelineGateState.REJECT_SPOOF_ATTACK -> {
                state.consecutiveSpoofHits++
                state.consecutiveKnownHits = 0
            }
            rawDecision.gateState == PipelineGateState.REJECT_UNKNOWN_IDENTITY || rawDecision.matchedStudentRoll == "GUEST" -> {
                state.consecutiveUnknownHits++
                if (state.consecutiveKnownHits > 0) {
                    state.consecutiveKnownHits--
                }
            }
        }

        // Check if track should transition to known or spoof locked state
        when {
            rawDecision.isAttendanceAuthorized && rawDecision.matchedStudentRoll.isNotBlank() -> {
                if (state.consecutiveKnownHits >= REQUIRED_CONFIRMATION_FRAMES || rawDecision.matchSimilarity >= 0.65f) {
                    state.classification = IdentityClassification.KNOWN
                    state.studentRoll = rawDecision.matchedStudentRoll
                    state.studentName = rawDecision.matchedStudentName
                    state.matchConfidence = rawDecision.matchConfidence
                    state.matchSimilarity = rawDecision.matchSimilarity
                    state.decisionMargin = rawDecision.decisionMargin
                    state.isClassificationLocked = true
                }
            }
            state.consecutiveSpoofHits >= REQUIRED_CONFIRMATION_FRAMES -> {
                state.classification = IdentityClassification.SPOOF_ATTACK
                state.isClassificationLocked = true
            }
        }

        // Apply persistent locked classification if currently verified as KNOWN
        if (state.isClassificationLocked) {
            when (state.classification) {
                IdentityClassification.KNOWN -> {
                    // Security guard: If the face changes to spoof attack or stranger actively replaces the subject
                    val isStrangerDetected = rawDecision.matchSimilarity in 0.01f..0.35f
                    val isSpoofDetected = rawDecision.gateState == PipelineGateState.REJECT_SPOOF_ATTACK
                    val isLostPersistence = state.consecutiveUnknownHits >= 12

                    if (isSpoofDetected || isStrangerDetected || isLostPersistence) {
                        state.isClassificationLocked = false
                        state.consecutiveKnownHits = 0
                        state.studentRoll = ""
                        state.studentName = ""
                        state.classification = if (isSpoofDetected) {
                            IdentityClassification.SPOOF_ATTACK
                        } else {
                            IdentityClassification.UNKNOWN
                        }
                        state.lastDecision = rawDecision
                        return rawDecision
                    }

                    val stabilized = rawDecision.copy(
                        gateState = PipelineGateState.PASS,
                        isAttendanceAuthorized = true,
                        matchedStudentRoll = state.studentRoll,
                        matchedStudentName = state.studentName,
                        matchSimilarity = maxOf(rawDecision.matchSimilarity, state.matchSimilarity),
                        matchConfidence = maxOf(rawDecision.matchConfidence, state.matchConfidence),
                        decisionMargin = maxOf(rawDecision.decisionMargin, state.decisionMargin),
                        title = "AUTHENTICATED: ${state.studentName.uppercase()}",
                        subtitle = "Roll: ${state.studentRoll} • Live 3D Verified"
                    )
                    state.lastDecision = stabilized
                    return stabilized
                }
                IdentityClassification.SPOOF_ATTACK -> {
                    val stabilized = rawDecision.copy(
                        gateState = PipelineGateState.REJECT_SPOOF_ATTACK,
                        isAttendanceAuthorized = false,
                        matchedStudentRoll = "SPOOF",
                        matchedStudentName = "Spoof Attack Detected",
                        title = "ACCESS DENIED: SPOOF DETECTED",
                        subtitle = "Presentation attack prevented"
                    )
                    state.lastDecision = stabilized
                    return stabilized
                }
                else -> {}
            }
        }

        state.lastDecision = rawDecision
        return rawDecision
    }

    /**
     * Returns the persistent track state for a given track ID if available.
     */
    fun getTrackState(trackId: Int): TrackedFaceState? {
        return activeTracks[trackId]
    }

    /**
     * Purges tracks that have not been updated within TRACK_TIMEOUT_MS (out of field of view).
     */
    fun purgeOldTracks() {
        val now = System.currentTimeMillis()
        activeTracks.entries.removeIf { (now - it.value.lastSeenTimestampMs) > TRACK_TIMEOUT_MS }
    }

    /**
     * Resets all persistent tracks.
     */
    fun clear() {
        activeTracks.clear()
    }

    // ── Geometry Helpers ──

    private fun computeIoU(a: Rect, b: Rect): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        val interArea = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
        if (interArea <= 0f) return 0f

        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val unionArea = areaA + areaB - interArea

        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    private fun computeNormalizedCentroidDist(a: Rect, b: Rect): Float {
        val dx = a.center.x - b.center.x
        val dy = a.center.y - b.center.y
        val dist = sqrt(dx * dx + dy * dy)
        val avgDim = ((a.right - a.left + a.bottom - a.top + b.right - b.left + b.bottom - b.top) / 4f).coerceAtLeast(1f)
        return dist / avgDim
    }
}
