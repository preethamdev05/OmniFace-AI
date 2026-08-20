package com.omniface.ai.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniface.ai.ui.theme.*

private const val AGSL_LIQUID_SHADER = """
uniform shader composable;
uniform float2 resolution;
uniform float refractionDistortion;

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float2 centerOffset = uv - float2(0.5, 0.5);
    float dist = length(centerOffset);
    float2 displacement = centerOffset * (dist * dist * refractionDistortion);
    
    // Chromatic dispersion: evaluate R, G, B at slightly offset refraction angles
    half4 colorR = composable.eval(fragCoord + displacement * 1.02);
    half4 colorG = composable.eval(fragCoord + displacement);
    half4 colorB = composable.eval(fragCoord + displacement * 0.98);
    
    return half4(colorR.r, colorG.g, colorB.b, (colorR.a + colorG.a + colorB.a) / 3.0);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object AgslLiquidGlassShaderHolder {
    val runtimeShader: android.graphics.RuntimeShader by lazy {
        android.graphics.RuntimeShader(AGSL_LIQUID_SHADER).apply {
            setFloatUniform("refractionDistortion", 0.06f)
        }
    }
}

/**
 * GPU Hardware Backdrop Diffusion Gating.
 * Prevents content-smearing by safely providing translucent depth rather than blurring child UI.
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.liquidGlassBackdrop(
    blurRadius: Dp = 16.dp,
    shape: Shape = RoundedCornerShape(20.dp),
    enableRefraction: Boolean = false
): Modifier = this

/**
 * Pure Apple iOS Specular Reflection Border Brush (Kyant & Philipp Lackner Tokens).
 * Simulates top-left 135° ambient light source highlight with bottom-right hairline refraction shadow.
 */
fun omniLiquidSpecularBorder(isDark: Boolean): Brush {
    return if (isDark) {
        Brush.linearGradient(
            0.0f to Color(0x38FFFFFF),
            0.35f to Color(0x1AFFFFFF),
            0.70f to Color(0x08FFFFFF),
            1.0f to Color(0x0A000000),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )
    } else {
        Brush.linearGradient(
            0.0f to Color(0x80FFFFFF),
            0.40f to Color(0x1A000000),
            1.0f to Color(0x0D000000),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )
    }
}

/**
 * Pure Apple iOS Inset Grouped Surface Diffusion Brush.
 */
fun omniLiquidSurfaceBrush(isDark: Boolean): Brush {
    return if (isDark) {
        Brush.verticalGradient(
            listOf(
                Color(0xFF1E2129),
                Color(0xFF171920),
                Color(0xFF12141A)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color(0xFFFFFFFF),
                Color(0xFFFCFDFF),
                Color(0xFFF6F8FB)
            )
        )
    }
}

/**
 * Standardized Apple iOS Grouped Surface Card (20dp major, 16dp compact).
 */
@Composable
fun IOSCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    borderBrush: Brush? = null,
    backgroundBrush: Brush? = null,
    elevation: Dp? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = LocalThemeIsDark.current
    val effectiveBg = backgroundBrush ?: omniLiquidSurfaceBrush(isDark)
    val effectiveBorder = borderBrush ?: omniLiquidSpecularBorder(isDark)
    val shadowElevation = elevation ?: (if (isDark) 2.dp else 4.dp)

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && onClick != null) 0.98f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "cardScale"
    )

    val cardShape = RoundedCornerShape(cornerRadius)

    val baseModifier = modifier
        .scale(scale)
        .shadow(
            shadowElevation,
            cardShape,
            ambientColor = if (isDark) Color(0x4D000000) else Color(0x14000000),
            spotColor = if (isDark) Color(0x33000000) else Color(0x0A000000)
        )
        .clip(cardShape)
        .background(effectiveBg)
        .border(0.75.dp, effectiveBorder, cardShape)

    val cardModifier = if (onClick != null) {
        baseModifier.clickable(
            interactionSource = interactionSource,
            indication = null
        ) { onClick() }
    } else {
        baseModifier
    }

    CompositionLocalProvider(LocalContentColor provides omniTextPrimary(isDark)) {
        Column(
            modifier = cardModifier.padding(16.dp),
            content = content
        )
    }
}

// Backward compatibility alias for FrostedGlassCard
@Composable
fun FrostedGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    borderBrush: Brush? = null,
    backgroundBrush: Brush? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) = IOSCard(
    modifier = modifier,
    cornerRadius = cornerRadius,
    borderBrush = borderBrush,
    backgroundBrush = backgroundBrush,
    onClick = onClick,
    content = content
)

/**
 * Standardized Section Header
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    val isDark = LocalThemeIsDark.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text.uppercase(),
            color = omniTextMuted(isDark),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
        if (actionText != null && onAction != null) {
            Text(
                text = actionText,
                color = omniCyan(isDark),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onAction() }
            )
        }
    }
}

/**
 * Standardized Empty State Component
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    val isDark = LocalThemeIsDark.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (isDark) Color(0x1FFFFFFF) else Color(0x0D000000)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = omniTextMuted(isDark),
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = title,
            color = omniTextPrimary(isDark),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = subtitle,
            color = omniTextMuted(isDark),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        if (actionText != null && onAction != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = omniCyan(isDark)),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(actionText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Standardized Setting Row with iOS Grouped Styling
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = CyanCore,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val isDark = LocalThemeIsDark.current
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && onClick != null) 0.98f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "settingRowScale"
    )

    val rowModifier = if (onClick != null) {
        modifier
            .scale(scale)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(vertical = 10.dp)
    } else {
        modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    }

    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(iconTint.copy(alpha = if (isDark) 0.20f else 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column {
                Text(
                    text = title,
                    color = omniTextPrimary(isDark),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = omniTextMuted(isDark),
                        fontSize = 12.sp
                    )
                }
            }
        }

        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = omniTextMuted(isDark),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Standardized Apple iOS Segmented Control (12dp corner radius)
 */
@Composable
fun CupertinoSegmentedControl(
    modifier: Modifier = Modifier,
    items: List<String>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit
) {
    val isDark = LocalThemeIsDark.current
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA))
            .border(0.75.dp, if (isDark) Color(0x1FFFFFFF) else Color(0x14000000), RoundedCornerShape(12.dp))
            .padding(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items.forEachIndexed { index, title ->
                val isSelected = index == selectedIndex
                val animBgColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        if (isDark) Color(0xFF636366) else Color(0xFFFFFFFF)
                    } else Color.Transparent,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "segmentBg"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .shadow(if (isSelected && !isDark) 2.dp else 0.dp, RoundedCornerShape(9.dp))
                        .clip(RoundedCornerShape(9.dp))
                        .background(animBgColor)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onItemSelected(index)
                        }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        color = if (isSelected) {
                            if (isDark) Color.White else Color.Black
                        } else {
                            if (isDark) Color(0x99EBEBF5) else Color(0x993C3C43)
                        },
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * Standardized Apple iOS Metric Tile (16dp corner radius)
 */
@Composable
fun CupertinoMetricTile(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color = CyanCore,
    onClick: (() -> Unit)? = null
) {
    val isDark = LocalThemeIsDark.current

    IOSCard(
        modifier = modifier,
        cornerRadius = 16.dp,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title.uppercase(),
                color = omniTextMuted(isDark),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accentColor.copy(alpha = if (isDark) 0.20f else 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = value,
            color = omniTextPrimary(isDark),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = subtitle,
            color = accentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Standardized Apple iOS Action Button (14dp rounded, 50dp height)
 */
@Composable
fun CupertinoButton(
    modifier: Modifier = Modifier,
    text: String,
    icon: ImageVector? = null,
    brush: Brush? = null,
    contentColor: Color? = null,
    isSecondary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val isDark = LocalThemeIsDark.current
    val haptic = LocalHapticFeedback.current

    val effectiveBrush = if (isSecondary) {
        if (isDark) {
            Brush.verticalGradient(listOf(Color(0xFF2C2C2E), Color(0xFF2C2C2E)))
        } else {
            Brush.verticalGradient(listOf(Color(0xFFE5E5EA), Color(0xFFE5E5EA)))
        }
    } else {
        brush ?: (if (isDark) CyanGlassBrush else LightCyanGlassBrush)
    }

    val effectiveContentColor = if (isSecondary) {
        if (isDark) TextPrimary else LightTextPrimary
    } else {
        contentColor ?: Color.White
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.97f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "btnScale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .fillMaxWidth()
            .height(50.dp)
            .shadow(if (!isSecondary && enabled) (if (isDark) 4.dp else 6.dp) else 0.dp, RoundedCornerShape(14.dp), ambientColor = if (isDark) Color(0x33000000) else Color(0x26007AFF))
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) effectiveBrush else Brush.verticalGradient(listOf(Color(0xFF8E8E93), Color(0xFF8E8E93))))
            .border(
                0.75.dp,
                if (enabled) omniLiquidSpecularBorder(isDark) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)),
                RoundedCornerShape(14.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) effectiveContentColor else TextMuted,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = text,
                color = if (enabled) effectiveContentColor else TextMuted,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp
            )
        }
    }
}
