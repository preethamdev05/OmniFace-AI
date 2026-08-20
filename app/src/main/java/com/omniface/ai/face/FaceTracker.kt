package com.omniface.ai.face

import android.graphics.Rect
import android.graphics.RectF

data class TrackedFace(
    val trackingId: Int,
    var smoothedBounds: RectF,
    var consecutiveFrames: Int = 1,
    var lastSeenTimestampMs: Long = System.currentTimeMillis()
)

class FaceTracker(private val smoothingFactor: Float = 0.65f) {

    private val activeTracks = mutableMapOf<Int, TrackedFace>()

    fun updateTracks(newBoundingBoxes: List<Pair<Int, Rect>>, nowMs: Long = System.currentTimeMillis()): List<TrackedFace> {
        val currentIds = mutableSetOf<Int>()

        for ((id, rawBox) in newBoundingBoxes) {
            currentIds.add(id)
            val rawRectF = RectF(rawBox)
            val existing = activeTracks[id]

            if (existing != null) {
                // Exponential moving average smoothing
                val smoothLeft = existing.smoothedBounds.left * smoothingFactor + rawRectF.left * (1f - smoothingFactor)
                val smoothTop = existing.smoothedBounds.top * smoothingFactor + rawRectF.top * (1f - smoothingFactor)
                val smoothRight = existing.smoothedBounds.right * smoothingFactor + rawRectF.right * (1f - smoothingFactor)
                val smoothBottom = existing.smoothedBounds.bottom * smoothingFactor + rawRectF.bottom * (1f - smoothingFactor)

                existing.smoothedBounds.set(smoothLeft, smoothTop, smoothRight, smoothBottom)
                existing.consecutiveFrames++
                existing.lastSeenTimestampMs = nowMs
            } else {
                activeTracks[id] = TrackedFace(
                    trackingId = id,
                    smoothedBounds = rawRectF,
                    consecutiveFrames = 1,
                    lastSeenTimestampMs = nowMs
                )
            }
        }

        // Expire lost tracks (> 500ms without update)
        val iterator = activeTracks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value.lastSeenTimestampMs > 500L) {
                iterator.remove()
            }
        }

        return activeTracks.values.toList()
    }

    fun clear() {
        activeTracks.clear()
    }
}
