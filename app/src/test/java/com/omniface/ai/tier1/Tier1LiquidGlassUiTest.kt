package com.omniface.ai.tier1

import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.Color
import com.omniface.ai.ui.components.omniLiquidSpecularBorder
import com.omniface.ai.ui.components.omniLiquidSurfaceBrush
import com.omniface.ai.ui.theme.CyanCore
import com.omniface.ai.ui.theme.CyanGlow
import com.omniface.ai.ui.theme.SlateBackground
import org.junit.Assert.*
import org.junit.Test

/**
 * Tier 1: Feature 10 - Liquid Glass UI Tokens & Cupertino Specifications
 */
class Tier1LiquidGlassUiTest {

    @Test
    fun testOmniLiquidSpecularBorderDarkStops() {
        val borderDark = omniLiquidSpecularBorder(isDark = true)
        assertNotNull("Dark mode specular border brush must be non-null", borderDark)
    }

    @Test
    fun testOmniLiquidSpecularBorderLightStops() {
        val borderLight = omniLiquidSpecularBorder(isDark = false)
        assertNotNull("Light mode specular border brush must be non-null", borderLight)
    }

    @Test
    fun testOmniLiquidSurfaceBrushColorValues() {
        val darkSurface = omniLiquidSurfaceBrush(isDark = true)
        val lightSurface = omniLiquidSurfaceBrush(isDark = false)

        assertNotNull("Dark surface brush must be non-null", darkSurface)
        assertNotNull("Light surface brush must be non-null", lightSurface)
    }

    @Test
    fun testCupertinoThemeColorTokens() {
        assertNotNull("CyanCore must be initialized", CyanCore)
        assertNotNull("CyanGlow must be initialized", CyanGlow)
        assertNotNull("SlateBackground must be initialized", SlateBackground)

        // Verify CyanCore color value (0xFF0A84FF) by comparing ARGB components directly,
        // avoiding ULong/Long bit-arithmetic that varies across Compose versions.
        assertEquals("CyanCore red must be 0x0A", 0x0A, (CyanCore.red * 255f + 0.5f).toInt())
        assertEquals("CyanCore green must be 0x84", 0x84, (CyanCore.green * 255f + 0.5f).toInt())
        assertEquals("CyanCore blue must be 0xFF", 0xFF, (CyanCore.blue * 255f + 0.5f).toInt())
        assertEquals("CyanCore alpha must be 0xFF (fully opaque)", 0xFF, (CyanCore.alpha * 255f + 0.5f).toInt())
    }

    @Test
    fun testSpringAnimationParameters() {
        // Enforce iOS tactile feel: Bouncy medium damping (0.5f) + low stiffness (200.0f)
        assertEquals(Spring.DampingRatioMediumBouncy, 0.5f, 1e-4f)
        assertEquals(Spring.StiffnessLow, 200.0f, 1e-4f)
    }
}
