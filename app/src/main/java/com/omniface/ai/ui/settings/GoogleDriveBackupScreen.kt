package com.omniface.ai.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.omniface.ai.sync.GoogleDriveAppDataService
import com.omniface.ai.sync.GoogleDriveBackupWorker
import com.omniface.ai.sync.UserDriveBackupManager
import com.omniface.ai.ui.components.IOSCard
import com.omniface.ai.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleDriveBackupScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val isDark = LocalThemeIsDark.current

    val prefs = remember { context.getSharedPreferences("OMNIFACE_DRIVE_BACKUP", Context.MODE_PRIVATE) }

    var connectedEmail by remember { mutableStateOf(prefs.getString("CONNECTED_GOOGLE_ACCOUNT", "") ?: "") }
    var lastBackupTime by remember { mutableStateOf(prefs.getLong("LAST_BACKUP_TIME", 0L)) }
    var lastBackupSize by remember { mutableStateOf(prefs.getLong("LAST_BACKUP_SIZE", 0L)) }
    var backupPin by remember { mutableStateOf(prefs.getString("BACKUP_ENCRYPTION_PIN", "123456") ?: "123456") }
    var backupFrequency by remember { mutableStateOf(prefs.getString("BACKUP_FREQUENCY", "DAILY") ?: "DAILY") }
    var wifiOnly by remember { mutableStateOf(prefs.getBoolean("WIFI_ONLY", true)) }

    var isBackingUp by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var backupProgressMessage by remember { mutableStateOf("") }
    var showPinDialog by remember { mutableStateOf(false) }
    var showRestorePinDialog by remember { mutableStateOf(false) }
    var tempPinInput by remember { mutableStateOf("") }

    // Google Sign-In Launcher configured for user's personal Google Drive appDataFolder
    val driveScope = Scope("https://www.googleapis.com/auth/drive.appdata")
    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(driveScope)
            .build()
    }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.result
                val email = account.email ?: ""
                connectedEmail = email
                prefs.edit().putString("CONNECTED_GOOGLE_ACCOUNT", email).apply()
                Toast.makeText(context, "Connected Google Account: $email", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Sign-in failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun startBackup() {
        if (connectedEmail.isBlank()) {
            val client = GoogleSignIn.getClient(context, gso)
            signInLauncher.launch(client.signInIntent)
            return
        }

        coroutineScope.launch {
            isBackingUp = true
            backupProgressMessage = "Creating end-to-end encrypted kiosk snapshot..."
            try {
                val (encryptedBytes, meta) = UserDriveBackupManager.createEncryptedBackupStream(backupPin)

                backupProgressMessage = "Acquiring secure Google Drive auth token..."
                val token = withContext(Dispatchers.IO) {
                    try {
                        val account = android.accounts.Account(connectedEmail, "com.google")
                        GoogleAuthUtil.getToken(
                            context,
                            account,
                            "oauth2:https://www.googleapis.com/auth/drive.appdata"
                        )
                    } catch (e: Exception) {
                        // If token needs direct prompt or user authentication
                        null
                    }
                }

                if (token == null) {
                    // Fallback to GoogleSignIn client to re-prompt account
                    val client = GoogleSignIn.getClient(context, gso)
                    signInLauncher.launch(client.signInIntent)
                    isBackingUp = false
                    return@launch
                }

                backupProgressMessage = "Uploading to your Google Drive appDataFolder..."
                val result = GoogleDriveAppDataService.uploadBackup(token, encryptedBytes)

                if (result.isSuccess) {
                    val now = System.currentTimeMillis()
                    lastBackupTime = now
                    lastBackupSize = encryptedBytes.size.toLong()

                    prefs.edit()
                        .putLong("LAST_BACKUP_TIME", now)
                        .putLong("LAST_BACKUP_SIZE", lastBackupSize)
                        .apply()

                    Toast.makeText(context, "✅ Backup completed (${meta.studentCount} students, ${meta.attendanceRecordCount} records)", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Backup failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Backup error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isBackingUp = false
            }
        }
    }

    fun startRestore(pin: String) {
        if (connectedEmail.isBlank()) {
            Toast.makeText(context, "Please connect your Google Account first.", Toast.LENGTH_SHORT).show()
            return
        }

        coroutineScope.launch {
            isRestoring = true
            backupProgressMessage = "Connecting to Google Drive..."
            try {
                val token = withContext(Dispatchers.IO) {
                    try {
                        val account = android.accounts.Account(connectedEmail, "com.google")
                        GoogleAuthUtil.getToken(
                            context,
                            account,
                            "oauth2:https://www.googleapis.com/auth/drive.appdata"
                        )
                    } catch (e: Exception) {
                        null
                    }
                }

                if (token == null) {
                    val client = GoogleSignIn.getClient(context, gso)
                    signInLauncher.launch(client.signInIntent)
                    isRestoring = false
                    return@launch
                }

                backupProgressMessage = "Searching for latest backup archive..."
                val files = GoogleDriveAppDataService.listBackups(token).getOrNull()
                if (files.isNullOrEmpty()) {
                    Toast.makeText(context, "No existing backups found in your Google Drive.", Toast.LENGTH_LONG).show()
                    isRestoring = false
                    return@launch
                }

                val latest = files.first()
                backupProgressMessage = "Downloading encrypted backup (${latest.sizeBytes / 1024} KB)..."
                val downloadResult = GoogleDriveAppDataService.downloadBackup(token, latest.id)

                if (downloadResult.isFailure) {
                    Toast.makeText(context, "Download failed: ${downloadResult.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    isRestoring = false
                    return@launch
                }

                backupProgressMessage = "Decrypting archive and restoring database..."
                val restoreResult = UserDriveBackupManager.restoreEncryptedBackup(
                    encryptedBytes = downloadResult.getOrThrow(),
                    pin = pin,
                    context = context
                )

                if (restoreResult.isSuccess) {
                    val meta = restoreResult.getOrThrow()
                    Toast.makeText(context, "🎉 Successfully restored ${meta.studentCount} students and ${meta.attendanceRecordCount} attendance logs!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "❌ Decryption failed: Incorrect PIN or corrupt archive.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Restore error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isRestoring = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Google Drive Backup", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = omniTextPrimary(isDark)
                )
            )
        },
        containerColor = omniBackground(isDark)
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── HERO BACKUP STATUS CARD (WhatsApp Style) ──
            item {
                IOSCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(omniEmerald(isDark).copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.CloudUpload,
                                    contentDescription = null,
                                    tint = omniEmerald(isDark),
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = "Back up to Google Drive",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = omniTextPrimary(isDark)
                                )
                                Text(
                                    text = "Owned 100% by you • Stored in your Google Drive",
                                    fontSize = 12.sp,
                                    color = omniTextSecondary(isDark)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        val formattedDate = if (lastBackupTime > 0L) {
                            SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date(lastBackupTime))
                        } else "Never"

                        val formattedSize = if (lastBackupSize > 0L) {
                            "${"%.1f".format(lastBackupSize / (1024.0 * 1024.0))} MB"
                        } else "0 MB"

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isDark) Color(0x33000000) else Color(0x0A000000))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Last Backup:", fontSize = 12.5.sp, color = omniTextSecondary(isDark))
                                Text(formattedDate, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = omniTextPrimary(isDark))
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Archive Size:", fontSize = 12.5.sp, color = omniTextSecondary(isDark))
                                Text(formattedSize, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = omniTextPrimary(isDark))
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Google Account:", fontSize = 12.5.sp, color = omniTextSecondary(isDark))
                                Text(if (connectedEmail.isNotBlank()) connectedEmail else "Not Connected", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = if (connectedEmail.isNotBlank()) omniCyan(isDark) else Color(0xFFFF9500))
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("End-to-End Encryption:", fontSize = 12.5.sp, color = omniTextSecondary(isDark))
                                Text("AES-256 (Protected by PIN)", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = omniEmerald(isDark))
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Backup Action Button
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                startBackup()
                            },
                            enabled = !isBackingUp && !isRestoring,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = omniEmerald(isDark)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isBackingUp) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(backupProgressMessage.take(28), fontSize = 14.sp)
                            } else {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("BACK UP NOW", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            // ── END-TO-END ENCRYPTION & PIN SETTINGS ──
            item {
                IOSCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = omniEmerald(isDark), modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("End-to-End Encryption Key", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = omniTextPrimary(isDark))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Your backup is encrypted with your custom PIN using PBKDF2-HMAC-SHA256 and AES-256-GCM before upload. Neither Google nor OmniFace can read your data.",
                            fontSize = 12.sp,
                            color = omniTextSecondary(isDark)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                tempPinInput = backupPin
                                showPinDialog = true
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Change Encryption PIN (Currently: ${backupPin.length} digits)", fontSize = 13.sp)
                        }
                    }
                }
            }

            // ── GOOGLE DRIVE SETTINGS (FREQUENCY & WI-FI) ──
            item {
                IOSCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Google Drive Settings", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = omniTextPrimary(isDark))

                        // Connected Account Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val client = GoogleSignIn.getClient(context, gso)
                                    signInLauncher.launch(client.signInIntent)
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Google Account", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = omniTextPrimary(isDark))
                                Text(if (connectedEmail.isNotBlank()) connectedEmail else "Tap to connect", fontSize = 12.sp, color = omniTextSecondary(isDark))
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = omniTextSecondary(isDark))
                        }

                        HorizontalDivider(color = if (isDark) Color(0x22FFFFFF) else Color(0x11000000))

                        // Backup Frequency
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Auto Backup Frequency", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = omniTextPrimary(isDark))
                                Text("Schedule automated background backups", fontSize = 12.sp, color = omniTextSecondary(isDark))
                            }
                            TextButton(onClick = {
                                val nextFreq = when (backupFrequency) {
                                    "OFF" -> "DAILY"
                                    "DAILY" -> "WEEKLY"
                                    else -> "OFF"
                                }
                                backupFrequency = nextFreq
                                prefs.edit().putString("BACKUP_FREQUENCY", nextFreq).putBoolean("AUTO_BACKUP_ENABLED", nextFreq != "OFF").apply()
                                GoogleDriveBackupWorker.schedulePeriodicBackup(context, nextFreq, wifiOnly)
                            }) {
                                Text(backupFrequency, fontWeight = FontWeight.Bold, color = omniCyan(isDark))
                            }
                        }

                        HorizontalDivider(color = if (isDark) Color(0x22FFFFFF) else Color(0x11000000))

                        // Wi-Fi Only Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Back up over Wi-Fi only", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = omniTextPrimary(isDark))
                                Text("Prevent uploads on cellular mobile data", fontSize = 12.sp, color = omniTextSecondary(isDark))
                            }
                            Switch(
                                checked = wifiOnly,
                                onCheckedChange = {
                                    wifiOnly = it
                                    prefs.edit().putBoolean("WIFI_ONLY", it).apply()
                                    GoogleDriveBackupWorker.schedulePeriodicBackup(context, backupFrequency, it)
                                }
                            )
                        }
                    }
                }
            }

            // ── RESTORE BACKUP CARD ──
            item {
                IOSCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, tint = omniCyan(isDark), modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Restore from Google Drive", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = omniTextPrimary(isDark))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Reinstalls or new devices can restore students, 512-D vector models, and attendance history with your encryption PIN.",
                            fontSize = 12.sp,
                            color = omniTextSecondary(isDark)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                tempPinInput = ""
                                showRestorePinDialog = true
                            },
                            enabled = !isBackingUp && !isRestoring,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = omniCyan(isDark)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isRestoring) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(backupProgressMessage.take(28), fontSize = 14.sp)
                            } else {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("RESTORE FROM DRIVE", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }

    // ── PIN CHANGE MODAL ──
    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text("Set Backup Encryption PIN") },
            text = {
                Column {
                    Text("Enter a 6+ digit PIN to protect your backup. Keep it safe — without this PIN, your backup cannot be restored on another device.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tempPinInput,
                        onValueChange = { tempPinInput = it },
                        label = { Text("PIN / Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempPinInput.length >= 4) {
                            backupPin = tempPinInput
                            prefs.edit().putString("BACKUP_ENCRYPTION_PIN", tempPinInput).apply()
                            showPinDialog = false
                            Toast.makeText(context, "Encryption PIN updated.", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "PIN must be at least 4 digits.", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Save PIN")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ── RESTORE PIN PROMPT MODAL ──
    if (showRestorePinDialog) {
        AlertDialog(
            onDismissRequest = { showRestorePinDialog = false },
            title = { Text("Enter Backup Encryption PIN") },
            text = {
                Column {
                    Text("Enter the encryption PIN used when the backup was created to decrypt your biometric templates and attendance history.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tempPinInput,
                        onValueChange = { tempPinInput = it },
                        label = { Text("Backup PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempPinInput.isNotBlank()) {
                            showRestorePinDialog = false
                            startRestore(tempPinInput)
                        } else {
                            Toast.makeText(context, "Please enter your PIN.", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Decrypt & Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestorePinDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
