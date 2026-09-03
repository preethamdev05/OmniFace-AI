package com.omniface.ai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.sp
import com.omniface.ai.ui.theme.*

enum class IslandPriority {
    HIGH,
    NORMAL
}

@Immutable
data class DynamicIslandEvent(
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector = Icons.Default.Memory,
    val accentColor: Color = CyanCore,
    val durationMs: Long = 2800L,
    val priority: IslandPriority = IslandPriority.NORMAL
)

val LocalDynamicIslandController = compositionLocalOf { DynamicIslandController() }

class DynamicIslandController {
    private val eventQueue = java.util.ArrayDeque<DynamicIslandEvent>()
    var activeEvent by mutableStateOf<DynamicIslandEvent?>(null)
        private set

    @Synchronized
    fun postEvent(event: DynamicIslandEvent) {
        if (activeEvent == null) {
            activeEvent = event
        } else {
            if (event.priority == IslandPriority.HIGH) {
                eventQueue.addFirst(event)
            } else {
                eventQueue.offer(event)
            }
        }
    }

    @Synchronized
    fun dismiss() {
        if (eventQueue.isNotEmpty()) {
            activeEvent = eventQueue.poll()
        } else {
            activeEvent = null
        }
    }

    @Synchronized
    fun clearAll() {
        eventQueue.clear()
        activeEvent = null
    }
}

@Composable
fun DynamicIslandCapsule(
    controller: DynamicIslandController,
    modifier: Modifier = Modifier
) {
    val event = controller.activeEvent
    val isDark = LocalThemeIsDark.current

    LaunchedEffect(event) {
        if (event != null) {
            kotlinx.coroutines.delay(event.durationMs)
            controller.dismiss()
        }
    }

    AnimatedVisibility(
        visible = event != null,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        if (event != null) {
            val width by animateDpAsState(
                targetValue = if (event.subtitle != null) 260.dp else 210.dp,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "islandWidth"
            )

            var isPressed by remember { mutableStateOf(false) }
            val scale by animateFloatAsState(
                targetValue = if (isPressed) 0.95f else 1.0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "capsuleScale"
            )

            Box(
                modifier = Modifier
                    .width(width)
                    .scale(scale)
                    .shadow(
                        elevation = if (isDark) 10.dp else 12.dp,
                        shape = RoundedCornerShape(999.dp),
                        ambientColor = if (isDark) Color(0x99000000) else Color(0x33000000),
                        spotColor = event.accentColor.copy(alpha = 0.25f)
                    )
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (isDark) {
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xF0182234),
                                    Color(0xFA0F172A)
                                )
                            )
                        } else {
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xF5FFFFFF),
                                    Color(0xEEF1F5F9)
                                )
                            )
                        }
                    )
                    .border(
                        0.75.dp,
                        omniLiquidSpecularBorder(isDark),
                        RoundedCornerShape(999.dp)
                    )
                    .clickable {
                        isPressed = true
                        controller.dismiss()
                    }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(event.accentColor.copy(alpha = 0.18f))
                            .border(0.5.dp, event.accentColor.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = event.icon,
                            contentDescription = null,
                            tint = event.accentColor,
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = event.title,
                            color = omniTextPrimary(isDark),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.2).sp
                        )
                        if (event.subtitle != null) {
                            Text(
                                text = event.subtitle,
                                color = omniTextSecondary(isDark),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Stitch Aetheric Biometrics Dynamic Island Live Telemetry Capsule.
 */
@Composable
fun BiometricLiveTelemetryCapsule(
    depthVariance: Float,
    isGazeAttentive: Boolean,
    livenessProbability: Float,
    pulseBpm: Int = 72,
    isPulseValid: Boolean = false,
    isDark: Boolean,
    hasFace: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .shadow(
                elevation = if (isDark) 8.dp else 4.dp,
                shape = RoundedCornerShape(999.dp)
            )
            .clip(RoundedCornerShape(999.dp))
            .background(if (isDark) Color(0xD90F172A) else Color(0xF2FFFFFF))
            .border(0.75.dp, omniLiquidSpecularBorder(isDark), RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 3DMM Depth Variance
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (hasFace) omniCyan(isDark) else omniTextMuted(isDark))
            )
            Text(
                text = if (hasFace) "3DMM: %.3f Var".format(depthVariance) else "3DMM: Standby",
                color = if (hasFace) omniTextSecondary(isDark) else omniTextMuted(isDark),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Thin Specular Separator
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(10.dp)
                .background(if (isDark) Color(0x33FFFFFF) else Color(0x33000000))
        )

        // Eye Gaze Attentiveness
        Text(
            text = if (!hasFace) "Gaze: Standby" else if (isGazeAttentive) "Gaze: Direct" else "Gaze: Off-Axis",
            color = if (!hasFace) omniTextMuted(isDark) else if (isGazeAttentive) omniEmerald(isDark) else Color(0xFFFF9500),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold
        )

        // Thin Specular Separator
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(10.dp)
                .background(if (isDark) Color(0x33FFFFFF) else Color(0x33000000))
        )

        // Contactless rPPG Pulse
        Text(
            text = if (!hasFace) "Pulse: Standby" else "Pulse: $pulseBpm BPM",
            color = if (!hasFace) omniTextMuted(isDark) else omniEmerald(isDark),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold
        )

        // Thin Specular Separator
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(10.dp)
                .background(if (isDark) Color(0x33FFFFFF) else Color(0x33000000))
        )

        // Liveness Probability
        Text(
            text = if (!hasFace) "Live: Standby" else "Live: ${(livenessProbability * 100).toInt()}%",
            color = if (!hasFace) omniTextMuted(isDark) else if (livenessProbability >= 0.70f) omniEmerald(isDark) else Color(0xFFFF453A),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
