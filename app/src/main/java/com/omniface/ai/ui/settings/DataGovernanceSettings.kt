package com.omniface.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
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
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey
import com.omniface.ai.security.findFragmentActivity
import com.omniface.ai.ui.components.*
import com.omniface.ai.ui.dedup.BiometricDeduplicationStudio
import com.omniface.ai.ui.theme.*

@Composable
fun DataGovernanceSettingsSubScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val isDark = LocalThemeIsDark.current
    val context = LocalContext.current
    var showDedupStudio by remember { mutableStateOf(false) }
    var showPrivacyPolicyDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = showDedupStudio) {
        showDedupStudio = false
    }

    if (showDedupStudio) {
        BiometricDeduplicationStudio(
            onClose = { showDedupStudio = false }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(omniBackgroundBrush(isDark))
    ) {
        SettingsSubScreenHeader(
            title = LocalizationManager.get(StringKey.SETTINGS_DATA_GOVERNANCE_TITLE),
            subtitle = LocalizationManager.get(StringKey.SETTINGS_DATA_GOVERNANCE_SUBTITLE),
            onBack = onBack
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 0. Privacy Policy & Google Play Data Safety Disclosures
            item {
                IOSCard(cornerRadius = 20.dp) {
                    SettingRow(
                        title = "Privacy Policy & Data Safety",
                        subtitle = "Zero-cloud biometrics & DPDP Act 2023 compliance",
                        icon = Icons.Default.PrivacyTip,
                        trailing = {
                            CupertinoButton(
                                text = "View Policy",
                                icon = Icons.Default.Description,
                                modifier = Modifier.widthIn(min = 90.dp, max = 130.dp),
                                onClick = { showPrivacyPolicyDialog = true }
                            )
                        }
                    )
                }
            }

            // 1. Biometric Collision & Duplicate Studio
            item {
                IOSCard(cornerRadius = 20.dp) {
                    SettingRow(
                        title = LocalizationManager.get(StringKey.DEDUP_STUDIO_TITLE),
                        subtitle = LocalizationManager.get(StringKey.DEDUP_STUDIO_SUBTITLE),
                        icon = Icons.Default.FindReplace,
                        trailing = {
                            CupertinoButton(
                                text = LocalizationManager.get(StringKey.SCAN_DUPLICATES),
                                icon = Icons.Default.Search,
                                modifier = Modifier.widthIn(min = 90.dp, max = 130.dp),
                                onClick = { showDedupStudio = true }
                            )
                        }
                    )
                }
            }

            // 2. Cloud Sync Consent & Dispatch
            item {
                IOSCard(cornerRadius = 20.dp) {
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
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF38BDF8).copy(alpha = if (isDark) 0.22f else 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudSync,
                                    contentDescription = "Sync",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = LocalizationManager.get(StringKey.CLOUD_SYNC_INTEGRATION),
                                    color = omniTextPrimary(isDark),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (state.isCloudSyncEnabled) LocalizationManager.get(StringKey.CLOUD_SYNC_ENABLED) else LocalizationManager.get(StringKey.OFFLINE_FIRST_MODE),
                                    color = if (state.isCloudSyncEnabled) Color(0xFF34C759) else omniTextMuted(isDark),
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        IOSGlassPill(
                            text = LocalizationManager.get(StringKey.COMING_SOON),
                            accentColor = Color(0xFF38BDF8)
                        )
                    }
                }
            }

            // 3. AES-256 Encrypted Local Backup
            item {
                IOSCard(cornerRadius = 20.dp) {
                    SettingRow(
                        title = LocalizationManager.get(StringKey.ENCRYPTED_BACKUP_TITLE),
                        subtitle = LocalizationManager.get(StringKey.ENCRYPTED_BACKUP_DESC),
                        icon = Icons.Default.SaveAlt,
                        trailing = {
                            CupertinoButton(
                                text = if (state.isBackingUp) LocalizationManager.get(StringKey.TESTING) else LocalizationManager.get(StringKey.BACKUP_ACTION),
                                icon = Icons.Default.Backup,
                                modifier = Modifier.widthIn(min = 90.dp, max = 120.dp),
                                onClick = {
                                    val act = context.findFragmentActivity()
                                    if (act != null) {
                                        com.omniface.ai.security.DeviceBiometricAuthManager.authenticate(
                                            activity = act,
                                            title = "Authorize Database Backup",
                                            subtitle = "Authenticate to export encrypted AES-256 database snapshot",
                                            onSuccess = { viewModel.backupDatabaseEncrypted(context) }
                                        )
                                    } else {
                                        viewModel.backupDatabaseEncrypted(context)
                                    }
                                }
                            )
                        }
                    )
                }
            }

            // 4. Export ISO/IEC Compliance Evidence Report
            item {
                IOSCard(cornerRadius = 20.dp) {
                    SettingRow(
                        title = LocalizationManager.get(StringKey.COMPLIANCE_REPORT_TITLE),
                        subtitle = LocalizationManager.get(StringKey.COMPLIANCE_REPORT_DESC),
                        icon = Icons.Default.Description,
                        trailing = {
                            CupertinoButton(
                                text = if (state.isGeneratingReport) LocalizationManager.get(StringKey.TESTING) else LocalizationManager.get(StringKey.EXPORT_ACTION),
                                icon = Icons.Default.Share,
                                modifier = Modifier.widthIn(min = 90.dp, max = 120.dp),
                                onClick = {
                                    val act = context.findFragmentActivity()
                                    if (act != null) {
                                        com.omniface.ai.security.DeviceBiometricAuthManager.authenticate(
                                            activity = act,
                                            title = "Authorize Compliance Export",
                                            subtitle = "Authenticate to export signed audit evidence report",
                                            onSuccess = { viewModel.exportComplianceReport(context) }
                                        )
                                    } else {
                                        viewModel.exportComplianceReport(context)
                                    }
                                }
                            )
                        }
                    )
                }
            }

            // 5. DPDP Act 2023 90-Day Retention Purge
            item {
                IOSCard(cornerRadius = 20.dp) {
                    SettingRow(
                        title = LocalizationManager.get(StringKey.DPDP_RETENTION_TITLE),
                        subtitle = LocalizationManager.get(StringKey.DPDP_RETENTION_DESC),
                        icon = Icons.Default.AutoDelete,
                        trailing = {
                            CupertinoButton(
                                text = LocalizationManager.get(StringKey.PURGE_ACTION),
                                icon = Icons.Default.DeleteSweep,
                                modifier = Modifier.widthIn(min = 90.dp, max = 120.dp),
                                onClick = {
                                    val act = context.findFragmentActivity()
                                    if (act != null) {
                                        com.omniface.ai.security.DeviceBiometricAuthManager.authenticate(
                                            activity = act,
                                            title = "Authorize DPDP Retention Purge",
                                            subtitle = "Scan fingerprint or screen lock to purge records older than 90 days",
                                            onSuccess = { viewModel.purgeOldRetentionRecords(context) }
                                        )
                                    } else {
                                        viewModel.purgeOldRetentionRecords(context)
                                    }
                                }
                            )
                        }
                    )
                }
            }

            // 6. Complete Biometric Ledger Wipe
            item {
                IOSCard(cornerRadius = 20.dp) {
                    SettingRow(
                        title = LocalizationManager.get(StringKey.WIPE_LEDGER_TITLE),
                        subtitle = LocalizationManager.get(StringKey.WIPE_LEDGER_DESC),
                        icon = Icons.Default.DeleteForever,
                        trailing = {
                            CupertinoButton(
                                text = if (state.isPurging) LocalizationManager.get(StringKey.TESTING) else LocalizationManager.get(StringKey.WIPE_ALL_ACTION),
                                icon = Icons.Default.Delete,
                                modifier = Modifier.widthIn(min = 90.dp, max = 120.dp),
                                onClick = {
                                    val act = context.findFragmentActivity()
                                    if (act != null) {
                                        com.omniface.ai.security.DeviceBiometricAuthManager.authenticate(
                                            activity = act,
                                            title = "Authorize Biometric Ledger Wipe",
                                            subtitle = "Scan fingerprint or screen lock to permanently delete all records",
                                            onSuccess = { viewModel.showPurgeDialog(true) }
                                        )
                                    } else {
                                        viewModel.showPurgeDialog(true)
                                    }
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    if (showPrivacyPolicyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyPolicyDialog = false },
            containerColor = if (isDark) Color(0xFF0F172A) else Color(0xFFFFFFFF),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Privacy",
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Biometric Privacy & Data Safety",
                        color = omniTextPrimary(isDark),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            text = "Core Guarantee: 100% On-Device Processing",
                            color = Color(0xFF10B981),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "OmniFace AI processes all facial recognition locally on your hardware NPU/GPU/CPU. Raw camera frames are processed ephemerally in RAM and are never stored in gallery or transmitted to external servers.",
                            color = omniTextSecondary(isDark),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                    item {
                        Text(
                            text = "Data Security & Encryption",
                            color = omniCyan(isDark),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Biometric templates are stored as 512-D mathematical vectors encrypted with AES-256-GCM using hardware keys sealed inside the AndroidKeyStore. Database backups are protected and USB adb extraction is prohibited.",
                            color = omniTextSecondary(isDark),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                    item {
                        Text(
                            text = "Global Compliance & Right-to-Forget",
                            color = Color(0xFFEC4899),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Fully compliant with Google Play Biometric Data Policies, India DPDP Act 2023, GDPR, and FERPA. Users and administrators possess the absolute right to erasure, 90-day retention auto-purge, and one-tap cryptographic ledger wipes.",
                            color = omniTextSecondary(isDark),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyPolicyDialog = false }) {
                    Text(LocalizationManager.get(StringKey.CONFIRM_ACTION), color = omniCyan(isDark), fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}
