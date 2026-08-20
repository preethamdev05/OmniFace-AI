package com.omniface.ai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM
}

val LocalThemeIsDark = compositionLocalOf { false }

private val DarkColorScheme = darkColorScheme(
    primary = CyanCore,
    onPrimary = Color.Black,
    secondary = EmeraldCore,
    onSecondary = Color.Black,
    tertiary = AmberCore,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateElevated,
    onSurfaceVariant = TextSecondary,
    outline = SlateBorder
)

private val LightColorScheme = lightColorScheme(
    primary = LightCyanCore,
    onPrimary = Color.White,
    secondary = LightEmeraldCore,
    onSecondary = Color.White,
    tertiary = LightAmberCore,
    background = AlabasterBg,
    onBackground = LightTextPrimary,
    surface = PorcelainSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = PorcelainElevated,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder
)

fun omniBackground(isDark: Boolean): Color = if (isDark) ObsidianBg else AlabasterBg

fun omniBackgroundBrush(isDark: Boolean): Brush {
    return if (isDark) {
        Brush.verticalGradient(
            listOf(
                Color(0xFF000000),
                Color(0xFF060913),
                Color(0xFF0A0E1A)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color(0xFFF2F2F7),
                Color(0xFFF2F2F7),
                Color(0xFFE5E5EA)
            )
        )
    }
}

fun omniFrostedGlassBrush(isDark: Boolean): Brush {
    return if (isDark) {
        Brush.verticalGradient(
            listOf(
                Color(0x331C1C1E),
                Color(0x4D1C1C1E)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color(0xFAFFFFFF),
                Color(0xF0FFFFFF)
            )
        )
    }
}

fun omniSpecularBorderBrush(isDark: Boolean): Brush {
    return if (isDark) {
        Brush.verticalGradient(
            listOf(
                Color(0x38FFFFFF),
                Color(0x0FFFFFFF),
                Color(0x05FFFFFF)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color(0x66FFFFFF),
                Color(0x1A000000),
                Color(0x0D000000)
            )
        )
    }
}

val CyanGlassBrush = Brush.horizontalGradient(
    colors = listOf(
        Color(0xFF0A84FF),
        Color(0xFF007AFF)
    )
)

val LightCyanGlassBrush = Brush.horizontalGradient(
    colors = listOf(
        Color(0xFF007AFF),
        Color(0xFF0062CC)
    )
)

fun omniCardShadowElevation(isDark: Boolean): Dp = if (isDark) 4.dp else 6.dp
fun omniTextPrimary(isDark: Boolean): Color = if (isDark) TextPrimary else LightTextPrimary
fun omniTextSecondary(isDark: Boolean): Color = if (isDark) TextSecondary else LightTextSecondary
fun omniTextMuted(isDark: Boolean): Color = if (isDark) TextMuted else LightTextMuted
fun omniCyan(isDark: Boolean): Color = if (isDark) CyanCore else LightCyanCore
fun omniEmerald(isDark: Boolean): Color = if (isDark) EmeraldCore else LightEmeraldCore

@Composable
fun OmniFaceTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemDark
    }

    CompositionLocalProvider(LocalThemeIsDark provides isDark) {
        MaterialTheme(
            colorScheme = if (isDark) DarkColorScheme else LightColorScheme,
            content = content
        )
    }
}
