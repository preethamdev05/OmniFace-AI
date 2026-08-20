package com.omniface.ai.ml.tracking

import androidx.compose.ui.geometry.Rect
import java.util.concurrent.ConcurrentHashMap

data class TrackedFaceState(
    val trackId: Int,
    val smoothedRect: Rect,
    val velocityX: Float,
    val velocityY: Float,
    val lastSeenTimestampMs: Long,
    val frameCount: Int
)

/**
 * Lightweight Real-Time Bounding Box EMA & IoU Tracker.
 *
 * Prevents reticle jitter and maintains consistent tracking state between detector invocations.
 */
class FaceTracker {

    companion object {
        private const val ALPHA = 0.65f // Smoothing factor
        private const val TRACK_TIMEOUT_MS = 800L
    }

    private val activeTracks = ConcurrentHashMap<Int, TrackedFaceState>()

    fun updateTrack(trackId: Int, rawRect: Rect): Rect {
        val now = System.currentTimeMillis()
        val prev = activeTracks[trackId]

        if (prev == null || (now - prev.lastSeenTimestampMs) > TRACK_TIMEOUT_MS) {
            val freshState = TrackedFaceState(
                trackId = trackId,
                smoothedRect = rawRect,
                velocityX = 0f,
                velocityY = 0f,
                lastSeenTimestampMs = now,
                frameCount = 1
            )
            activeTracks[trackId] = freshState
            return rawRect
        }

        // Exponential Moving Average
        val smoothed = Rect(
            left = prev.smoothedRect.left * (1f - ALPHA) + rawRect.left * ALPHA,
            top = prev.smoothedRect.top * (1f - ALPHA) + rawRect.top * ALPHA,
            right = prev.smoothedRect.right * (1f - ALPHA) + rawRect.right * ALPHA,
            bottom = prev.smoothedRect.bottom * (1f - ALPHA) + rawRect.bottom * ALPHA
        )

        val vx = smoothed.center.x - prev.smoothedRect.center.x
        val vy = smoothed.center.y - prev.smoothedRect.center.y

        val updated = TrackedFaceState(
            trackId = trackId,
            smoothedRect = smoothed,
            velocityX = vx,
            velocityY = vy,
            lastSeenTimestampMs = now,
            frameCount = prev.frameCount + 1
        )
        activeTracks[trackId] = updated
        return smoothed
    }

    fun purgeOldTracks() {
        val now = System.currentTimeMillis()
        activeTracks.entries.removeIf { (now - it.value.lastSeenTimestampMs) > TRACK_TIMEOUT_MS }
    }

    fun clear() {
        activeTracks.clear()
    }
}
