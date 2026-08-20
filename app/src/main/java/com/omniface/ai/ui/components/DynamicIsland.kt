package com.omniface.ai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.draw.shadow
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

            Box(
                modifier = Modifier
                    .width(width)
                    .shadow(
                        elevation = if (isDark) 6.dp else 10.dp,
                        shape = RoundedCornerShape(999.dp),
                        ambientColor = if (isDark) Color(0x66000000) else Color(0x26000000),
                        spotColor = if (isDark) Color(0x4D000000) else Color(0x1F000000)
                    )
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isDark) Color(0xFF0F172A).copy(alpha = 0.95f) else Color(0xFFFFFFFF).copy(alpha = 0.95f))
                    .border(
                        0.75.dp,
                        omniLiquidSpecularBorder(isDark),
                        RoundedCornerShape(999.dp)
                    )
                    .clickable { controller.dismiss() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(event.accentColor.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = event.icon,
                            contentDescription = null,
                            tint = event.accentColor,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = event.title,
                            color = omniTextPrimary(isDark),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (event.subtitle != null) {
                            Text(
                                text = event.subtitle,
                                color = omniTextMuted(isDark),
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
