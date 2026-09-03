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
import androidx.compose.ui.graphics.Brush
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
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Overview", Icons.Default.Dashboard)
    object Scanner : Screen("scanner", "Scanner", Icons.Default.Videocam)
    object Enrollment : Screen("enrollment", "Students", Icons.Default.People)
    object Ledger : Screen("ledger", "Ledger", Icons.Default.Description)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

val IndustrialBottomNavTabs = listOf(
    Screen.Dashboard,
    Screen.Scanner,
    Screen.Enrollment,
    Screen.Ledger,
    Screen.Settings
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

    val dockBackground = if (isDark) {
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .shadow(
                    elevation = if (isDark) 12.dp else 10.dp,
                    shape = dockShape,
                    ambientColor = if (isDark) Color(0x99000000) else Color(0x240F172A),
                    spotColor = if (isDark) Color(0x330A84FF) else Color(0x1A0071E3)
                )
                .clip(dockShape)
                .background(dockBackground)
                .border(0.75.dp, omniLiquidSpecularBorder(isDark), dockShape)
                .padding(horizontal = 6.dp, vertical = 4.dp),
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
                    targetValue = if (isPressed) 0.92f else if (isSelected) 1.02f else 1.0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "tabScale"
                )

                val tabBgBrush = if (isSelected) {
                    if (isDark) {
                        Brush.verticalGradient(
                            listOf(
                                Color(0x2E0A84FF),
                                Color(0x140A84FF)
                            )
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(
                                Color(0x200071E3),
                                Color(0x0F0071E3)
                            )
                        )
                    }
                } else {
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                }

                val tabModifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .scale(tabScale)
                    .clip(RoundedCornerShape(18.dp))
                    .background(tabBgBrush)
                    .let { mod ->
                        if (isSelected) {
                            mod.border(
                                0.5.dp,
                                if (isDark) Color(0x380A84FF) else Color(0x280071E3),
                                RoundedCornerShape(18.dp)
                            )
                        } else mod
                    }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNavigate(screen)
                    }
                    .padding(vertical = 3.dp)

                Box(
                    modifier = tabModifier,
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.title,
                                tint = contentColor,
                                modifier = Modifier.size(21.dp)
                            )
                            if (screen == Screen.Ledger && unsyncedCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 6.dp, y = (-2).dp)
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(AmberCore)
                                        .border(1.dp, if (isDark) Color(0xFF131823) else Color.White, CircleShape)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        val localizedTitle = when (screen) {
                            Screen.Dashboard -> LocalizationManager.get(StringKey.TAB_OVERVIEW)
                            Screen.Scanner -> LocalizationManager.get(StringKey.TAB_SCANNER)
                            Screen.Enrollment -> LocalizationManager.get(StringKey.TAB_STUDENTS)
                            Screen.Ledger -> LocalizationManager.get(StringKey.TAB_LEDGER)
                            Screen.Settings -> LocalizationManager.get(StringKey.TAB_SETTINGS)
                        }
                        Text(
                            text = localizedTitle,
                            color = contentColor,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            letterSpacing = (-0.1).sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}
