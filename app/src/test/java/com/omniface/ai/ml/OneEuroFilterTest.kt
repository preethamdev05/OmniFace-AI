package com.omniface.ai.ml

import android.graphics.PointF
import androidx.compose.ui.geometry.Rect
import com.omniface.ai.ml.tracking.OneEuroFilter
import com.omniface.ai.ml.tracking.PointFOneEuroFilter
import com.omniface.ai.ml.tracking.RectOneEuroFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OneEuroFilterTest {

    @Test
    fun testOneEuroFilter_stationarySignal_suppressesJitter() {
        val filter = OneEuroFilter(minCutoff = 1.0f, beta = 0.05f)
        var t = 1000L
        val baseValue = 50.0f

        // Initialize with base value
        filter.filter(baseValue, t)

        // Feed small high-frequency noise (+-0.5)
        var totalOutputJitter = 0.0f
        for (i in 1..20) {
            t += 33L // ~30 FPS
            val noise = if (i % 2 == 0) 0.5f else -0.5f
            val noisyInput = baseValue + noise
            val filtered = filter.filter(noisyInput, t)
            totalOutputJitter += abs(filtered - baseValue)
        }

        val avgOutputJitter = totalOutputJitter / 20f
        // Jitter should be heavily attenuated compared to raw 0.5f noise amplitude
        assertTrue("Filter should attenuate stationary jitter: avg=$avgOutputJitter", avgOutputJitter < 0.35f)
    }

    @Test
    fun testOneEuroFilter_fastStep_adaptsQuicklyWithoutLag() {
        val filter = OneEuroFilter(minCutoff = 1.0f, beta = 0.1f)
        var t = 1000L

        // Warm up at position 0
        for (i in 0..5) {
            t += 33L
            filter.filter(0.0f, t)
        }

        // Rapid step jump to position 100
        t += 33L
        val step1 = filter.filter(100.0f, t)
        t += 33L
        val step2 = filter.filter(100.0f, t)

        // Dynamic beta increases cutoff on high velocity, so step response reaches > 80% rapidly
        assertTrue("Step response should adapt quickly on high velocity: step2=$step2", step2 > 75.0f)
    }

    @Test
    fun testPointFOneEuroFilter_smoothsPoints() {
        val filter = PointFOneEuroFilter(minCutoff = 1.0f, beta = 0.04f)
        val p1 = filter.filter(androidx.compose.ui.geometry.Offset(10f, 20f), 1000L)
        assertEquals(10f, p1.x, 0.01f)
        assertEquals(20f, p1.y, 0.01f)

        val p2 = filter.filter(androidx.compose.ui.geometry.Offset(12f, 22f), 1033L)
        assertTrue(p2.x in 10.0f..12.0f)
        assertTrue(p2.y in 20.0f..22.0f)
    }

    @Test
    fun testRectOneEuroFilter_smoothsBoundingBoxes() {
        val filter = RectOneEuroFilter(minCutoff = 1.2f, beta = 0.05f)
        val initialRect = Rect(10f, 10f, 100f, 100f)
        val r1 = filter.filter(initialRect, 1000L)
        assertEquals(initialRect.left, r1.left, 0.01f)
        assertEquals(initialRect.bottom, r1.bottom, 0.01f)

        val movedRect = Rect(15f, 15f, 105f, 105f)
        val r2 = filter.filter(movedRect, 1033L)
        assertTrue(r2.left in 10.0f..15.0f)
        assertTrue(r2.right in 100.0f..105.0f)
    }
}
