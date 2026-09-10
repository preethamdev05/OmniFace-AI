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
    val orgType by LocalizationManager.currentOrgType.collectAsState()
    val isDark = LocalThemeIsDark.current
    val haptic = LocalHapticFeedback.current
    val dockShape = RoundedCornerShape(30.dp)

    val dockBackground = if (isDark) {
        Brush.verticalGradient(
            listOf(
                Color(0xF212172A),
                Color(0xFA0B0F1C)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color(0xF8FFFFFF),
                Color(0xEEF8FAFC)
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .shadow(
                    elevation = if (isDark) 16.dp else 12.dp,
                    shape = dockShape,
                    ambientColor = if (isDark) Color(0x99000000) else Color(0x206366F1),
                    spotColor = if (isDark) Color(0x406366F1) else Color(0x266366F1)
                )
                .clip(dockShape)
                .background(dockBackground)
                .border(
                    0.8.dp,
                    if (isDark) Brush.verticalGradient(listOf(Color(0x38818CF8), Color(0x10818CF8)))
                    else Brush.verticalGradient(listOf(Color(0x206366F1), Color(0x10000000))),
                    dockShape
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IndustrialBottomNavTabs.forEach { screen ->
                key(screen.route) {
                    val isSelected = currentRoute == screen.route
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            if (isDark) Color.White else Color(0xFF6366F1)
                        } else omniTextMuted(isDark),
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "tabColor"
                    )

                    val interactionSource = remember(screen.route) { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()

                    val tabScale by animateFloatAsState(
                        targetValue = if (isPressed) 0.92f else if (isSelected) 1.02f else 1.0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        ),
                        label = "tabScale"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onNavigate(screen)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier
                                .scale(tabScale)
                                .padding(vertical = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 46.dp, height = 28.dp)
                                    .then(
                                        if (isSelected) {
                                            Modifier
                                                .shadow(
                                                    elevation = 6.dp,
                                                    shape = RoundedCornerShape(14.dp),
                                                    ambientColor = Color(0x4D6366F1),
                                                    spotColor = Color(0x668B5CF6)
                                                )
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(OmniButtonBrush)
                                        } else {
                                            Modifier.clip(RoundedCornerShape(14.dp))
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                        Icon(
                            imageVector = screen.icon,
                            contentDescription = screen.title,
                            tint = if (isSelected) Color.White else omniTextMuted(isDark),
                            modifier = Modifier.size(18.dp)
                        )
                        if (screen == Screen.Ledger && unsyncedCount > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-2).dp)
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(OmniAmber)
                                    .border(1.dp, if (isDark) Color(0xFF131823) else Color.White, CircleShape)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    val localizedTitle = when (screen) {
                        Screen.Dashboard -> LocalizationManager.get(StringKey.TAB_OVERVIEW)
                        Screen.Scanner -> LocalizationManager.get(StringKey.TAB_SCANNER)
                        Screen.Enrollment -> LocalizationManager.getDirectoryTabTitle(orgType)
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
}
