@file:Suppress("DEPRECATION")

package com.omniface.ai.ui.settings

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniface.ai.hardware.KioskLockController
import com.omniface.ai.hardware.TurnstileRelayController
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey
import com.omniface.ai.security.findFragmentActivity
import com.omniface.ai.ui.components.*
import com.omniface.ai.ui.theme.*

@Composable
fun KioskAccessSettingsSubScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val isDark = LocalThemeIsDark.current
    val context = LocalContext.current
    val activity = context as? Activity
    var showPinDialog by remember { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = showPinDialog) {
        showPinDialog = false
        enteredPin = ""
        pinError = null
    }

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = {
                showPinDialog = false
                enteredPin = ""
                pinError = null
            },
            containerColor = if (isDark) Color(0xFF1E293B) else Color(0xFFFFFFFF),
            shape = RoundedCornerShape(20.dp),
            icon = {
                Icon(
                    imageVector = Icons.Default.AdminPanelSettings,
                    contentDescription = null,
                    tint = omniCyan(isDark),
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = LocalizationManager.get(StringKey.KIOSK_LOCK),
                    color = omniTextPrimary(isDark),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = LocalizationManager.get(StringKey.ADMIN_PIN),
                        color = omniTextSecondary(isDark),
                        fontSize = 13.sp
                    )

                    OutlinedTextField(
                        value = enteredPin,
                        onValueChange = {
                            if (it.length <= 12) {
                                enteredPin = it
                                pinError = null
                            }
                        },
                        placeholder = { Text(LocalizationManager.get(StringKey.ADMIN_PIN), color = omniTextMuted(isDark), fontSize = 13.sp) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = omniCyan(isDark),
                            unfocusedBorderColor = if (isDark) Color(0x38FFFFFF) else Color(0x26000000),
                            focusedTextColor = omniTextPrimary(isDark),
                            unfocusedTextColor = omniTextPrimary(isDark)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    if (pinError != null) {
                        Text(
                            text = pinError!!,
                            color = Color(0xFFEF4444),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (KioskLockController.isLockedOut()) {
                            pinError = "Too many attempts — wait for lockout to expire"
                        } else if (!KioskLockController.verifyAdminPin(context, enteredPin)) {
                            pinError = "Invalid Admin PIN"
                        } else {
                            if (activity != null) {
                                viewModel.toggleKioskLock(activity, enteredPin)
                            }
                            showPinDialog = false
                            enteredPin = ""
                            pinError = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = omniCyan(isDark)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(LocalizationManager.get(StringKey.CONFIRM_ACTION), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPinDialog = false
                    enteredPin = ""
                    pinError = null
                }) {
                    Text(LocalizationManager.get(StringKey.CANCEL_ACTION), color = omniTextMuted(isDark))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(omniBackgroundBrush(isDark))
    ) {
        SettingsSubScreenHeader(
            title = LocalizationManager.get(StringKey.CAT_KIOSK),
            subtitle = LocalizationManager.get(StringKey.CAT_KIOSK_DESC),
            onBack = onBack
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Turnstile Relay Duration
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
                                        .background(Color(0xFF007AFF).copy(alpha = if (isDark) 0.22f else 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MeetingRoom,
                                        contentDescription = "Relay",
                                        tint = Color(0xFF007AFF),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = LocalizationManager.get(StringKey.TURNSTILE_RELAY),
                                        color = omniTextPrimary(isDark),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (state.isDoorUnlocked) LocalizationManager.get(StringKey.RELAY_OPEN) else LocalizationManager.get(StringKey.RELAY_LATCHED),
                                        color = if (state.isDoorUnlocked) Color(0xFF34C759) else omniTextMuted(isDark),
                                        fontSize = 12.sp,
                                        fontWeight = if (state.isDoorUnlocked) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        CupertinoButton(
                            text = if (state.isDoorUnlocked) LocalizationManager.get(StringKey.DOOR_UNLOCKED) else LocalizationManager.get(StringKey.DOOR_PULSE_ACTION),
                            icon = Icons.Default.LockOpen,
                            onClick = {
                                val act = context.findFragmentActivity()
                                if (act != null) {
                                    com.omniface.ai.security.DeviceBiometricAuthManager.authenticate(
                                        activity = act,
                                        title = "Authorize Turnstile Door Pulse",
                                        subtitle = "Verify fingerprint or screen lock to trigger turnstile relay",
                                        onSuccess = { viewModel.triggerDoorUnlock(context) }
                                    )
                                } else {
                                    viewModel.triggerDoorUnlock(context)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 2. Kiosk Pin Lock & Screen Pinning
            item {
                IOSCard(cornerRadius = 20.dp) {
                    SettingRow(
                        title = LocalizationManager.get(StringKey.KIOSK_LOCK),
                        subtitle = if (state.isKioskLocked) "Screen Pinned • Locked with Admin PIN / Biometrics" else "Unlocked • Standard Window",
                        icon = if (state.isKioskLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        trailing = {
                            CupertinoButton(
                                text = if (state.isKioskLocked) "Unlock" else "Lock Kiosk",
                                icon = if (state.isKioskLocked) Icons.Default.KeyOff else Icons.Default.Lock,
                                modifier = Modifier.widthIn(min = 90.dp, max = 120.dp),
                                onClick = {
                                    val act = context.findFragmentActivity()
                                    if (act != null && com.omniface.ai.security.DeviceBiometricAuthManager.canAuthenticate(context)) {
                                        com.omniface.ai.security.DeviceBiometricAuthManager.authenticate(
                                            activity = act,
                                            title = if (state.isKioskLocked) "Unlock Kiosk Screen" else "Lock Kiosk Screen",
                                            subtitle = "Verify fingerprint or screen lock to toggle kiosk mode",
                                            onSuccess = {
                                                viewModel.toggleKioskLock(act)
                                            },
                                            onError = { showPinDialog = true }
                                        )
                                    } else {
                                        showPinDialog = true
                                    }
                                }
                            )
                        }
                    )
                }
            }

            // 3. Kiosk Hardware Self-Test Diagnostics
            item {
                IOSCard(cornerRadius = 20.dp) {
                    SettingRow(
                        title = LocalizationManager.get(StringKey.KIOSK_SELF_TEST),
                        subtitle = LocalizationManager.get(StringKey.KIOSK_SELF_TEST_DESC),
                        icon = Icons.Default.Science,
                        trailing = {
                            CupertinoButton(
                                text = if (state.isRunningSelfTest) LocalizationManager.get(StringKey.TESTING) else LocalizationManager.get(StringKey.RUN_TEST),
                                icon = Icons.Default.PlayArrow,
                                modifier = Modifier.widthIn(min = 90.dp, max = 120.dp),
                                onClick = { viewModel.runHardwareSelfTest(context) }
                            )
                        }
                    )
                }
            }

            // 4. BLE Fleet Mesh Topology
            item {
                IOSCard(cornerRadius = 20.dp) {
                    SettingRow(
                        title = LocalizationManager.get(StringKey.BLE_FLEET_MESH),
                        subtitle = LocalizationManager.get(StringKey.BLE_FLEET_MESH_DESC),
                        icon = Icons.Default.Bluetooth,
                        trailing = {
                            IOSGlassPill(
                                text = LocalizationManager.get(StringKey.COMING_SOON),
                                accentColor = Color(0xFF007AFF)
                            )
                        }
                    )
                }
            }
        }
    }
}
