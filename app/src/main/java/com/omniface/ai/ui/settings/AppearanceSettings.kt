package com.omniface.ai.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniface.ai.audio.SoundEnvironmentMode
import com.omniface.ai.i18n.AppLanguage
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey
import com.omniface.ai.ui.components.*
import com.omniface.ai.ui.theme.*

@Composable
fun AppearanceSettingsSubScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onThemeModeChanged: (ThemeMode) -> Unit
) {
    val isDark = LocalThemeIsDark.current
    val context = LocalContext.current
    var selectedLang by remember { mutableStateOf(LocalizationManager.currentLanguage.value) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(omniBackgroundBrush(isDark))
    ) {
        SettingsSubScreenHeader(
            title = LocalizationManager.get(StringKey.SETTINGS_APPEARANCE_TITLE),
            subtitle = LocalizationManager.get(StringKey.SETTINGS_APPEARANCE_SUBTITLE),
            onBack = onBack
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Theme Mode Card
            item {
                IOSCard(cornerRadius = 20.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                    ) {
                        Text(
                            text = LocalizationManager.get(StringKey.SETTINGS_THEME_TITLE),
                            color = omniTextPrimary(isDark),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = LocalizationManager.get(StringKey.SETTINGS_THEME_SUBTITLE),
                            color = omniTextMuted(isDark),
                            fontSize = 12.5.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        CupertinoSegmentedControl(
                            items = listOf(
                                LocalizationManager.get(StringKey.THEME_LIGHT),
                                LocalizationManager.get(StringKey.THEME_DARK),
                                LocalizationManager.get(StringKey.THEME_SYSTEM)
                            ),
                            selectedIndex = when (state.selectedThemeMode) {
                                ThemeMode.LIGHT -> 0
                                ThemeMode.DARK -> 1
                                ThemeMode.SYSTEM -> 2
                            },
                            onItemSelected = { idx ->
                                val mode = when (idx) {
                                    0 -> ThemeMode.LIGHT
                                    1 -> ThemeMode.DARK
                                    else -> ThemeMode.SYSTEM
                                }
                                viewModel.setThemeMode(mode)
                                onThemeModeChanged(mode)
                            }
                        )
                    }
                }
            }

            // 2. 10 Major Indian Languages Selector
            item {
                IOSCard(cornerRadius = 20.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = LocalizationManager.get(StringKey.SETTINGS_LANGUAGE_TITLE),
                                    color = omniTextPrimary(isDark),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = LocalizationManager.get(StringKey.SETTINGS_LANGUAGE_SUBTITLE),
                                    color = omniTextMuted(isDark),
                                    fontSize = 12.5.sp
                                )
                            }

                            IOSGlassPill(
                                text = selectedLang.nativeName,
                                accentColor = Color(0xFF38BDF8)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Grid of 10 Indian Languages
                        val languages = AppLanguage.values()
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            languages.toList().chunked(2).forEach { rowPair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowPair.forEach { lang ->
                                        val isSelected = lang == selectedLang
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(
                                                    if (isSelected) Color(0xFF38BDF8).copy(alpha = if (isDark) 0.25f else 0.15f)
                                                    else if (isDark) Color(0x1A1E293B) else Color(0xFFF1F5F9)
                                                )
                                                .border(
                                                    width = if (isSelected) 1.5.dp else 0.5.dp,
                                                    color = if (isSelected) Color(0xFF38BDF8) else if (isDark) Color(0x38FFFFFF) else Color(0x1A000000),
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                .clickable {
                                                    selectedLang = lang
                                                    LocalizationManager.setLanguage(context, lang)
                                                    viewModel.setLanguage(lang)
                                                }
                                                .padding(vertical = 10.dp, horizontal = 12.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text(
                                                        text = lang.nativeName,
                                                        color = if (isSelected) Color(0xFF38BDF8) else omniTextPrimary(isDark),
                                                        fontSize = 13.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                    )
                                                    Text(
                                                        text = lang.displayName,
                                                        color = omniTextMuted(isDark),
                                                        fontSize = 10.5.sp
                                                    )
                                                }
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.CheckCircle,
                                                        contentDescription = "Selected",
                                                        tint = Color(0xFF38BDF8),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (rowPair.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Acoustic Environment Audio Mode
            item {
                IOSCard(cornerRadius = 20.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                    ) {
                        Text(
                            text = LocalizationManager.get(StringKey.SETTINGS_SOUND_TITLE),
                            color = omniTextPrimary(isDark),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = LocalizationManager.get(StringKey.SETTINGS_SOUND_SUBTITLE),
                            color = omniTextMuted(isDark),
                            fontSize = 12.5.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        CupertinoSegmentedControl(
                            items = listOf(
                                LocalizationManager.get(StringKey.ACOUSTIC_HALLWAY),
                                LocalizationManager.get(StringKey.ACOUSTIC_CLASSROOM),
                                LocalizationManager.get(StringKey.ACOUSTIC_SILENT)
                            ),
                            selectedIndex = when (state.selectedSoundMode) {
                                SoundEnvironmentMode.NOISY_HALLWAY -> 0
                                SoundEnvironmentMode.QUIET_CLASSROOM -> 1
                                SoundEnvironmentMode.SILENT_VIBRATION -> 2
                            },
                            onItemSelected = { idx ->
                                val mode = when (idx) {
                                    0 -> SoundEnvironmentMode.NOISY_HALLWAY
                                    1 -> SoundEnvironmentMode.QUIET_CLASSROOM
                                    else -> SoundEnvironmentMode.SILENT_VIBRATION
                                }
                                viewModel.setSoundMode(mode)
                            }
                        )
                    }
                }
            }
        }
    }
}
