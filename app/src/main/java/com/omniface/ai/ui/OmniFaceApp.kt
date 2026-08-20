package com.omniface.ai.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.omniface.ai.ui.components.DynamicIslandCapsule
import com.omniface.ai.ui.components.DynamicIslandController
import com.omniface.ai.ui.components.DynamicIslandEvent
import com.omniface.ai.ui.components.LocalDynamicIslandController
import com.omniface.ai.ui.dashboard.DashboardScreen
import com.omniface.ai.ui.dashboard.DashboardViewModel
import com.omniface.ai.ui.enrollment.EnrollmentScreen
import com.omniface.ai.ui.enrollment.EnrollmentViewModel
import com.omniface.ai.ui.ledger.LedgerScreen
import com.omniface.ai.ui.ledger.LedgerViewModel
import com.omniface.ai.ui.navigation.CupertinoTabBar
import com.omniface.ai.ui.navigation.Screen
import com.omniface.ai.ui.scanner.ScannerScreen
import com.omniface.ai.ui.scanner.ScannerViewModel
import com.omniface.ai.ui.settings.SettingsScreen
import com.omniface.ai.ui.settings.SettingsViewModel
import com.omniface.ai.ui.theme.CyanCore
import com.omniface.ai.ui.theme.LocalThemeIsDark
import com.omniface.ai.ui.theme.OmniFaceTheme
import com.omniface.ai.ui.theme.ThemeMode

import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniFaceApp() {
    val settingsViewModel: SettingsViewModel = viewModel()
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val themeMode = settingsState.selectedThemeMode
    val dynamicIslandController = remember { DynamicIslandController() }
    var showSettingsSheet by remember { mutableStateOf(false) }

    OmniFaceTheme(themeMode = themeMode) {
        val isDark = LocalThemeIsDark.current
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Dashboard.route

        val dashboardViewModel: DashboardViewModel = viewModel()
        val scannerViewModel: ScannerViewModel = viewModel()
        val enrollmentViewModel: EnrollmentViewModel = viewModel()
        val ledgerViewModel: LedgerViewModel = viewModel()
        val ledgerState by ledgerViewModel.uiState.collectAsStateWithLifecycle()
        val unsyncedCount = ledgerState.allRecords.count { !it.isSynced }

        CompositionLocalProvider(
            LocalDynamicIslandController provides dynamicIslandController
        ) {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                contentWindowInsets = WindowInsets.statusBars,
                containerColor = if (isDark) Color(0xFF000000) else Color(0xFFF2F2F7),
                bottomBar = {
                    CupertinoTabBar(
                        currentRoute = currentRoute,
                        unsyncedCount = unsyncedCount,
                        onNavigate = { screen ->
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(Screen.Dashboard.route) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Dashboard.route,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable(Screen.Dashboard.route) {
                            DashboardScreen(
                                viewModel = dashboardViewModel,
                                onNavigate = { screen ->
                                    navController.navigate(screen.route) {
                                        launchSingleTop = true
                                    }
                                },
                                onOpenSettings = { showSettingsSheet = true }
                            )
                        }

                        composable(Screen.Scanner.route) {
                            ScannerScreen(
                                viewModel = scannerViewModel,
                                onNavigateToEnroll = {
                                    navController.navigate(Screen.Enrollment.route) {
                                        launchSingleTop = true
                                    }
                                },
                                onOpenSettings = { showSettingsSheet = true }
                            )
                        }

                        composable(Screen.Enrollment.route) {
                            EnrollmentScreen(
                                viewModel = enrollmentViewModel,
                                onNavigateToScanner = {
                                    navController.navigate(Screen.Scanner.route) {
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }

                        composable(Screen.Ledger.route) {
                            LedgerScreen(
                                viewModel = ledgerViewModel
                            )
                        }
                    }

                    // Floating Dynamic Island Notification Capsule at the top
                    DynamicIslandCapsule(
                        controller = dynamicIslandController,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp)
                    )

                    // Apple iOS-Style Settings Modal Sheet
                    if (showSettingsSheet) {
                        ModalBottomSheet(
                            onDismissRequest = { showSettingsSheet = false },
                            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                            containerColor = if (isDark) Color(0xFF000000) else Color(0xFFF2F2F7),
                            dragHandle = {
                                Box(
                                    modifier = Modifier
                                        .padding(vertical = 12.dp)
                                        .size(width = 38.dp, height = 5.dp)
                                        .clip(CircleShape)
                                        .background(if (isDark) Color(0x38FFFFFF) else Color(0x26000000))
                                )
                            }
                        ) {
                            SettingsScreen(
                                viewModel = settingsViewModel,
                                onThemeModeChanged = { mode ->
                                    dynamicIslandController.postEvent(
                                        DynamicIslandEvent(
                                            title = "Theme Switched",
                                            subtitle = "${mode.name} Mode Activated",
                                            accentColor = CyanCore
                                        )
                                    )
                                },
                                onDismiss = { showSettingsSheet = false }
                            )
                        }
                    }
                }
            }
        }
    }
}
