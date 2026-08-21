package com.omniface.ai.ml.tracking

import androidx.compose.ui.geometry.Rect
import com.omniface.ai.ml.pipeline.BiometricSynthesisDecision
import com.omniface.ai.ml.pipeline.PipelineGateState
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
    var isClassificationLocked: Boolean = false
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
        // Exponential Moving Average for bounding box smoothing
        val smoothed = Rect(
            left = prev.smoothedRect.left * (1f - ALPHA) + rawRect.left * ALPHA,
            top = prev.smoothedRect.top * (1f - ALPHA) + rawRect.top * ALPHA,
            right = prev.smoothedRect.right * (1f - ALPHA) + rawRect.right * ALPHA,
            bottom = prev.smoothedRect.bottom * (1f - ALPHA) + rawRect.bottom * ALPHA
        )

        val vx = (smoothed.center.x - prev.smoothedRect.center.x).coerceIn(-50f, 50f)
        val vy = (smoothed.center.y - prev.smoothedRect.center.y).coerceIn(-50f, 50f)

        prev.smoothedRect = smoothed
        prev.rawRect = rawRect
        prev.velocityX = vx
        prev.velocityY = vy
        prev.lastSeenTimestampMs = now
        prev.frameCount += 1
        prev.lostFrameCount = 0

        return prev
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
            }
            rawDecision.gateState == PipelineGateState.REJECT_UNKNOWN_IDENTITY || rawDecision.matchedStudentRoll == "GUEST" -> {
                state.consecutiveUnknownHits++
                state.consecutiveKnownHits = 0
            }
        }

        // Check if track should transition to locked state
        if (!state.isClassificationLocked) {
            when {
                state.consecutiveKnownHits >= REQUIRED_CONFIRMATION_FRAMES || (rawDecision.isAttendanceAuthorized && rawDecision.matchSimilarity >= 0.70f) -> {
                    state.classification = IdentityClassification.KNOWN
                    state.studentRoll = rawDecision.matchedStudentRoll
                    state.studentName = rawDecision.matchedStudentName
                    state.matchConfidence = rawDecision.matchConfidence
                    state.matchSimilarity = rawDecision.matchSimilarity
                    state.decisionMargin = rawDecision.decisionMargin
                    state.isClassificationLocked = true
                }
                state.consecutiveUnknownHits >= REQUIRED_CONFIRMATION_FRAMES -> {
                    state.classification = IdentityClassification.UNKNOWN
                    state.studentRoll = "GUEST"
                    state.studentName = "Unregistered Visitor"
                    state.isClassificationLocked = true
                }
                state.consecutiveSpoofHits >= REQUIRED_CONFIRMATION_FRAMES -> {
                    state.classification = IdentityClassification.SPOOF_ATTACK
                    state.isClassificationLocked = true
                }
            }
        }

        // Apply persistent locked classification to prevent single-frame flickers
        if (state.isClassificationLocked) {
            when (state.classification) {
                IdentityClassification.KNOWN -> {
                    // Retain known student identity even if rawDecision had a single-frame quality dip
                    val stabilized = if (rawDecision.gateState == PipelineGateState.REJECT_SPOOF_ATTACK) {
                        // Anti-spoof attack triggers override
                        rawDecision
                    } else {
                        rawDecision.copy(
                            gateState = PipelineGateState.PASS,
                            isAttendanceAuthorized = true,
                            matchedStudentRoll = state.studentRoll,
                            matchedStudentName = state.studentName,
                            matchSimilarity = maxOf(rawDecision.matchSimilarity, state.matchSimilarity),
                            matchConfidence = maxOf(rawDecision.matchConfidence, state.matchConfidence),
                            decisionMargin = maxOf(rawDecision.decisionMargin, state.decisionMargin),
                            title = "AUTHENTICATED: ${state.studentName.uppercase()}",
                            subtitle = "Roll: ${state.studentRoll} • Identity Locked"
                        )
                    }
                    state.lastDecision = stabilized
                    return stabilized
                }
                IdentityClassification.UNKNOWN -> {
                    val stabilized = rawDecision.copy(
                        gateState = PipelineGateState.REJECT_UNKNOWN_IDENTITY,
                        isAttendanceAuthorized = false,
                        matchedStudentRoll = "GUEST",
                        matchedStudentName = "Unregistered Visitor",
                        title = "ACCESS DENIED: UNKNOWN VISITOR",
                        subtitle = "Identity not enrolled in system"
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
