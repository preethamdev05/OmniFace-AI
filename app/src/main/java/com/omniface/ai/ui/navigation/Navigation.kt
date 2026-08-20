package com.omniface.ai.ui.navigation

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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniface.ai.ui.components.liquidGlassBackdrop
import com.omniface.ai.ui.components.omniLiquidSpecularBorder
import com.omniface.ai.ui.components.omniLiquidSurfaceBrush
import com.omniface.ai.ui.theme.*

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Overview", Icons.Default.Dashboard)
    object Scanner : Screen("scanner", "Scanner", Icons.Default.Videocam)
    object Enrollment : Screen("enrollment", "Students", Icons.Default.People)
    object Ledger : Screen("ledger", "Ledger", Icons.Default.Description)
}

val IndustrialBottomNavTabs = listOf(
    Screen.Dashboard,
    Screen.Scanner,
    Screen.Enrollment,
    Screen.Ledger
)

@Composable
fun CupertinoTabBar(
    currentRoute: String,
    unsyncedCount: Int = 0,
    onNavigate: (Screen) -> Unit
) {
    val isDark = LocalThemeIsDark.current
    val haptic = LocalHapticFeedback.current
    val dockShape = RoundedCornerShape(26.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .shadow(
                    elevation = if (isDark) 8.dp else 12.dp,
                    shape = dockShape,
                    ambientColor = if (isDark) Color(0x66000000) else Color(0x26000000),
                    spotColor = if (isDark) Color(0x4D000000) else Color(0x1F000000)
                )
                .clip(dockShape)
                .background(omniLiquidSurfaceBrush(isDark))
                .border(0.75.dp, omniLiquidSpecularBorder(isDark), dockShape)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IndustrialBottomNavTabs.forEach { screen ->
                val isSelected = currentRoute == screen.route
                val contentColor by animateColorAsState(
                    targetValue = if (isSelected) omniCyan(isDark) else omniTextMuted(isDark),
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "tabColor"
                )

                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()

                val tabScale by animateFloatAsState(
                    targetValue = if (isPressed) 0.92f else if (isSelected) 1.05f else 1.0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "tabScale"
                )

                val tabBgColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        if (isDark) Color(0x260A84FF) else Color(0x1A007AFF)
                    } else Color.Transparent,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "tabBg"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .scale(tabScale)
                        .clip(RoundedCornerShape(18.dp))
                        .background(tabBgColor)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNavigate(screen)
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.title,
                                tint = contentColor,
                                modifier = Modifier.size(22.dp)
                            )
                            if (screen == Screen.Ledger && unsyncedCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 6.dp, y = (-3).dp)
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(AmberCore)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = screen.title,
                            color = contentColor,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            letterSpacing = 0.2.sp
                        )
                        if (isSelected) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(omniCyan(isDark))
                            )
                        }
                    }
                }
            }
        }
    }
}
