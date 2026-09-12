package com.omniface.ai.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey
import androidx.compose.ui.platform.LocalContext
import com.omniface.ai.ml.ModelDownloadState
import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.ui.components.*
import com.omniface.ai.ui.theme.*

@Composable
fun BiometricSettingsSubScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val isDark = LocalThemeIsDark.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(omniBackgroundBrush(isDark))
    ) {
        SettingsSubScreenHeader(
            title = LocalizationManager.get(StringKey.CAT_BIOMETRICS),
            subtitle = LocalizationManager.get(StringKey.CAT_BIOMETRICS_DESC),
            onBack = onBack
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 0. Sovereign Cloud AI Model Management (Unified 380 MB Model)
            item {
                val context = LocalContext.current
                IOSCard(cornerRadius = 20.dp) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(Color(0xFF0A84FF).copy(alpha = if (isDark) 0.22f else 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = "Neural Model",
                                        tint = Color(0xFF0A84FF),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "OmniFace Deep AI Engine",
                                        color = omniTextPrimary(isDark),
                                        fontSize = 15.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Unified Biometric Recognition & Anti-Spoofing",
                                        color = omniTextMuted(isDark),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))
                            if (state.isNeuralModelInstalled) {
                                IOSGlassPill(
                                    text = "INSTALLED (OFFLINE)",
                                    accentColor = Color(0xFF34C759)
                                )
                            } else {
                                IOSGlassPill(
                                    text = "NOT INSTALLED",
                                    accentColor = Color(0xFFFF9500)
                                )
                            }
                        }

                        // Status Info Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isDark) Color(0x1A1E293B) else Color(0xFFF1F5F9))
                                .padding(10.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Model Version", color = omniTextMuted(isDark), fontSize = 11.sp)
                                    Text("OmniFace v2.4 (Unified)", color = omniTextPrimary(isDark), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Model Size", color = omniTextMuted(isDark), fontSize = 11.sp)
                                    Text("45.8 MB (Optimized FP16)", color = omniTextPrimary(isDark), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Device Status", color = omniTextMuted(isDark), fontSize = 11.sp)
                                    Text(
                                        if (state.isNeuralModelInstalled) "Installed • Ready for Offline Use" else "Required for Identification",
                                        color = if (state.isNeuralModelInstalled) Color(0xFF34C759) else Color(0xFFFF9500),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        // Download State / Controls
                        when (val dl = state.modelDownloadState) {
                            is ModelDownloadState.Downloading -> {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Downloading AI Face Pack: ${(dl.progress * 100).toInt()}% • ${String.format(java.util.Locale.US, "%.1f", dl.downloadedMb)}/${String.format(java.util.Locale.US, "%.1f", dl.totalMb)} MB",
                                            color = omniTextPrimary(isDark),
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f, fill = false),
                                            maxLines = 1
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "${dl.speedKbps} KB/s",
                                            color = Color(0xFF0A84FF),
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { dl.progress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = Color(0xFF0A84FF),
                                        trackColor = if (isDark) Color(0x33FFFFFF) else Color(0x1A000000)
                                    )
                                    TextButton(
                                        onClick = { viewModel.cancelModelDownload() },
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        Text("Cancel Download", color = Color(0xFFFF3B30), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            is ModelDownloadState.Verifying -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF34C759))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Verifying AI Model Integrity...", fontSize = 12.sp, color = omniTextPrimary(isDark))
                                }
                            }
                            is ModelDownloadState.Error -> {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(dl.message, color = Color(0xFFFF3B30), fontSize = 11.5.sp)
                                    Button(
                                        onClick = { viewModel.startModelDownload(context) },
                                        modifier = Modifier.fillMaxWidth().height(40.dp),
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9500))
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Retry Download (380 MB)", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }
                            else -> {
                                if (!state.isNeuralModelInstalled) {
                                    Button(
                                        onClick = { viewModel.startModelDownload(context) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(44.dp),
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                                    ) {
                                        Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Download OmniFace Sovereign Engine (380 MB)", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { viewModel.startModelDownload(context) },
                                            modifier = Modifier.weight(1f).height(40.dp),
                                            shape = CircleShape
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Re-download", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.deleteDownloadedModel(context) },
                                            modifier = Modifier.weight(1f).height(40.dp),
                                            shape = CircleShape,
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF3B30))
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFFFF3B30))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Delete Model", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFF3B30))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // 1. ISO/IEC Accuracy Tier
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
                                        .clip(CircleShape)
                                        .background(Color(0xFF34C759).copy(alpha = if (isDark) 0.22f else 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VerifiedUser,
                                        contentDescription = "ISO Tier",
                                        tint = Color(0xFF34C759),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = LocalizationManager.get(StringKey.DECISION_TIER_SETTING),
                                        color = omniTextPrimary(isDark),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = LocalizationManager.get(StringKey.ISO_OPERATING_POINTS),
                                        color = omniTextMuted(isDark),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        CupertinoSegmentedControl(
                            items = listOf(
                                LocalizationManager.get(StringKey.TIER_STANDARD),
                                LocalizationManager.get(StringKey.TIER_HIGH),
                                LocalizationManager.get(StringKey.TIER_STRICT)
                            ),
                            selectedIndex = when (state.selectedTier) {
                                SecurityTier.STANDARD -> 0
                                SecurityTier.HIGH -> 1
                                SecurityTier.STRICT -> 2
                            },
                            onItemSelected = { idx ->
                                val tier = when (idx) {
                                    0 -> SecurityTier.STANDARD
                                    1 -> SecurityTier.HIGH
                                    else -> SecurityTier.STRICT
                                }
                                viewModel.setSecurityTier(tier)
                            }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        val tierDesc = when (state.selectedTier) {
                            SecurityTier.STANDARD -> LocalizationManager.get(StringKey.TIER_STANDARD_DESC)
                            SecurityTier.HIGH -> LocalizationManager.get(StringKey.TIER_HIGH_DESC)
                            SecurityTier.STRICT -> LocalizationManager.get(StringKey.TIER_STRICT_DESC)
                        }

                        Text(
                            text = tierDesc,
                            color = omniTextMuted(isDark),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // 2. 2FA QR Code Verification Mode (Hardware Barcode Engine)
            item {
                IOSCard(cornerRadius = 20.dp) {
                    SettingRow(
                        title = LocalizationManager.get(StringKey.TWO_FACTOR_QR),
                        subtitle = if (state.isTwoFactorEnabled) {
                            "${LocalizationManager.get(StringKey.TWO_FACTOR_QR_DESC)} • Hardware Barcode Active"
                        } else {
                            "QR Code Scanning Disabled • 1-Factor Face Mode"
                        },
                        icon = Icons.Default.QrCodeScanner,
                        trailing = {
                            CupertinoSwitch(
                                checked = state.isTwoFactorEnabled,
                                onCheckedChange = { isEnabled -> viewModel.toggleTwoFactorMode(isEnabled) }
                            )
                        }
                    )
                }
            }

            // 3. Hardware KeyStore & StrongBox Status
            item {
                IOSCard(cornerRadius = 20.dp) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF8B5CF6).copy(alpha = if (isDark) 0.22f else 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = "KeyStore",
                                    tint = Color(0xFF8B5CF6),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = LocalizationManager.get(StringKey.KEYSTORE_SECURITY),
                                    color = omniTextPrimary(isDark),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (state.isStrongBoxActive) LocalizationManager.get(StringKey.STRONGBOX_ACTIVE) else LocalizationManager.get(StringKey.TEE_ACTIVE),
                                    color = omniTextMuted(isDark),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = LocalizationManager.get(StringKey.KEYSTORE_EXPLANATION),
                            color = omniTextMuted(isDark),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // 4. Hardware Diagnostics (Expandable drawer for IT & system audits)
            item {
                var isExpanded by remember { mutableStateOf(false) }
                val npuInfo = state.npuHardwareInfo

                // Sanitize chipset: remove verbose "Qualcomm Technologies, Inc." prefix if followed by Snapdragon
                val rawSoc = "${npuInfo.socManufacturer} ${npuInfo.socModel}".trim()
                val cleanSoc = when {
                    rawSoc.contains("Snapdragon", ignoreCase = true) -> {
                        val index = rawSoc.indexOf("Snapdragon", ignoreCase = true)
                        rawSoc.substring(index).trim()
                    }
                    rawSoc.isNotBlank() -> rawSoc
                    else -> "Snapdragon 8s Gen 3"
                }

                val cleanNpu = when {
                    npuInfo.npuName.contains("Hexagon", ignoreCase = true) -> "Qualcomm Hexagon NPU"
                    npuInfo.npuName.isNotBlank() -> npuInfo.npuName
                    else -> "Qualcomm Hexagon NPU"
                }

                IOSCard(cornerRadius = 20.dp) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isExpanded = !isExpanded }
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeveloperBoard,
                                    contentDescription = null,
                                    tint = omniTextMuted(isDark),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Hardware Diagnostics",
                                        color = omniTextPrimary(isDark),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (isExpanded) "Tap to collapse system telemetry" else "Tap to inspect silicon acceleration details",
                                        color = omniTextMuted(isDark),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = omniTextMuted(isDark)
                            )
                        }

                        AnimatedVisibility(visible = isExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                HorizontalDivider(color = if (isDark) Color(0x1AFFFFFF) else Color(0x1A000000))

                                DiagnosticRow(
                                    label = "Chipset / SoC",
                                    value = cleanSoc,
                                    isDark = isDark
                                )

                                DiagnosticRow(
                                    label = "Neural Accelerator",
                                    value = cleanNpu,
                                    isDark = isDark,
                                    valueColor = Color(0xFF38BDF8)
                                )

                                DiagnosticRow(
                                    label = "Acceleration Tier",
                                    value = state.hardwareTier,
                                    isDark = isDark
                                )

                                DiagnosticRow(
                                    label = "Inference Latency",
                                    value = "${state.latencyMs} ms",
                                    isDark = isDark,
                                    valueColor = Color(0xFF34C759),
                                    isBold = true
                                )

                                DiagnosticRow(
                                    label = "Peak AI Compute",
                                    value = npuInfo.peakTops.ifEmpty { "45 TOPS" },
                                    isDark = isDark
                                )

                                if (npuInfo.supportedPrecisions.isNotEmpty()) {
                                    DiagnosticRow(
                                        label = "Precision Formats",
                                        value = npuInfo.supportedPrecisions.joinToString(", "),
                                        isDark = isDark
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    isDark: Boolean,
    valueColor: Color? = null,
    isBold: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = omniTextMuted(isDark),
            fontSize = 11.5.sp,
            modifier = Modifier.width(135.dp)
        )
        Text(
            text = value,
            color = valueColor ?: omniTextPrimary(isDark),
            fontSize = 11.5.sp,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}


