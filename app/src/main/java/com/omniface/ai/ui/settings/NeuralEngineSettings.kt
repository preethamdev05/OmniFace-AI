package com.omniface.ai.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniface.ai.hardware.DeviceCapacityGovernor
import com.omniface.ai.ml.NeuralModelConfigManager
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey
import com.omniface.ai.ui.components.*
import com.omniface.ai.ui.theme.*

@Composable
fun NeuralEngineSettingsSubScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val isDark = LocalThemeIsDark.current
    val context = LocalContext.current
    val capacityProfile = remember { DeviceCapacityGovernor.evaluateDeviceCapacity(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(omniBackgroundBrush(isDark))
    ) {
        SettingsSubScreenHeader(
            title = LocalizationManager.get(StringKey.CAT_NEURAL),
            subtitle = LocalizationManager.get(StringKey.CAT_NEURAL_DESC),
            onBack = onBack
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Silicon Hardware Accelerator Status
            item {
                IOSCard(cornerRadius = 20.dp) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFFA855F7).copy(alpha = if (isDark) 0.22f else 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Memory,
                                        contentDescription = "NPU",
                                        tint = Color(0xFFA855F7),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = state.hardwareTier,
                                        color = omniTextPrimary(isDark),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${state.npuHardwareInfo.socModel} • ${state.npuHardwareInfo.peakTops}",
                                        color = omniTextMuted(isDark),
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            IOSGlassPill(
                                text = "${state.latencyMs}ms",
                                accentColor = if (state.latencyMs <= 10L) Color(0xFF34C759) else Color(0xFFFF9500)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Device Capacity Recommendation Banner
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isDark) Color(0x1A38BDF8) else Color(0x0F0284C7))
                                .border(0.5.dp, Color(0xFF38BDF8).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Status",
                                        tint = Color(0xFF38BDF8),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = capacityProfile.tier.badgeTitle,
                                        color = Color(0xFF38BDF8),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = capacityProfile.summaryRecommendation,
                                    color = omniTextPrimary(isDark),
                                    fontSize = 11.5.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }

            // 2. Granular Neural Model Toggles
            item {
                Text(
                    text = "AUXILIARY NEURAL PIPELINES",
                    color = omniTextMuted(isDark),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            item {
                IOSCard(cornerRadius = 20.dp) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Multi-Stage PAD
                        SettingRow(
                            title = "Multi-Stage Passive PAD",
                            subtitle = "LBP frequency & shadow anti-spoofing",
                            icon = Icons.Default.Shield,
                            trailing = {
                                CupertinoSwitch(
                                    checked = state.neuralModelConfig.isMultiStageLivenessEnabled,
                                    onCheckedChange = { NeuralModelConfigManager.setMultiStageLivenessEnabled(it) }
                                )
                            }
                        )

                        // Temporal Liveness
                        SettingRow(
                            title = "Temporal Liveness Tracking",
                            subtitle = "Multi-frame micro-motion & blink consensus",
                            icon = Icons.Default.MotionPhotosAuto,
                            trailing = {
                                CupertinoSwitch(
                                    checked = state.neuralModelConfig.isTemporalLivenessEnabled,
                                    onCheckedChange = { NeuralModelConfigManager.setTemporalLivenessEnabled(it) }
                                )
                            }
                        )

                        // 3DMM FaceMap
                        SettingRow(
                            title = "FaceMap 3DMM Depth",
                            subtitle = "265-parameter 3D facial depth topography",
                            icon = Icons.Default.ViewInAr,
                            trailing = {
                                CupertinoSwitch(
                                    checked = state.neuralModelConfig.isFaceMap3DMMEnabled,
                                    onCheckedChange = { NeuralModelConfigManager.setFaceMap3DMMEnabled(it) }
                                )
                            }
                        )

                        // EyeGaze Tracking
                        SettingRow(
                            title = "EyeGaze Subpixel Tracking",
                            subtitle = "Pupil fixation rays & attentiveness detection",
                            icon = Icons.Default.Visibility,
                            trailing = {
                                CupertinoSwitch(
                                    checked = state.neuralModelConfig.isEyeGazeEnabled,
                                    onCheckedChange = { NeuralModelConfigManager.setEyeGazeEnabled(it) }
                                )
                            }
                        )

                        // MediaPipe Mesh
                        SettingRow(
                            title = "MediaPipe 468-Point Mesh",
                            subtitle = "Real-time dense 3D topological wireframe",
                            icon = Icons.Default.GridOn,
                            trailing = {
                                CupertinoSwitch(
                                    checked = state.neuralModelConfig.isMediaPipeMeshEnabled,
                                    onCheckedChange = { NeuralModelConfigManager.setMediaPipeMeshEnabled(it) }
                                )
                            }
                        )

                        // Face Attributes
                        SettingRow(
                            title = "FaceAttribNet Diagnostics",
                            subtitle = "Smile, eyeglasses & facial hair HUD tags",
                            icon = Icons.Default.Face,
                            trailing = {
                                CupertinoSwitch(
                                    checked = state.neuralModelConfig.isFaceAttribEnabled,
                                    onCheckedChange = { NeuralModelConfigManager.setFaceAttribEnabled(it) }
                                )
                            }
                        )
                    }
                }
            }

            // 3. Cloudflare R2 Sovereign Storage Zero-Trust Architecture
            item {
                IOSCard(cornerRadius = 20.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF38BDF8).copy(alpha = if (isDark) 0.22f else 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDone,
                                        contentDescription = "Cloudflare R2",
                                        tint = Color(0xFF38BDF8),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Cloudflare R2 Sovereign Storage",
                                        color = omniTextPrimary(isDark),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Zero-Trust Tokenless Edge CDN",
                                        color = omniTextMuted(isDark),
                                        fontSize = 11.5.sp
                                    )
                                }
                            }

                            IOSGlassPill(
                                text = "Active",
                                accentColor = Color(0xFF34C759)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Model flatbuffers are distributed seamlessly via Cloudflare R2 Edge CDN buckets with AES-256 integrity gates. Zero user tokens, credentials, or manual link configuration required.",
                            color = omniTextMuted(isDark),
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}
