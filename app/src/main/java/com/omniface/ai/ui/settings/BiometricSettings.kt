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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey
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
                                        .clip(RoundedCornerShape(10.dp))
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

            // 2. 2FA QR Code Verification Mode (Marked Coming Soon)
            item {
                IOSCard(cornerRadius = 20.dp) {
                    SettingRow(
                        title = LocalizationManager.get(StringKey.TWO_FACTOR_QR),
                        subtitle = "${LocalizationManager.get(StringKey.TWO_FACTOR_QR_DESC)} • Hardware Barcode Engine",
                        icon = Icons.Default.QrCodeScanner,
                        trailing = {
                            IOSGlassPill(
                                text = "Coming Soon",
                                accentColor = Color(0xFFF59E0B)
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
                                    .clip(RoundedCornerShape(10.dp))
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
                            Column {
                                Text(
                                    text = LocalizationManager.get(StringKey.KEYSTORE_SECURITY),
                                    color = omniTextPrimary(isDark),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (state.isStrongBoxActive) LocalizationManager.get(StringKey.STRONGBOX_ACTIVE) else LocalizationManager.get(StringKey.TEE_ACTIVE),
                                    color = omniTextMuted(isDark),
                                    fontSize = 12.sp
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

            // 4. Aegis SHA-256 Merkle Root Batch Proof
            item {
                IOSCard(cornerRadius = 20.dp) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = LocalizationManager.get(StringKey.MERKLE_ROOT_PROOF),
                                color = omniTextPrimary(isDark),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            IOSGlassPill(
                                text = "SHA-256",
                                accentColor = Color(0xFF007AFF)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = state.merkleRoot.ifBlank { "0x0000000000000000000000000000000000000000000000000000000000000000" },
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }
}

