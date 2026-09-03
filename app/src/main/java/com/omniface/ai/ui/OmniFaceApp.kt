package com.omniface.ai.ui

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniFaceApp() {
    val settingsViewModel: SettingsViewModel = viewModel()
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val themeMode = settingsState.selectedThemeMode
    val dynamicIslandController = remember { DynamicIslandController() }

    OmniFaceTheme(themeMode = themeMode) {
        val isDark = LocalThemeIsDark.current
        val context = LocalContext.current
        val activity = context as? Activity
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Dashboard.route

        // Double-back exit prevention on root Overview Dashboard with Dynamic Island prompt
        var lastBackPressTime by remember { mutableLongStateOf(0L) }
        BackHandler(enabled = currentRoute == Screen.Dashboard.route) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressTime < 2000L) {
                activity?.finish()
            } else {
                lastBackPressTime = currentTime
                dynamicIslandController.postEvent(
                    DynamicIslandEvent(
                        title = "Press back again to exit",
                        subtitle = "OmniFace AI",
                        accentColor = CyanCore
                    )
                )
                Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
            }
        }

        // Ledger stays activity-scoped so the tab-bar sync badge survives navigation;
        // the collected value is mapped+deduped to the COUNT only, so DB writes no longer
        // recompose the entire shell on every record insert.
        val ledgerViewModel: LedgerViewModel = viewModel()
        val unsyncedCount by remember(ledgerViewModel) {
            ledgerViewModel.uiState
                .map { state -> state.allRecords.count { !it.isSynced } }
                .distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = 0)

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
                        modifier = Modifier.fillMaxSize(),
                        enterTransition = { fadeIn(animationSpec = tween(200)) + slideInHorizontally(animationSpec = tween(200)) { 50 } },
                        exitTransition = { fadeOut(animationSpec = tween(200)) + slideOutHorizontally(animationSpec = tween(200)) { -50 } },
                        popEnterTransition = { fadeIn(animationSpec = tween(200)) + slideInHorizontally(animationSpec = tween(200)) { -50 } },
                        popExitTransition = { fadeOut(animationSpec = tween(200)) + slideOutHorizontally(animationSpec = tween(200)) { 50 } }
                    ) {
                        composable(Screen.Dashboard.route) { entry ->
                            val dashboardViewModel: DashboardViewModel =
                                viewModel(viewModelStoreOwner = entry)
                            DashboardScreen(
                                viewModel = dashboardViewModel,
                                onNavigate = { screen ->
                                    navController.navigate(screen.route) {
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }

                        composable(Screen.Scanner.route) { entry ->
                            // Scoped to this destination: camera + engine resources are
                            // released when the user navigates away from the Scanner.
                            val scannerViewModel: ScannerViewModel =
                                viewModel(viewModelStoreOwner = entry)
                            ScannerScreen(
                                viewModel = scannerViewModel,
                                onNavigateToEnroll = {
                                    navController.navigate(Screen.Enrollment.route) {
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }

                        composable(Screen.Enrollment.route) { entry ->
                            val enrollmentViewModel: EnrollmentViewModel =
                                viewModel(viewModelStoreOwner = entry)
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

                        composable(Screen.Settings.route) {
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
                                onDismiss = null
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
                }
            }
        }
    }
}
