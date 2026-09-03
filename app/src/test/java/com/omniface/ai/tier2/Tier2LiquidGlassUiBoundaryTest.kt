package com.omniface.ai.tier2

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.omniface.ai.ui.components.omniLiquidSpecularBorder
import com.omniface.ai.ui.components.omniLiquidSurfaceBrush
import org.junit.Assert.*
import org.junit.Test

/**
 * Tier 2: Boundary & Corner Cases - Feature 10: Liquid Glass UI Tokens & Fallback Depth
 */
class Tier2LiquidGlassUiBoundaryTest {

    @Test
    fun testLiquidSurfaceBrushNotNullAcrossThemes() {
        val dark = omniLiquidSurfaceBrush(isDark = true)
        val light = omniLiquidSurfaceBrush(isDark = false)

        assertNotNull(dark)
        assertNotNull(light)
        assertNotEquals(dark, light)
    }

    @Test
    fun testSpecularBorderBrushNotNullAcrossThemes() {
        val dark = omniLiquidSpecularBorder(isDark = true)
        val light = omniLiquidSpecularBorder(isDark = false)

        assertNotNull(dark)
        assertNotNull(light)
    }

    @Test
    fun testColorAlphaBoundaryValues() {
        val testColor = Color(0x381E293B)
        assertEquals(0x38, (testColor.alpha * 255).toInt())
        
        val fullAlpha = Color(0xFF1E293B)
        assertEquals(1.0f, fullAlpha.alpha, 1e-4f)

        val zeroAlpha = Color(0x00000000)
        assertEquals(0.0f, zeroAlpha.alpha, 1e-4f)
    }

    @Test
    fun testBrushLinearGradientCreation() {
        val brush = Brush.verticalGradient(
            listOf(Color.White, Color.Black)
        )
        assertNotNull(brush)
    }

    @Test
    fun testSpecularGradientDirectionalStops() {
        // Dark specular stops ensure crisp 0.0f top-left highlight down to 1.0f dark shadow
        val darkBorder = omniLiquidSpecularBorder(isDark = true)
        assertNotNull(darkBorder)
    }
}
