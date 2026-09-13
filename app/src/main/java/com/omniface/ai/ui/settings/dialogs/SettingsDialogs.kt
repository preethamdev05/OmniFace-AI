package com.omniface.ai.ui.settings.dialogs

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniface.ai.hardware.DeviceCapacityProfile
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey
import com.omniface.ai.ui.settings.SettingsUiState
import com.omniface.ai.ui.settings.SettingsViewModel
import com.omniface.ai.ui.settings.HardwareInfoRow
import com.omniface.ai.ui.theme.*

@Composable
fun SettingsMasterDialogs(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
    isDark: Boolean,
    showHardwareInfoDialog: Boolean,
    onDismissHardwareInfo: () -> Unit,
    deviceCapacity: DeviceCapacityProfile
) {
    // ── Master Modals & Alert Dialogs ──

    // Diagnostics Self-Test Modal
    if (state.showSelfTestModal && state.selfTestReport != null) {
        val report = state.selfTestReport!!
        AlertDialog(
            onDismissRequest = { viewModel.dismissSelfTestModal() },
            containerColor = if (isDark) Color(0xFF0F172A) else Color(0xFFFFFFFF),
            icon = {
                Icon(
                    imageVector = if (report.overallPassed) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (report.overallPassed) Color(0xFF34C759) else Color(0xFFFF3B30),
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = if (report.overallPassed) "✅ ${LocalizationManager.get(StringKey.KIOSK_SELF_TEST)}" else "⚠️ ${LocalizationManager.get(StringKey.KIOSK_SELF_TEST_DESC)}",
                    color = omniTextPrimary(isDark),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    report.items.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isDark) Color(0x261E293B) else Color(0xFFF8FAFC))
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, color = omniTextPrimary(isDark), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(item.detail, color = omniTextSecondary(isDark), fontSize = 10.sp)
                            }
                            Text(
                                text = if (item.isPassed) "✅ ${item.latencyMs}ms" else "❌ FAIL",
                                color = if (item.isPassed) Color(0xFF34C759) else Color(0xFFFF3B30),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissSelfTestModal() }) {
                    Text(LocalizationManager.get(StringKey.CLOSE_ACTION), color = omniCyan(isDark), fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Cloud Consent Dialog
    if (state.showCloudConsentDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showCloudConsentDialog(false) },
            containerColor = if (isDark) Color(0xFF0F172A) else Color(0xFFFFFFFF),
            icon = {
                Icon(Icons.Default.CloudSync, contentDescription = null, tint = omniCyan(isDark), modifier = Modifier.size(32.dp))
            },
            title = {
                Text("Cloud Synchronization Disclosure", color = omniTextPrimary(isDark), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "In compliance with Google Play Data Safety policies, please review the data collected during cloud synchronization:",
                        color = omniTextSecondary(isDark),
                        fontSize = 12.sp
                    )
                    Text(
                        "• Data Transmitted: Student Name, Roll Number, Session Timestamp, Confidence Score, and Aegis SHA-256 Hash.\n" +
                        "• Biometric Privacy: Facial images and raw biometric embeddings remain 100% on-device and are NEVER transmitted.\n" +
                        "• Security: TLS 1.3 encryption in transit.",
                        color = omniTextPrimary(isDark),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.setCloudSyncConsent(true) }
                ) {
                    Text(LocalizationManager.get(StringKey.CONFIRM_ACTION), color = omniCyan(isDark), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showCloudConsentDialog(false) }) {
                    Text(LocalizationManager.get(StringKey.CANCEL_ACTION), color = omniTextSecondary(isDark))
                }
            }
        )
    }

    // DPDP Purge Confirmation Dialog
    if (state.showPurgeConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showPurgeDialog(false) },
            containerColor = if (isDark) Color(0xFF0F172A) else Color(0xFFFFFFFF),
            title = {
                Text(LocalizationManager.get(StringKey.DPDP_RETENTION_TITLE), color = Color(0xFFFF3B30), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    LocalizationManager.get(StringKey.DPDP_RETENTION_DESC),
                    color = omniTextSecondary(isDark),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.purgeAttendanceLedger(context) }
                ) {
                    Text(LocalizationManager.get(StringKey.WIPE_ALL_ACTION), color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showPurgeDialog(false) }) {
                    Text(LocalizationManager.get(StringKey.CANCEL_ACTION), color = omniTextPrimary(isDark))
                }
            }
        )
    }



    // Fleet Topology Dialog
    if (state.showFleetModal) {
        AlertDialog(
            onDismissRequest = { viewModel.toggleFleetModal(false) },
            containerColor = if (isDark) Color(0xFF0F172A) else Color(0xFFFFFFFF),
            icon = {
                Icon(Icons.Default.Hub, contentDescription = null, tint = Color(0xFF007AFF), modifier = Modifier.size(32.dp))
            },
            title = {
                Text(LocalizationManager.get(StringKey.BLE_FLEET_MESH), color = omniTextPrimary(isDark), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        LocalizationManager.get(StringKey.BLE_FLEET_MESH_DESC),
                        color = omniTextSecondary(isDark),
                        fontSize = 12.sp
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDark) Color(0x261E293B) else Color(0xFFF8FAFC))
                            .padding(10.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("• Local Node: ACTIVE (Master Ledger)", color = Color(0xFF34C759), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            Text("• Bluetooth Channel: BLE Mesh v5.3 GCM", color = omniTextMuted(isDark), fontSize = 11.sp)
                            Text("• Sync Latency: < 200ms per transaction", color = omniTextMuted(isDark), fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.toggleFleetModal(false) }) {
                    Text(LocalizationManager.get(StringKey.CLOSE_ACTION), color = omniCyan(isDark), fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Hardware Acceleration & Silicon Architecture Info Dialog
    if (showHardwareInfoDialog) {
        val rawSoc = state.npuHardwareInfo.socModel
        val cleanSoc = state.npuHardwareInfo.socModel.ifBlank { "Generic ARM Silicon" }
        val cleanNpu = state.npuHardwareInfo.npuName.ifBlank { "Neural Engine" }

        AlertDialog(
            onDismissRequest = { onDismissHardwareInfo() },
            containerColor = if (isDark) Color(0xFF0F172A) else Color(0xFFFFFFFF),
            shape = RoundedCornerShape(24.dp),
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF38BDF8).copy(alpha = if (isDark) 0.2f else 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(26.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "Hardware Acceleration",
                    color = omniTextPrimary(isDark),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HardwareInfoRow(label = "Processor", value = cleanSoc, isDark = isDark)
                    HardwareInfoRow(label = "Neural Engine", value = cleanNpu, isDark = isDark)
                    HardwareInfoRow(label = "Architecture", value = "Hexagon Vector HTP", isDark = isDark)
                    HardwareInfoRow(label = "Peak Compute", value = "${state.npuHardwareInfo.peakTops} (INT8)", isDark = isDark)
                    HardwareInfoRow(label = "Unified Memory", value = "${"%.1f".format(deviceCapacity.totalRamGb)} GB System RAM", isDark = isDark)
                    HardwareInfoRow(label = "Inference Latency", value = "${state.latencyMs} ms direct dispatch", isDark = isDark)

                    Spacer(modifier = Modifier.height(4.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isDark) Color(0x1A10B981) else Color(0x1210B981))
                            .padding(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "100% On-Device execution. Biometric data and face embeddings never leave local hardware silicon.",
                                color = if (isDark) Color(0xFF6EE7B7) else Color(0xFF047857),
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onDismissHardwareInfo() },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = LocalizationManager.get(StringKey.CLOSE_ACTION),
                        color = omniCyan(isDark),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )
    }
}
