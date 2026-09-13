package com.omniface.ai.ui.scanner

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniface.ai.ui.components.IOSCard
import com.omniface.ai.ui.theme.*

@Composable
fun NeuralEngineLoadingOverlay(
    loading: com.omniface.ai.ml.EngineLoadingProgress,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (isDark) Color(0xD90B0F19) else Color(0xD9FFFFFF)),
        contentAlignment = Alignment.Center
    ) {
        IOSCard(
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Pulsing circular indicator
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(if (isDark) Color(0x220284C7) else Color(0x1A0284C7)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { loading.progress },
                        modifier = Modifier.size(68.dp),
                        color = Color(0xFF0284C7),
                        trackColor = if (isDark) Color(0x330284C7) else Color(0x1A0284C7),
                        strokeWidth = 3.5.dp
                    )
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = "Neural Accelerator",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(30.dp)
                    )
                }

                // Stage title & details
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "LOADING NEURAL MODEL",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = loading.activeModelName,
                        color = omniTextPrimary(isDark),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = loading.stage,
                        color = omniTextSecondary(isDark),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }

                // Progress Bar with Percentage
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = loading.hardwareTarget,
                            color = omniTextMuted(isDark),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${(loading.progress * 100).toInt()}%",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { loading.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFF0284C7),
                        trackColor = if (isDark) Color(0x22FFFFFF) else Color(0x14000000)
                    )
                }

                Text(
                    text = "Compiling neural graph tensors on silicon hardware. Inference warmup in progress...",
                    color = omniTextMuted(isDark),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
internal fun CupertinoDockIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color,
    isCenterAccent: Boolean = false,
    onClick: () -> Unit
) {
    val isDark = LocalThemeIsDark.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "dockBtnScale"
    )

    val bgColor by animateColorAsState(
        targetValue = if (isCenterAccent) {
            Color.Transparent
        } else if (isActive) {
            activeColor.copy(alpha = if (isDark) 0.28f else 0.18f)
        } else {
            if (isDark) Color(0x1AFFFFFF) else Color(0x0A000000)
        },
        label = "dockBtnBg"
    )

    val iconTint by animateColorAsState(
        targetValue = if (isCenterAccent) Color.White else if (isActive) activeColor else omniTextPrimary(isDark),
        label = "dockBtnTint"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .then(
                    if (isCenterAccent) {
                        Modifier
                            .shadow(8.dp, RoundedCornerShape(15.dp), spotColor = Color(0x886366F1))
                            .clip(RoundedCornerShape(15.dp))
                            .background(OmniButtonBrush)
                    } else {
                        Modifier
                            .clip(RoundedCornerShape(13.dp))
                            .background(bgColor)
                            .border(
                                0.5.dp,
                                if (isActive) activeColor.copy(alpha = 0.6f) else (if (isDark) Color(0x22FFFFFF) else Color(0x12000000)),
                                RoundedCornerShape(13.dp)
                            )
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = if (isCenterAccent) (if (isDark) Color.White else OmniDeepPurple) else if (isActive) activeColor else omniTextMuted(isDark),
            fontSize = 10.sp,
            fontWeight = if (isCenterAccent || isActive) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = (-0.1).sp
        )
    }
}
