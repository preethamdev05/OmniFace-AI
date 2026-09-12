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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.composed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniface.ai.ui.theme.*

private const val AGSL_KYANT_LIQUID_GLASS = """
uniform shader composable;
uniform float2 size;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float circleMap(float x) {
    return 1.0 - sqrt(max(1.0 - x * x, 0.0));
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);
    
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return composable.eval(coord);
    }
    sd = min(sd, 0.0);
    
    float d = circleMap(1.0 - -sd / max(refractionHeight, 1.0)) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord + 0.001));
    
    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / max(halfSize.x * halfSize.y, 1.0));
    float2 dispersedCoord = d * grad * dispersionIntensity;
    
    half4 color = half4(0.0);
    
    half4 red = composable.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;
    
    half4 orange = composable.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;
    
    half4 yellow = composable.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;
    
    half4 green = composable.eval(refractedCoord);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;
    
    half4 cyan = composable.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;
    
    half4 blue = composable.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;
    
    half4 purple = composable.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;
    
    return color;
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object AgslLiquidGlassShaderHolder {
    fun createShader(width: Float, height: Float, cornerRadius: Float): android.graphics.RuntimeShader {
        return android.graphics.RuntimeShader(AGSL_KYANT_LIQUID_GLASS).apply {
            setFloatUniform("size", width, height)
            setFloatUniform("cornerRadii", cornerRadius, cornerRadius, cornerRadius, cornerRadius)
            setFloatUniform("refractionHeight", 24.0f)
            setFloatUniform("refractionAmount", 14.0f)
            setFloatUniform("depthEffect", 0.22f)
            setFloatUniform("chromaticAberration", 0.16f)
        }
    }
}

/**
 * GPU Hardware Backdrop Diffusion & Kyant Liquid Glass Refraction (Android 12+ API 31+ / Android 13+ API 33+).
 */
fun Modifier.liquidGlassBackdrop(
    blurRadius: Dp = 16.dp,
    shape: Shape = RoundedCornerShape(20.dp),
    enableRefraction: Boolean = true
): Modifier = composed {
    val isDark = LocalThemeIsDark.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val density = LocalDensity.current
        val blurPx = remember(density, blurRadius) {
            with(density) { blurRadius.toPx() }.coerceAtLeast(1f)
        }
        this
            .clip(shape)
            .graphicsLayer {
                val w = size.width.coerceAtLeast(1f)
                val h = size.height.coerceAtLeast(1f)
                var effect = android.graphics.RenderEffect.createBlurEffect(
                    blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP
                )
                if (enableRefraction && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    try {
                        val shader = AgslLiquidGlassShaderHolder.createShader(w, h, 20f * density.density)
                        effect = android.graphics.RenderEffect.createChainEffect(
                            android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "composable"),
                            effect
                        )
                    } catch (_: Throwable) {}
                }
                this.renderEffect = effect.asComposeRenderEffect()
            }
    } else {
        // Legacy fallback: layered translucent diffusion
        this
            .clip(shape)
            .background(omniLiquidSurfaceBrush(isDark), shape)
            .border(0.75.dp, omniLiquidSpecularBorder(isDark), shape)
    }
}

/**
 * Pure Apple iOS Specular Reflection Border Brush (Kyant & Philipp Lackner Tokens).
 * Simulates top-left 135° ambient light source highlight with bottom-right hairline refraction shadow.
 */
private val DarkLiquidSpecularBorder = Brush.linearGradient(
    0.0f to Color(0x66FFFFFF),
    0.20f to Color(0x2EFFFFFF),
    0.55f to Color(0x0CFFFFFF),
    1.0f to Color(0x06000000),
    start = Offset(0f, 0f),
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
)

private val LightLiquidSpecularBorder = Brush.linearGradient(
    0.0f to Color(0xB3FFFFFF),
    0.35f to Color(0x33000000),
    1.0f to Color(0x12000000),
    start = Offset(0f, 0f),
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
)

fun omniLiquidSpecularBorder(isDark: Boolean): Brush =
    if (isDark) DarkLiquidSpecularBorder else LightLiquidSpecularBorder

private val DarkLiquidSurfaceBrush = Brush.verticalGradient(
    listOf(
        Color(0x44222E42),
        Color(0x2D151D2C),
        Color(0x520A0E18)
    )
)

private val LightLiquidSurfaceBrush = Brush.verticalGradient(
    listOf(
        Color(0xF8FFFFFF),
        Color(0xEDFFFFFF),
        Color(0xDDF1F5F9)
    )
)

fun omniLiquidSurfaceBrush(isDark: Boolean): Brush =
    if (isDark) DarkLiquidSurfaceBrush else LightLiquidSurfaceBrush

/**
 * Standardized Apple iOS Grouped Surface Card (20dp major, 16dp compact) with Doppelrand specular sheen.
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
    val haptic = LocalHapticFeedback.current
    val effectiveBg = backgroundBrush ?: omniLiquidSurfaceBrush(isDark)
    val effectiveBorder = borderBrush ?: omniLiquidSpecularBorder(isDark)
    val shadowElevation = elevation ?: (if (isDark) 5.dp else 6.dp)
    val cardShape = RoundedCornerShape(cornerRadius)

    val topSpecularSheen = remember(isDark) {
        Brush.verticalGradient(
            0.0f to if (isDark) Color(0x22FFFFFF) else Color(0x55FFFFFF),
            0.12f to Color.Transparent
        )
    }

    val cardModifier = if (onClick != null) {
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()

        val scale by animateFloatAsState(
            targetValue = if (isPressed) 0.982f else 1.0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "cardScale"
        )

        modifier
            .scale(scale)
            .shadow(
                elevation = shadowElevation,
                shape = cardShape,
                ambientColor = if (isDark) Color(0x66000000) else Color(0x1F0F172A),
                spotColor = if (isDark) Color(0x4D000000) else Color(0x140F172A)
            )
            .clip(cardShape)
            .background(effectiveBg)
            .border(0.75.dp, effectiveBorder, cardShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
    } else {
        modifier
            .shadow(
                elevation = shadowElevation,
                shape = cardShape,
                ambientColor = if (isDark) Color(0x66000000) else Color(0x1F0F172A),
                spotColor = if (isDark) Color(0x4D000000) else Color(0x140F172A)
            )
            .clip(cardShape)
            .background(effectiveBg)
            .border(0.75.dp, effectiveBorder, cardShape)
    }

    CompositionLocalProvider(LocalContentColor provides omniTextPrimary(isDark)) {
        Box(modifier = cardModifier) {
            // Subtle top-edge specular hairline highlight (Apple Doppelrand effect)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(topSpecularSheen)
            )
            Column(
                modifier = Modifier.padding(18.dp),
                content = content
            )
        }
    }
}

/**
 * Standardized Apple iOS Specular Glass Badge / Status Pill (non-wrapping, auto-pulsing)
 */
@Composable
fun IOSGlassPill(
    text: String,
    modifier: Modifier = Modifier,
    accentColor: Color = CyanCore,
    showPulsingDot: Boolean = false,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null
) {
    val isDark = LocalThemeIsDark.current
    val haptic = LocalHapticFeedback.current

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val dotAlpha by if (showPulsingDot) {
        infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dotAlpha"
        )
    } else {
        remember { mutableFloatStateOf(1.0f) }
    }

    val pillShape = RoundedCornerShape(999.dp)
    val pillModifier = if (onClick != null) {
        modifier
            .clip(pillShape)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
    } else {
        modifier.clip(pillShape)
    }

    Row(
        modifier = pillModifier
            .background(accentColor.copy(alpha = if (isDark) 0.16f else 0.10f))
            .border(0.75.dp, accentColor.copy(alpha = if (isDark) 0.40f else 0.25f), pillShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (showPulsingDot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = dotAlpha))
            )
            Spacer(modifier = Modifier.width(6.dp))
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
        }
        Text(
            text = text,
            color = accentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Standardized Apple iOS Liquid Glass Search Input Field
 */
@Composable
fun CupertinoSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Search...",
    modifier: Modifier = Modifier
) {
    val isDark = LocalThemeIsDark.current
    val searchShape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(if (isDark) 4.dp else 6.dp, searchShape, ambientColor = Color(0x1F000000))
            .clip(searchShape)
            .background(if (isDark) Color(0x331E293B) else Color(0xF2FFFFFF))
            .border(0.75.dp, omniLiquidSpecularBorder(isDark), searchShape)
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = omniTextMuted(isDark),
                modifier = Modifier.size(19.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = omniTextPrimary(isDark),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                ),
                singleLine = true,
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = omniTextMuted(isDark),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                    innerTextField()
                }
            )
            if (query.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                val haptic = LocalHapticFeedback.current
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (isDark) Color(0x33FFFFFF) else Color(0x1F000000))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onQueryChange("")
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear",
                        tint = if (isDark) Color(0xCCFFFFFF) else Color(0x99000000),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
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
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = omniCyan(isDark)),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
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
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(iconTint.copy(alpha = if (isDark) 0.20f else 0.12f))
                        .border(0.5.dp, iconTint.copy(alpha = if (isDark) 0.40f else 0.25f), CircleShape),
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = omniTextPrimary(isDark),
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.2).sp,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = omniTextMuted(isDark),
                        fontSize = 12.sp,
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        } else if (onClick != null) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = omniTextMuted(isDark),
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

/**
 * Standardized Apple iOS Segmented Control (14dp corner radius) with sliding glass pill indicator.
 */
@Composable
fun CupertinoSegmentedControl(
    modifier: Modifier = Modifier,
    items: List<String>,
    selectedIndex: Int,
    activeBrush: Brush? = null,
    onItemSelected: (Int) -> Unit
) {
    val isDark = LocalThemeIsDark.current
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isDark) Color(0x660F1424) else Color(0xFFE5E7EB))
            .border(0.75.dp, omniLiquidSpecularBorder(isDark), RoundedCornerShape(14.dp))
            .padding(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEachIndexed { index, title ->
                val isSelected = index == selectedIndex
                val animBgColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        if (activeBrush != null) Color.Transparent
                        else if (isDark) Color(0xFF636366) else Color(0xFFFFFFFF)
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
                        .shadow(
                            if (isSelected) 4.dp else 0.dp,
                            RoundedCornerShape(10.dp),
                            spotColor = if (isSelected && activeBrush != null) Color(0x666366F1) else Color(0x1F000000)
                        )
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (isSelected && activeBrush != null) {
                                Modifier.background(activeBrush)
                            } else {
                                Modifier.background(animBgColor)
                            }
                        )
                        .border(
                            if (isSelected) 0.5.dp else 0.dp,
                            if (isSelected) (if (isDark) Color(0x33FFFFFF) else Color(0x26000000)) else Color.Transparent,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onItemSelected(index)
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        color = if (isSelected) {
                            if (activeBrush != null) Color.White
                            else if (isDark) Color.White else Color(0xFF1C1C1E)
                        } else {
                            if (isDark) Color(0x99EBEBF5) else Color(0x993C3C43)
                        },
                        fontSize = 12.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = (-0.1).sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Standardized Apple iOS Metric Tile (18dp corner radius) with ambient glowing backdrop and SF Pro metrics.
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
        cornerRadius = 18.dp,
        onClick = onClick
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Subtle ambient radial glow behind icon
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = if (isDark) 0.16f else 0.09f),
                                Color.Transparent
                            ),
                            center = Offset(240f, 0f),
                            radius = 280f
                        )
                    )
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title.uppercase(),
                        color = omniTextMuted(isDark),
                        fontSize = if (title.length > 12) 8.5.sp else 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = if (title.length > 12) 0.sp else 0.15.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = if (isDark) 0.20f else 0.12f))
                            .border(0.5.dp, accentColor.copy(alpha = if (isDark) 0.40f else 0.25f), CircleShape),
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

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = value,
                    color = omniTextPrimary(isDark),
                    fontSize = if (value.length > 8) 20.sp else 25.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.6).sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = subtitle,
                        color = accentColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.1).sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Standardized Apple iOS Action Button (16dp rounded, 50dp height) with Button-in-Button pattern.
 */
@Composable
fun CupertinoButton(
    modifier: Modifier = Modifier.fillMaxWidth(),
    text: String,
    icon: ImageVector? = null,
    brush: Brush? = null,
    contentColor: Color? = null,
    isSecondary: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 50.dp,
    onClick: () -> Unit
) {
    val isDark = LocalThemeIsDark.current
    val haptic = LocalHapticFeedback.current

    val effectiveBrush = if (isSecondary) {
        if (isDark) {
            Brush.verticalGradient(listOf(Color(0xFF1E2433), Color(0xFF161A24)))
        } else {
            Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF1F5F9)))
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
        targetValue = if (isPressed && enabled) 0.965f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "btnScale"
    )

    val shadowElevation = if (!isSecondary && enabled) (if (isDark) 6.dp else 8.dp) else (if (isDark) 2.dp else 3.dp)
    val ambientGlow = if (!isSecondary && enabled) {
        if (isDark) Color(0x406366F1) else Color(0x290071E3)
    } else {
        if (isDark) Color(0x55000000) else Color(0x1A000000)
    }

    Box(
        modifier = modifier
            .scale(scale)
            .height(height)
            .shadow(
                elevation = shadowElevation,
                shape = RoundedCornerShape(16.dp),
                ambientColor = ambientGlow,
                spotColor = ambientGlow
            )
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) effectiveBrush else Brush.verticalGradient(listOf(Color(0xFF64748B), Color(0xFF475569))))
            .border(
                width = 0.75.dp,
                brush = if (enabled) omniLiquidSpecularBorder(isDark) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)),
                shape = RoundedCornerShape(16.dp)
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
        // Inner top specular hairline sheen
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to if (isDark) Color(0x26FFFFFF) else Color(0x55FFFFFF),
                        0.18f to Color.Transparent
                    )
                )
        )

        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) effectiveContentColor else TextMuted,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = if (enabled) effectiveContentColor else TextMuted,
                fontSize = if (text.length > 14) 12.5.sp else if (text.length > 10) 13.5.sp else 14.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Cupertino-styled toggle switch with smooth iOS accent tinting.
 */
@Composable
fun CupertinoSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    activeColor: Color = OmniViolet
) {
    val isDark = LocalThemeIsDark.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = activeColor,
            uncheckedThumbColor = if (isDark) Color(0xFFCBD5E1) else Color.White,
            uncheckedTrackColor = if (isDark) Color(0xFF334155) else Color(0xFFCBD5E1)
        )
    )
}

/**
 * Standardized Apple iOS compact trailing action pill for list rows & settings items.
 * Guaranteed never to wrap onto multiple lines or overflow narrow device viewports.
 */
@Composable
fun CupertinoActionPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accentColor: Color? = null,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    val isDark = LocalThemeIsDark.current
    val haptic = LocalHapticFeedback.current
    val primaryColor = accentColor ?: if (isDestructive) Color(0xFFFF453A) else omniCyan(isDark)
    val bgColor = primaryColor.copy(alpha = if (isDark) 0.22f else 0.12f)
    val contentColor = primaryColor

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(bgColor)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(13.dp)
                )
            }
            Text(
                text = text,
                color = contentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

/**
 * Stitch Apple Liquid Glass 2-Column Bento KPI Metric Tile.
 * Features a 38dp circular glass status disc, 135-degree specular outline,
 * ambient radial glow, bold tabular metric, and status subtitle.
 */
@Composable
fun CupertinoMetricDisc(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val isDark = LocalThemeIsDark.current
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && onClick != null) 0.97f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "metricDiscScale"
    )

    IOSCard(
        modifier = modifier
            .scale(scale)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    }
                } else Modifier
            ),
        cornerRadius = 20.dp
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            // Ambient radial glow behind the disc
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .align(Alignment.TopEnd)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = if (isDark) 0.20f else 0.12f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title.uppercase(),
                        color = omniTextMuted(isDark),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.2.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = if (isDark) 0.18f else 0.10f))
                            .border(1.dp, accentColor.copy(alpha = if (isDark) 0.45f else 0.30f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = value,
                    color = omniTextPrimary(isDark),
                    fontSize = if (value.length > 8) 20.sp else 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.8).sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = subtitle,
                        color = omniTextMuted(isDark),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Stitch Smart Scanner Tactile Action Capsule for bottom controls (Override, Sensor, Enclave).
 */
@Composable
fun ScannerActionCapsule(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accentColor: Color = CyanCore,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    val isDark = LocalThemeIsDark.current
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(999.dp)
    val borderBrush: Brush = if (isActive) SolidColor(accentColor.copy(alpha = 0.6f)) else omniLiquidSpecularBorder(isDark)

    Box(
        modifier = modifier
            .clip(shape)
            .background(if (isActive) accentColor.copy(alpha = 0.22f) else if (isDark) Color(0x331E293B) else Color(0x1F000000))
            .border(
                0.75.dp,
                borderBrush,
                shape
            )
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) accentColor else omniTextPrimary(isDark),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = text.uppercase(),
                color = if (isActive) accentColor else omniTextPrimary(isDark),
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp,
                maxLines = 1
            )
        }
    }
}

