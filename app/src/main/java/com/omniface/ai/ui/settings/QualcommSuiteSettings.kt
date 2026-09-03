package com.omniface.ai.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.omniface.ai.hardware.ModelCompatibilityStatus
import com.omniface.ai.hardware.ModelHardwareRequirement
import com.omniface.ai.ml.QualcommModelState
import com.omniface.ai.ml.QualcommSuiteDownloadManager
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey
import com.omniface.ai.ui.components.*
import com.omniface.ai.ui.theme.*

@Composable
fun QualcommSuiteSettingsSubScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val isDark = LocalThemeIsDark.current
    val context = LocalContext.current
    val modelRequirements = remember { DeviceCapacityGovernor.getModelRequirements(context) }
    val deviceProfile = remember { DeviceCapacityGovernor.evaluateDeviceCapacity(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(omniBackgroundBrush(isDark))
    ) {
        SettingsSubScreenHeader(
            title = LocalizationManager.get(StringKey.CAT_QUALCOMM),
            subtitle = LocalizationManager.get(StringKey.CAT_QUALCOMM_DESC),
            onBack = onBack
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Device Compatibility Advisory Card
            item {
                IOSCard(cornerRadius = 20.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFFFF9500).copy(alpha = if (isDark) 0.22f else 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Hub,
                                    contentDescription = "Hub",
                                    tint = Color(0xFFFF9500),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Device Capacity Calibration",
                                    color = omniTextPrimary(isDark),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${deviceProfile.npuInfo.socModel} • ${deviceProfile.tier.badgeTitle}",
                                    color = Color(0xFFFF9500),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Unified Neural Models Hub connects directly to hardware neural acceleration on your ${deviceProfile.npuInfo.npuName}. Models marked 'Recommended' execute with zero frame drops and low thermal load.",
                            color = omniTextMuted(isDark),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Model Cards List
            items(modelRequirements) { req ->
                ModelCapacityCard(
                    req = req,
                    state = state,
                    viewModel = viewModel,
                    isDark = isDark
                )
            }
        }
    }
}

@Composable
private fun ModelCapacityCard(
    req: ModelHardwareRequirement,
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    isDark: Boolean
) {
    val modelState = state.qualcommSuiteStates[req.modelId] ?: QualcommModelState.Idle
    val isBundled = req.modelId == "mobilefacenet_bundled"

    IOSCard(cornerRadius = 18.dp) {
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = req.displayName,
                        color = omniTextPrimary(isDark),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${req.fileSizeFormatted} • ${req.targetProcessor} • ${req.expectedFps}",
                        color = omniTextMuted(isDark),
                        fontSize = 11.5.sp
                    )
                }

                // Compatibility Status Badge
                val (badgeBg, badgeTextColor) = when (req.compatibilityStatus) {
                    ModelCompatibilityStatus.RECOMMENDED_OPTIMAL -> Color(0xFF34C759).copy(alpha = 0.2f) to Color(0xFF34C759)
                    ModelCompatibilityStatus.SUPPORTED_MODERATE_LOAD -> Color(0xFF38BDF8).copy(alpha = 0.2f) to Color(0xFF38BDF8)
                    ModelCompatibilityStatus.HIGH_LOAD_DISCOURAGED -> Color(0xFFFF9500).copy(alpha = 0.2f) to Color(0xFFFF9500)
                    ModelCompatibilityStatus.NOT_SUPPORTED_HARDWARE -> Color(0xFF8E8E93).copy(alpha = 0.2f) to Color(0xFF8E8E93)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeBg)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = req.compatibilityStatus.label,
                        color = badgeTextColor,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = req.description,
                color = omniTextMuted(isDark),
                fontSize = 11.5.sp,
                lineHeight = 15.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons
            if (isBundled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Active",
                        tint = Color(0xFF34C759),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Pre-installed Active Model",
                        color = Color(0xFF34C759),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                when (modelState) {
                    is QualcommModelState.Idle, is QualcommModelState.Unavailable -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            CupertinoButton(
                                text = "${LocalizationManager.get(StringKey.DOWNLOAD_MODEL)} (${req.fileSizeFormatted})",
                                icon = Icons.Default.Download,
                                modifier = Modifier.widthIn(min = 140.dp),
                                onClick = { viewModel.downloadQualcommModel(req.modelId) }
                            )
                        }
                    }
                    is QualcommModelState.Downloading -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = LocalizationManager.get(StringKey.TESTING),
                                    color = Color(0xFF38BDF8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${(modelState.progress * 100).toInt()}%",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { modelState.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = Color(0xFF38BDF8),
                                trackColor = if (isDark) Color(0x22FFFFFF) else Color(0x14000000)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { viewModel.cancelQualcommDownload(req.modelId) }
                                ) {
                                    Text(LocalizationManager.get(StringKey.CANCEL_ACTION), color = Color(0xFFFF3B30), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    is QualcommModelState.Installed -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Installed",
                                    tint = Color(0xFF34C759),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = LocalizationManager.get(StringKey.MODEL_READY),
                                    color = Color(0xFF34C759),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            TextButton(
                                onClick = { viewModel.deleteQualcommModel(req.modelId) }
                            ) {
                                Text(LocalizationManager.get(StringKey.REMOVE_MODEL), color = Color(0xFFFF3B30), fontSize = 12.sp)
                            }
                        }
                    }
                    is QualcommModelState.Error -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Error: ${modelState.message}",
                                color = Color(0xFFFF3B30),
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                            CupertinoButton(
                                text = LocalizationManager.get(StringKey.RETRY_ACTION),
                                icon = Icons.Default.Refresh,
                                modifier = Modifier.widthIn(min = 90.dp, max = 120.dp),
                                onClick = { viewModel.downloadQualcommModel(req.modelId) }
                            )
                        }
                    }
                }
            }
        }
    }
}
