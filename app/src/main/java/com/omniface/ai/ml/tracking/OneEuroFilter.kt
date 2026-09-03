package com.omniface.ai.ml.tracking

import android.graphics.PointF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.PI

/**
 * 1€ (One Euro) Adaptive Low-Pass Filter.
 *
 * Designed specifically for real-time human computer interaction and computer-vision tracking:
 * - Low cutoff frequency when stationary (eliminates high-frequency micro-jitter).
 * - Dynamically increases cutoff with signal velocity (eliminates tracking lag/trailing during rapid head movement).
 */
class OneEuroFilter(
    private var minCutoff: Float = 1.0f,
    private var beta: Float = 0.05f,
    private var dCutoff: Float = 1.0f
) {
    private var xPrev: Float? = null
    private var dxPrev: Float = 0f
    private var tPrev: Long? = null

    fun filter(x: Float, timestampMs: Long = System.currentTimeMillis()): Float {
        val lastT = tPrev
        val lastX = xPrev

        if (lastT == null || lastX == null) {
            xPrev = x
            dxPrev = 0f
            tPrev = timestampMs
            return x
        }

        val dt = ((timestampMs - lastT).toFloat() / 1000f).coerceIn(0.001f, 0.1f)
        tPrev = timestampMs

        // 1. Compute velocity (derivative)
        val dx = (x - lastX) / dt
        val edx = alpha(dt, dCutoff) * dx + (1f - alpha(dt, dCutoff)) * dxPrev
        dxPrev = edx

        // 2. Compute dynamic cutoff frequency based on velocity magnitude
        val cutoff = minCutoff + beta * abs(edx)

        // 3. Filter the position with the dynamic cutoff
        val a = alpha(dt, cutoff)
        val xFiltered = a * x + (1f - a) * lastX
        xPrev = xFiltered
        return xFiltered
    }

    private fun alpha(dt: Float, cutoff: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    fun reset() {
        xPrev = null
        dxPrev = 0f
        tPrev = null
    }
}

/**
 * 2D Point 1€ Filter for individual facial landmarks (eyes, nose, mouth corners).
 */
class PointFOneEuroFilter(
    minCutoff: Float = 1.0f,
    beta: Float = 0.04f,
    dCutoff: Float = 1.0f
) {
    private val filterX = OneEuroFilter(minCutoff, beta, dCutoff)
    private val filterY = OneEuroFilter(minCutoff, beta, dCutoff)

    fun filter(point: PointF, timestampMs: Long = System.currentTimeMillis()): PointF {
        val fx = filterX.filter(point.x, timestampMs)
        val fy = filterY.filter(point.y, timestampMs)
        return PointF(fx, fy)
    }

    fun filter(offset: Offset, timestampMs: Long = System.currentTimeMillis()): Offset {
        val fx = filterX.filter(offset.x, timestampMs)
        val fy = filterY.filter(offset.y, timestampMs)
        return Offset(fx, fy)
    }

    fun reset() {
        filterX.reset()
        filterY.reset()
    }
}

/**
 * Bounding Box 1€ Filter for smooth reticle tracking without lagging during motion.
 */
class RectOneEuroFilter(
    minCutoff: Float = 1.2f,
    beta: Float = 0.05f,
    dCutoff: Float = 1.0f
) {
    private val filterLeft = OneEuroFilter(minCutoff, beta, dCutoff)
    private val filterTop = OneEuroFilter(minCutoff, beta, dCutoff)
    private val filterRight = OneEuroFilter(minCutoff, beta, dCutoff)
    private val filterBottom = OneEuroFilter(minCutoff, beta, dCutoff)

    fun filter(rect: Rect, timestampMs: Long = System.currentTimeMillis()): Rect {
        val left = filterLeft.filter(rect.left, timestampMs)
        val top = filterTop.filter(rect.top, timestampMs)
        val right = filterRight.filter(rect.right, timestampMs)
        val bottom = filterBottom.filter(rect.bottom, timestampMs)
        return Rect(left, top, right, bottom)
    }

    fun reset() {
        filterLeft.reset()
        filterTop.reset()
        filterRight.reset()
        filterBottom.reset()
    }
}
