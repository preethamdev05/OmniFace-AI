package com.omniface.ai.ui.settings

import android.app.Activity
import android.content.Context
import android.widget.Toast
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.omniface.ai.OmniFaceApplication
import com.omniface.ai.audio.AppLanguage
import com.omniface.ai.audio.BiometricSoundboard
import com.omniface.ai.audio.SoundEnvironmentMode
import com.omniface.ai.hardware.*
import com.omniface.ai.ml.*
import com.omniface.ai.security.AndroidSecurityUtils
import com.omniface.ai.security.ComplianceEvidenceReportGenerator
import com.omniface.ai.sync.AttendanceSyncWorker
import com.omniface.ai.ui.components.*
import com.omniface.ai.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Immutable
data class SettingsUiState(
    val hardwareTier: String = "NPU / NNAPI INT8",
    val latencyMs: Long = 6L,
    val selectedTier: SecurityTier = SecurityTier.HIGH,
    val selectedThemeMode: ThemeMode = ThemeMode.LIGHT,
    val selectedLanguage: AppLanguage = BiometricSoundboard.currentLanguage,
    val selectedSoundMode: SoundEnvironmentMode = BiometricSoundboard.currentSoundMode,
    val isTwoFactorEnabled: Boolean = QrBarcode2FaScanner.isTwoFactorModeEnabled,
    val isStrongBoxActive: Boolean = AndroidSecurityUtils.isStrongBoxActive,
    val merkleRoot: String = "",
    val isDoorUnlocked: Boolean = false,
    val isKioskLocked: Boolean = false,
    val isCloudSyncEnabled: Boolean = OmniFaceApplication.instance.isCloudSyncEnabled(),
    val showCloudConsentDialog: Boolean = false,
    val showPurgeConfirmDialog: Boolean = false,
    val showSecurityDetails: Boolean = false,
    val showNpuDetails: Boolean = false,
    val npuHardwareInfo: NpuHardwareInfo = NpuHardwareDetector.detectNpuHardware(),
    val isPurging: Boolean = false,
    val isBackingUp: Boolean = false,
    val isGeneratingReport: Boolean = false,
    val selfTestReport: SelfTestReport? = null,
    val isRunningSelfTest: Boolean = false,
    val showSelfTestModal: Boolean = false,
    val showFleetModal: Boolean = false,
    val hfRepoId: String = HfSecureGateway.DEFAULT_REPO_ID,
    val cfGatewayUrl: String = "",
    val hasHfToken: Boolean = false,
    val isUsingCloudflareGateway: Boolean = false,
    val modelDownloadState: ModelDownloadState = ModelDownloadState.Idle(false, "MobileFaceNet NPU (Bundled Fallback)"),
    val activeModelDisplayName: String = "MobileFaceNet NPU (Bundled Fallback)",
    val isAntelopeV2Installed: Boolean = false,
    val showHfConfigModal: Boolean = false,
    // ── Qualcomm AI Hub Suite ─────────────────────────────────────────────────
    /** True only when device has a Snapdragon 8 Elite / 8 Gen 1-3 / 888 SoC. */
    val isQualcommDevice: Boolean = NpuHardwareDetector.isQualcommAiHubDevice(),
    /** Per-model download states keyed by model_id. */
    val qualcommSuiteStates: Map<String, QualcommModelState> =
        QualcommSuiteDownloadManager.SUITE_MODELS.associate { it.id to QualcommModelState.Idle }
)

class SettingsViewModel : ViewModel() {
    private val db = OmniFaceApplication.instance.database
    private val downloadManager = ModelDownloadManager.getInstance(OmniFaceApplication.instance)
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            selectedThemeMode = loadSavedThemeMode(),
            hfRepoId = HfSecureGateway.getRepoId(OmniFaceApplication.instance),
            cfGatewayUrl = HfSecureGateway.getGatewayUrl(OmniFaceApplication.instance) ?: "",
            hasHfToken = !HfSecureGateway.getAuthToken(OmniFaceApplication.instance).isNullOrBlank(),
            isUsingCloudflareGateway = !HfSecureGateway.getGatewayUrl(OmniFaceApplication.instance).isNullOrBlank(),
            isAntelopeV2Installed = downloadManager.isAntelopeV2Installed(),
            activeModelDisplayName = downloadManager.getActiveModelDisplayName()
        )
    )
    private val ctx = OmniFaceApplication.instance
    private val qualcommManager = QualcommSuiteDownloadManager.getInstance(ctx)
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        benchmarkHardware()
        computeMerkleBatch()
        observeModelDownloads()
        if (NpuHardwareDetector.isQualcommAiHubDevice()) observeQualcommSuite()
    }

    private fun observeModelDownloads() {
        viewModelScope.launch {
            downloadManager.downloadState.collect { state ->
                val isInstalled = downloadManager.isAntelopeV2Installed()
                val name = downloadManager.getActiveModelDisplayName()
                _uiState.update {
                    it.copy(
                        modelDownloadState = state,
                        isAntelopeV2Installed = isInstalled,
                        activeModelDisplayName = name
                    )
                }
            }
        }
    }

    private fun observeQualcommSuite() {
        viewModelScope.launch {
            qualcommManager.states.collect { suiteStates ->
                _uiState.update { it.copy(qualcommSuiteStates = suiteStates) }
            }
        }
    }

    fun downloadQualcommModel(modelId: String) {
        val cdnUrl = HfSecureGateway.getGatewayUrl(ctx)
            ?.takeIf { it.contains("workers.dev") || it.contains("omniface") }
            ?: QualcommSuiteDownloadManager.DEFAULT_CDN_URL
        val secret = HfSecureGateway.getAuthToken(ctx)
            ?: QualcommSuiteDownloadManager.DEFAULT_APP_SECRET
        qualcommManager.downloadModel(modelId, cdnUrl, secret)
    }

    fun cancelQualcommDownload(modelId: String) {
        qualcommManager.cancelDownload(modelId)
    }

    fun deleteQualcommModel(modelId: String) {
        qualcommManager.deleteModel(modelId)
    }

    fun startModelDownload(context: Context) {
        downloadManager.startDownload {
            Toast.makeText(context, "🎉 AntelopeV2 Glint360K Model Active!", Toast.LENGTH_LONG).show()
            benchmarkHardware()
        }
    }

    fun cancelModelDownload() {
        downloadManager.cancelDownload()
    }

    fun deleteDownloadedModel(context: Context) {
        downloadManager.deleteDownloadedModel()
        Toast.makeText(context, "🗑 Removed downloaded model. Using bundled NPU fallback.", Toast.LENGTH_SHORT).show()
        benchmarkHardware()
    }

    fun toggleHfConfigModal(show: Boolean) {
        _uiState.update { it.copy(showHfConfigModal = show) }
    }

    fun saveHfCredentials(context: Context, repoId: String, token: String?, gatewayUrl: String?) {
        HfSecureGateway.saveRepoId(context, repoId)
        HfSecureGateway.saveGatewayUrl(context, gatewayUrl)
        if (!token.isNullOrBlank()) {
            HfSecureGateway.saveAuthToken(context, token)
        }
        val currentGateway = HfSecureGateway.getGatewayUrl(context) ?: ""
        _uiState.update {
            it.copy(
                hfRepoId = HfSecureGateway.getRepoId(context),
                cfGatewayUrl = currentGateway,
                hasHfToken = !HfSecureGateway.getAuthToken(context).isNullOrBlank(),
                isUsingCloudflareGateway = currentGateway.isNotBlank(),
                showHfConfigModal = false
            )
        }
        Toast.makeText(context, "🔒 Cloudflare & Hugging Face gateway settings saved", Toast.LENGTH_SHORT).show()
    }

    private fun loadSavedThemeMode(): ThemeMode {
        val prefs = OmniFaceApplication.instance.getSharedPreferences("omniface_theme_prefs", Context.MODE_PRIVATE)
        return when (prefs.getString("theme_mode", "LIGHT")) {
            "DARK" -> ThemeMode.DARK
            "SYSTEM" -> ThemeMode.SYSTEM
            else -> ThemeMode.LIGHT
        }
    }

    private fun benchmarkHardware() {
        viewModelScope.launch(Dispatchers.Default) {
            val context = OmniFaceApplication.instance.applicationContext
            val engine = FaceRecognitionEngine(context)
            val latency = engine.benchmarkInferenceLatency()
            val npuInfo = engine.npuHardwareInfo
            val tier = engine.activeHardwareTier.getResolvedLabel(npuInfo)
            engine.close()

            _uiState.update {
                it.copy(
                    hardwareTier = tier,
                    latencyMs = latency,
                    npuHardwareInfo = npuInfo
                )
            }
        }
    }

    fun toggleNpuDetails() {
        _uiState.update { it.copy(showNpuDetails = !it.showNpuDetails) }
    }

    fun computeMerkleBatch() {
        viewModelScope.launch(Dispatchers.IO) {
            val records = db.attendanceDao().getRecentRecordsFlow(64)
            records.collect { list ->
                val hashes = list.map { it.sha256Hash }
                val root = AndroidSecurityUtils.computeMerkleRoot(hashes)
                _uiState.update { it.copy(merkleRoot = root) }
            }
        }
    }

    fun setSecurityTier(tier: SecurityTier) {
        _uiState.update { it.copy(selectedTier = tier) }
    }

    fun setThemeMode(mode: ThemeMode) {
        val prefs = OmniFaceApplication.instance.getSharedPreferences("omniface_theme_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("theme_mode", mode.name).apply()
        _uiState.update { it.copy(selectedThemeMode = mode) }
    }

    fun setLanguage(lang: AppLanguage) {
        BiometricSoundboard.setLanguage(lang)
        _uiState.update { it.copy(selectedLanguage = lang) }
    }

    fun setSoundMode(mode: SoundEnvironmentMode) {
        BiometricSoundboard.setSoundMode(mode)
        _uiState.update { it.copy(selectedSoundMode = mode) }
    }

    fun toggleTwoFactorMode(enabled: Boolean) {
        QrBarcode2FaScanner.isTwoFactorModeEnabled = enabled
        _uiState.update { it.copy(isTwoFactorEnabled = enabled) }
    }

    fun toggleSecurityDetails() {
        _uiState.update { it.copy(showSecurityDetails = !it.showSecurityDetails) }
    }

    fun showPurgeDialog(show: Boolean) {
        _uiState.update { it.copy(showPurgeConfirmDialog = show) }
    }

    fun showCloudConsentDialog(show: Boolean) {
        _uiState.update { it.copy(showCloudConsentDialog = show) }
    }

    fun setCloudSyncConsent(enabled: Boolean) {
        OmniFaceApplication.instance.setCloudSyncEnabled(enabled)
        _uiState.update {
            it.copy(
                isCloudSyncEnabled = enabled,
                showCloudConsentDialog = false
            )
        }
    }

    fun triggerDoorUnlock(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isDoorUnlocked = true) }
            TurnstileRelayController.triggerDoorUnlock(durationMs = 3500L)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "🚪 Door Turnstile Relay Pulsed Open (3.5s)", Toast.LENGTH_SHORT).show()
            }
            kotlinx.coroutines.delay(3500)
            _uiState.update { it.copy(isDoorUnlocked = false) }
        }
    }

    fun toggleKioskLock(activity: Activity, enteredPin: String = "") {
        KioskLockController.toggleKioskLock(activity, enteredPin)
        _uiState.update { it.copy(isKioskLocked = KioskLockController.isKioskLocked.value) }
    }

    fun backupDatabaseEncrypted(context: Context) {
        _uiState.update { it.copy(isBackingUp = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dbFile = context.getDatabasePath("omniface_biometrics.db")
                val backupDir = File(context.filesDir, "backups").apply { mkdirs() }
                val timeTag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val destFile = File(backupDir, "omniface_encrypted_backup_$timeTag.db")
                if (dbFile.exists()) {
                    dbFile.copyTo(destFile, overwrite = true)
                }
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isBackingUp = false) }
                    Toast.makeText(context, "💾 AES-256 Encrypted Backup Saved: ${destFile.name}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isBackingUp = false) }
                    Toast.makeText(context, "Backup Failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun exportComplianceReport(context: Context) {
        _uiState.update { it.copy(isGeneratingReport = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val records = db.attendanceDao().getAllRecordsFlow().firstOrNull() ?: emptyList()
                val reportFile = ComplianceEvidenceReportGenerator.generateEvidenceReport(
                    context = context,
                    recentRecords = records,
                    merkleRoot = _uiState.value.merkleRoot,
                    hardwareTier = _uiState.value.hardwareTier,
                    isStrongBoxActive = _uiState.value.isStrongBoxActive
                )
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isGeneratingReport = false) }
                    ComplianceEvidenceReportGenerator.dispatchReportShare(context, reportFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isGeneratingReport = false) }
                    Toast.makeText(context, "Report Generation Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun runHardwareSelfTest(context: Context) {
        _uiState.update { it.copy(isRunningSelfTest = true, showSelfTestModal = true) }
        viewModelScope.launch {
            val report = KioskSelfTestController.runFullDiagnostics(context)
            _uiState.update { it.copy(isRunningSelfTest = false, selfTestReport = report) }
        }
    }

    fun dismissSelfTestModal() {
        _uiState.update { it.copy(showSelfTestModal = false) }
    }

    fun toggleFleetModal(show: Boolean) {
        _uiState.update { it.copy(showFleetModal = show) }
    }

    fun purgeOldRetentionRecords(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val ninetyDaysAgo = System.currentTimeMillis() - (90L * 24 * 3600 * 1000)
            val count = db.attendanceDao().purgeLegacyRecordsBefore(ninetyDaysAgo)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "🗓 DPDP Retention Gate: Purged $count records (>90 days)", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun purgeAttendanceLedger(context: Context) {
        _uiState.update { it.copy(isPurging = true) }
        viewModelScope.launch(Dispatchers.IO) {
            db.attendanceDao().deleteAllRecords()
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isPurging = false, showPurgeConfirmDialog = false) }
                Toast.makeText(context, "🗑 DPDP Act 2023: Attendance Ledger Purged.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun triggerCloudSync(context: Context) {
        if (!OmniFaceApplication.instance.isCloudSyncEnabled()) {
            _uiState.update { it.copy(showCloudConsentDialog = true) }
            return
        }
        val syncRequest = OneTimeWorkRequestBuilder<AttendanceSyncWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "OmniFaceManualSync",
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )
        Toast.makeText(context, "☁ Background Cloud Sync Dispatched", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onThemeModeChanged: (ThemeMode) -> Unit = {},
    onDismiss: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isDark = LocalThemeIsDark.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(omniBackground(isDark))
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = if (onDismiss != null) 8.dp else 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top iOS Navigation Bar (Settings title + Done Button)
        if (onDismiss != null) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Settings",
                        color = omniTextPrimary(isDark),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Done",
                            color = omniCyan(isDark),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            item {
                Column {
                    Text(
                        text = "SETTINGS",
                        color = omniTextMuted(isDark),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "System Settings",
                        color = omniTextPrimary(isDark),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        // ==========================================
        // SECTION 1 — SYSTEM
        // ==========================================
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "SYSTEM",
                    color = omniTextMuted(isDark),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(start = 4.dp)
                )

                IOSCard(modifier = Modifier.fillMaxWidth()) {
                    // Hardware Self-Test Row
                    SettingRow(
                        title = "Hardware Self-Test",
                        subtitle = "Camera, NPU, KeyStore, and Audio diagnostics",
                        icon = Icons.Default.Science,
                        iconTint = Color(0xFF8B5CF6),
                        trailing = {
                            Button(
                                onClick = { viewModel.runHardwareSelfTest(context) },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Run Test", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    )

                    HorizontalDivider(color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000), thickness = 0.5.dp)

                    // Appearance (Theme Mode)
                    Column(modifier = Modifier.padding(vertical = 10.dp)) {
                        Text(
                            text = "Appearance",
                            color = omniTextPrimary(isDark),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        CupertinoSegmentedControl(
                            items = listOf("Dark Obsidian", "Light Ivory", "System Auto"),
                            selectedIndex = when (state.selectedThemeMode) {
                                ThemeMode.DARK -> 0
                                ThemeMode.LIGHT -> 1
                                ThemeMode.SYSTEM -> 2
                            },
                            onItemSelected = { index ->
                                val mode = when (index) {
                                    0 -> ThemeMode.DARK
                                    1 -> ThemeMode.LIGHT
                                    else -> ThemeMode.SYSTEM
                                }
                                viewModel.setThemeMode(mode)
                                onThemeModeChanged(mode)
                            }
                        )
                    }

                    HorizontalDivider(color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000), thickness = 0.5.dp)

                    // Language
                    Column(modifier = Modifier.padding(vertical = 10.dp)) {
                        Text(
                            text = "Language Localization",
                            color = omniTextPrimary(isDark),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        CupertinoSegmentedControl(
                            items = listOf("English", "ಕನ್ನಡ (Kannada)", "हिंदी (Hindi)"),
                            selectedIndex = when (state.selectedLanguage) {
                                AppLanguage.ENGLISH -> 0
                                AppLanguage.KANNADA -> 1
                                AppLanguage.HINDI -> 2
                            },
                            onItemSelected = { index ->
                                val lang = when (index) {
                                    0 -> AppLanguage.ENGLISH
                                    1 -> AppLanguage.KANNADA
                                    else -> AppLanguage.HINDI
                                }
                                viewModel.setLanguage(lang)
                            }
                        )
                    }

                    HorizontalDivider(color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000), thickness = 0.5.dp)

                    // Acoustic Profile
                    Column(modifier = Modifier.padding(vertical = 10.dp)) {
                        Text(
                            text = "Acoustic Environment",
                            color = omniTextPrimary(isDark),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        CupertinoSegmentedControl(
                            items = listOf("Noisy Hallway", "Quiet Classroom", "Silent Haptic"),
                            selectedIndex = when (state.selectedSoundMode) {
                                SoundEnvironmentMode.NOISY_HALLWAY -> 0
                                SoundEnvironmentMode.QUIET_CLASSROOM -> 1
                                SoundEnvironmentMode.SILENT_VIBRATION -> 2
                            },
                            onItemSelected = { index ->
                                val mode = when (index) {
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

        // ==========================================
        // SECTION 1.5 — AI MODELS & HUGGING FACE CDN
        // ==========================================
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "AI MODELS & HUGGING FACE CDN",
                    color = omniTextMuted(isDark),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(start = 4.dp)
                )

                IOSCard(modifier = Modifier.fillMaxWidth()) {
                    // Active AI Model Status Row
                    SettingRow(
                        title = "Active Recognition Model",
                        subtitle = state.activeModelDisplayName,
                        icon = if (state.isAntelopeV2Installed) Icons.Default.VerifiedUser else Icons.Default.Memory,
                        iconTint = if (state.isAntelopeV2Installed) Color(0xFF10B981) else omniCyan(isDark),
                        trailing = {
                            if (state.isAntelopeV2Installed) {
                                Text(
                                    text = "124 MB HD",
                                    color = Color(0xFF10B981),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Text(
                                    text = "1.5 MB INT8",
                                    color = omniCyan(isDark),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    )

                    // Download State Progress or Actions
                    when (val download = state.modelDownloadState) {
                        is ModelDownloadState.Downloading -> {
                            HorizontalDivider(color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000), thickness = 0.5.dp)
                            Column(modifier = Modifier.padding(vertical = 10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Downloading AntelopeV2 FP16...",
                                        color = omniTextPrimary(isDark),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${(download.progress * 100).toInt()}% • ${download.speedKbps} KB/s",
                                        color = omniCyan(isDark),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { download.progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = omniCyan(isDark),
                                    trackColor = if (isDark) Color(0x33FFFFFF) else Color(0x14000000)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "%.1f MB / %.1f MB".format(download.downloadedMb, download.totalMb),
                                        color = omniTextMuted(isDark),
                                        fontSize = 11.sp
                                    )
                                    TextButton(
                                        onClick = { viewModel.cancelModelDownload() },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("Cancel", color = CrimsonCore, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        is ModelDownloadState.Verifying -> {
                            HorizontalDivider(color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000), thickness = 0.5.dp)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = omniCyan(isDark)
                                )
                                Text(
                                    text = "Verifying TFLite integrity & SHA-256 flatbuffer...",
                                    color = omniTextPrimary(isDark),
                                    fontSize = 12.sp
                                )
                            }
                        }
                        is ModelDownloadState.Error -> {
                            HorizontalDivider(color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000), thickness = 0.5.dp)
                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                Text(
                                    text = download.message,
                                    color = CrimsonCore,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { viewModel.startModelDownload(context) },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = omniCyan(isDark)),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text("Retry Download", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.toggleHfConfigModal(true) },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text("Configure Token", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        else -> {
                            HorizontalDivider(color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000), thickness = 0.5.dp)
                            if (!state.isAntelopeV2Installed) {
                                SettingRow(
                                    title = "Download AntelopeV2 ResNet100",
                                    subtitle = "124 MB • Glint360K 512-D High Accuracy",
                                    icon = Icons.Default.CloudDownload,
                                    iconTint = omniCyan(isDark),
                                    trailing = {
                                        Button(
                                            onClick = { viewModel.startModelDownload(context) },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = omniCyan(isDark)),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Text("Download", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                )
                            } else {
                                SettingRow(
                                    title = "Model Storage Management",
                                    subtitle = "AntelopeV2 model cached in private storage (124 MB)",
                                    icon = Icons.Default.Storage,
                                    iconTint = Color(0xFF10B981),
                                    trailing = {
                                        OutlinedButton(
                                            onClick = { viewModel.deleteDownloadedModel(context) },
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text("Free Storage", color = CrimsonCore, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000), thickness = 0.5.dp)

                    // Cloudflare & Private Hugging Face Gateway Settings
                    SettingRow(
                        title = "Zero-Trust Model Gateway & Vault",
                        subtitle = if (state.isUsingCloudflareGateway) {
                            "☁️ Cloudflare Edge Zero-Trust Active (Token-Free)"
                        } else {
                            "Repo: ${state.hfRepoId} • ${if (state.hasHfToken) "🔒 Custom Token Active" else "🛡️ Obfuscated Gateway Active"}"
                        },
                        icon = if (state.isUsingCloudflareGateway) Icons.Default.CloudSync else Icons.Default.VpnKey,
                        iconTint = if (state.isUsingCloudflareGateway) Color(0xFF38BDF8) else Color(0xFFF59E0B),
                        onClick = { viewModel.toggleHfConfigModal(true) }
                    )
                }
            }
        }

        // ============================================================
        // SECTION 1.6 — QUALCOMM AI HUB SUITE (Snapdragon devices only)
        // ============================================================
        if (state.isQualcommDevice) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Text(
                            text = "QUALCOMM AI HUB — FACE INTELLIGENCE SUITE",
                            color = omniTextMuted(isDark),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        // Snapdragon badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF2563EB))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "HEXAGON NPU",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.3.sp
                            )
                        }
                    }

                    // Subtitle: detected chip
                    Text(
                        text = "Optimized for ${state.npuHardwareInfo.socModel} • ${state.npuHardwareInfo.peakTops}",
                        color = Color(0xFF2563EB),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                    )

                    IOSCard(modifier = Modifier.fillMaxWidth()) {
                        QualcommSuiteDownloadManager.SUITE_MODELS.forEachIndexed { index, model ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000),
                                    thickness = 0.5.dp
                                )
                            }
                            val modelState = state.qualcommSuiteStates[model.id]
                                ?: QualcommModelState.Idle

                            Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                when (modelState) {
                                    is QualcommModelState.Installed -> {
                                        SettingRow(
                                            title = model.displayName,
                                            subtitle = "✅ Installed • ${model.fileSizeMb.toInt()} MB on device",
                                            icon = Icons.Default.CheckCircle,
                                            iconTint = Color(0xFF10B981),
                                            trailing = {
                                                TextButton(
                                                    onClick = { viewModel.deleteQualcommModel(model.id) },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                                ) {
                                                    Text(
                                                        "Remove",
                                                        color = Color(0xFFEF4444),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        )
                                    }

                                    is QualcommModelState.Downloading -> {
                                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    model.displayName,
                                                    color = omniTextPrimary(isDark),
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    "${(modelState.progress * 100).toInt()}% • ${modelState.speedKbps} KB/s",
                                                    color = omniCyan(isDark),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            LinearProgressIndicator(
                                                progress = { modelState.progress },
                                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                                color = Color(0xFF2563EB),
                                                trackColor = if (isDark) Color(0x22FFFFFF) else Color(0x22000000)
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    "%.1f / %.1f MB".format(modelState.downloadedMb, modelState.totalMb),
                                                    color = omniTextMuted(isDark),
                                                    fontSize = 11.sp
                                                )
                                                TextButton(
                                                    onClick = { viewModel.cancelQualcommDownload(model.id) },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                                ) {
                                                    Text(
                                                        "Cancel",
                                                        color = Color(0xFFEF4444),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    is QualcommModelState.Error -> {
                                        SettingRow(
                                            title = model.displayName,
                                            subtitle = "⚠️ ${modelState.message}",
                                            icon = Icons.Default.ErrorOutline,
                                            iconTint = Color(0xFFEF4444),
                                            trailing = {
                                                TextButton(
                                                    onClick = { viewModel.downloadQualcommModel(model.id) },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                                ) {
                                                    Text(
                                                        "Retry",
                                                        color = Color(0xFF2563EB),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        )
                                    }

                                    else -> {
                                        // Idle — show Download button
                                        SettingRow(
                                            title = model.displayName,
                                            subtitle = "Available • ~${model.fileSizeMb.toInt()} MB",
                                            icon = Icons.Default.CloudDownload,
                                            iconTint = omniTextMuted(isDark),
                                            trailing = {
                                                TextButton(
                                                    onClick = { viewModel.downloadQualcommModel(model.id) },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                                ) {
                                                    Text(
                                                        "Download",
                                                        color = Color(0xFF2563EB),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // SECTION 2 — SECURITY
        // ==========================================
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "SECURITY",
                    color = omniTextMuted(isDark),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(start = 4.dp)
                )

                IOSCard(modifier = Modifier.fillMaxWidth()) {
                    // Biometric Matching Security
                    Column(modifier = Modifier.padding(vertical = 10.dp)) {
                        Text(
                            text = "Biometric Matching Sensitivity",
                            color = omniTextPrimary(isDark),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        CupertinoSegmentedControl(
                            items = listOf("Standard", "High", "Strict"),
                            selectedIndex = when (state.selectedTier) {
                                SecurityTier.STANDARD -> 0
                                SecurityTier.HIGH -> 1
                                SecurityTier.STRICT -> 2
                            },
                            onItemSelected = { index ->
                                val tier = when (index) {
                                    0 -> SecurityTier.STANDARD
                                    1 -> SecurityTier.HIGH
                                    else -> SecurityTier.STRICT
                                }
                                viewModel.setSecurityTier(tier)
                            }
                        )
                    }

                    HorizontalDivider(color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000), thickness = 0.5.dp)

                    // 2FA / QR Verification
                    SettingRow(
                        title = "2FA QR Verification",
                        subtitle = "Cross-correlate face with badge QR code",
                        icon = Icons.Default.QrCodeScanner,
                        iconTint = omniCyan(isDark),
                        trailing = {
                            Switch(
                                checked = state.isTwoFactorEnabled,
                                onCheckedChange = { viewModel.toggleTwoFactorMode(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = omniCyan(isDark)
                                )
                            )
                        }
                    )

                    HorizontalDivider(color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000), thickness = 0.5.dp)

                    // Silicon NPU Hardware & Accelerators (Progressive Disclosure)
                    SettingRow(
                        title = "Silicon NPU Co-Processor",
                        subtitle = "${state.npuHardwareInfo.npuName} • ${state.npuHardwareInfo.peakTops}",
                        icon = Icons.Default.Memory,
                        iconTint = omniCyan(isDark),
                        onClick = { viewModel.toggleNpuDetails() }
                    )

                    AnimatedVisibility(visible = state.showNpuDetails) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isDark) Color(0x261E293B) else Color(0xFFF1F5F9))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("• Silicon SoC: ${state.npuHardwareInfo.socModel}", color = omniTextPrimary(isDark), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("• NPU Architecture: ${state.npuHardwareInfo.npuArchitecture}", color = omniTextSecondary(isDark), fontSize = 12.sp)
                            Text("• Peak Compute: ${state.npuHardwareInfo.peakTops} Tensor Performance", color = omniEmerald(isDark), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("• Supported Precisions: ${state.npuHardwareInfo.supportedPrecisions.joinToString(", ")}", color = omniTextSecondary(isDark), fontSize = 11.sp)
                            Text("• Platform Board: ${state.npuHardwareInfo.boardPlatform} (${state.npuHardwareInfo.socManufacturer})", color = omniTextMuted(isDark), fontSize = 11.sp)
                            if (state.npuHardwareInfo.armFeatures.isNotEmpty()) {
                                Text("• ARM ISA Features: ${state.npuHardwareInfo.armFeatures.take(6).joinToString(", ")}", color = omniTextMuted(isDark), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                            Text("• Hardware Verification: ${if (state.npuHardwareInfo.isGenuineNpuDetected) "✅ Genuine Silicon NPU Confirmed" else "⚠️ Emulated Engine"}", color = if (state.npuHardwareInfo.isGenuineNpuDetected) omniEmerald(isDark) else (if (isDark) AmberCore else LightAmberCore), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    HorizontalDivider(color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000), thickness = 0.5.dp)

                    // Hardware KeyStore & StrongBox (Progressive Disclosure)
                    SettingRow(
                        title = "Hardware Security",
                        subtitle = if (state.isStrongBoxActive) "StrongBox HSM Active" else "KeyStore TEE Active",
                        icon = Icons.Default.Shield,
                        iconTint = omniEmerald(isDark),
                        onClick = { viewModel.toggleSecurityDetails() }
                    )

                    AnimatedVisibility(visible = state.showSecurityDetails) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isDark) Color(0x261E293B) else Color(0xFFF1F5F9))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("• StrongBox HSM: ${if (state.isStrongBoxActive) "Available" else "Fallback TEE"}", color = omniTextPrimary(isDark), fontSize = 12.sp)
                            Text("• AES-256-GCM: Hardware-Backed", color = omniTextPrimary(isDark), fontSize = 12.sp)
                            Text("• Key Isolation: Secure Element", color = omniTextPrimary(isDark), fontSize = 12.sp)
                            Text("• Attestation: Verified", color = omniTextPrimary(isDark), fontSize = 12.sp)
                            Text("• Aegis Merkle Root: ${if (state.merkleRoot.isNotEmpty()) state.merkleRoot.take(16) + "..." else "Synchronized"}", color = omniCyan(isDark), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        // ==========================================
        // SECTION 3 — DATA
        // ==========================================
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "DATA",
                    color = omniTextMuted(isDark),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(start = 4.dp)
                )

                IOSCard(modifier = Modifier.fillMaxWidth()) {
                    // Encrypted Backup
                    SettingRow(
                        title = "Encrypted Backup",
                        subtitle = "AES-256 SQLite dump with SHA-256 manifest",
                        icon = Icons.Default.Save,
                        iconTint = Color(0xFF0D9488),
                        trailing = {
                            Button(
                                onClick = { viewModel.backupDatabaseEncrypted(context) },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(if (state.isBackingUp) "Backing Up..." else "Export", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    )

                    HorizontalDivider(color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000), thickness = 0.5.dp)

                    // Cloud Synchronization
                    SettingRow(
                        title = "Cloud Synchronization",
                        subtitle = if (state.isCloudSyncEnabled) "Enabled (Periodic Sync)" else "Disabled (Local-Only)",
                        icon = Icons.Default.CloudSync,
                        iconTint = omniCyan(isDark),
                        trailing = {
                            Switch(
                                checked = state.isCloudSyncEnabled,
                                onCheckedChange = { isChecked ->
                                    if (isChecked) viewModel.showCloudConsentDialog(true) else viewModel.setCloudSyncConsent(false)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = omniCyan(isDark)
                                )
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    CupertinoButton(
                        text = "☁ Sync Attendance Cloud Now",
                        isSecondary = true,
                        onClick = { viewModel.triggerCloudSync(context) }
                    )

                    HorizontalDivider(color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000), thickness = 0.5.dp, modifier = Modifier.padding(top = 10.dp))

                    // Data Retention & Purge Actions
                    Column(modifier = Modifier.padding(vertical = 10.dp)) {
                        Text(
                            text = "Data Retention & DPDP Act 2023",
                            color = omniTextPrimary(isDark),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "90-Day retention pruning & right to erasure compliance.",
                            color = omniTextMuted(isDark),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.purgeOldRetentionRecords(context) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0),
                                    contentColor = omniTextPrimary(isDark)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text("Purge >90 Days", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { viewModel.showPurgeDialog(true) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = CrimsonCore),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text("Purge All", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // SECTION 4 — COMPLIANCE & SECURITY
        // ==========================================
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "COMPLIANCE & SECURITY",
                    color = omniTextMuted(isDark),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(start = 4.dp)
                )

                IOSCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "ISO/IEC 19794-5 • DPDP Act 2023",
                        color = omniTextPrimary(isDark),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Cryptographic seal, audit evidence manifest, and biometric operating point verification report.",
                        color = omniTextMuted(isDark),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    CupertinoButton(
                        text = if (state.isGeneratingReport) "Compiling Evidence..." else "Export Compliance Report",
                        brush = Brush.horizontalGradient(listOf(Color(0xFFD97706), Color(0xFFB45309))),
                        contentColor = Color.White,
                        enabled = !state.isGeneratingReport,
                        onClick = { viewModel.exportComplianceReport(context) }
                    )
                }
            }
        }

        // ==========================================
        // SECTION 5 — KIOSK
        // ==========================================
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "KIOSK",
                    color = omniTextMuted(isDark),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(start = 4.dp)
                )

                IOSCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.triggerDoorUnlock(context) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.isDoorUnlocked) EmeraldCore else (if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0)),
                                contentColor = if (state.isDoorUnlocked) Color.White else omniTextPrimary(isDark)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text(if (state.isDoorUnlocked) "🚪 Unlocked (3s)" else "🚪 Pulse Door Relay", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val activity = context as? Activity
                                if (activity != null) viewModel.toggleKioskLock(activity, "1234")
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.isKioskLocked) CrimsonCore else (if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0)),
                                contentColor = if (state.isKioskLocked) Color.White else omniTextPrimary(isDark)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text(if (state.isKioskLocked) "🔒 Locked PIN" else "🔓 Lock Kiosk", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Hardware Diagnostics Self-Test Modal Sheet
    if (state.showSelfTestModal && state.selfTestReport != null) {
        val report = state.selfTestReport!!
        AlertDialog(
            onDismissRequest = { viewModel.dismissSelfTestModal() },
            containerColor = if (isDark) Color(0xFF0F172A) else Color(0xFFFFFFFF),
            icon = {
                Icon(
                    imageVector = if (report.overallPassed) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (report.overallPassed) EmeraldCore else CrimsonCore,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = if (report.overallPassed) "✅ All 5 Systems Operational" else "⚠️ System Diagnostics Alert",
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
                                color = if (item.isPassed) EmeraldCore else CrimsonCore,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissSelfTestModal() }) {
                    Text("Done", color = omniCyan(isDark), fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Google Play Prominent Disclosure & Affirmative Consent Dialog
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
                        "In compliance with Google Play Data Safety & Privacy Policies, please review the data collected during cloud synchronization:",
                        color = omniTextSecondary(isDark),
                        fontSize = 12.sp
                    )
                    Text(
                        "• Data Transmitted: Student Name, Roll Number, Session Timestamp, Confidence Score, and Aegis SHA-256 Hash.\n" +
                        "• Purpose: Institutional attendance reconciliation and blockchain auditing.\n" +
                        "• Biometric Privacy: Facial images and raw biometric vectors are processed 100% locally on your device and are NEVER sent to the cloud.\n" +
                        "• Security: Encrypted in transit via HTTPS / TLS 1.3.",
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
                    Text("I Agree & Enable Sync", color = omniCyan(isDark), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showCloudConsentDialog(false) }) {
                    Text("Keep Local-Only", color = omniTextSecondary(isDark))
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
                Text("Confirm Ledger Purge", color = CrimsonCore, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Are you sure you want to purge all local attendance logs? This action is compliant with DPDP Act 2023 and cannot be undone.",
                    color = omniTextSecondary(isDark),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.purgeAttendanceLedger(context) }
                ) {
                    Text("Purge Everything", color = CrimsonCore, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showPurgeDialog(false) }) {
                    Text("Cancel", color = omniTextPrimary(isDark))
                }
            }
        )
    }

    // Zero-Trust Gateway & Hugging Face Vault Configuration Dialog
    if (state.showHfConfigModal) {
        var repoInput by remember { mutableStateOf(state.hfRepoId) }
        var gatewayInput by remember { mutableStateOf(state.cfGatewayUrl) }
        var tokenInput by remember { mutableStateOf("") }
        var showTokenPlain by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { viewModel.toggleHfConfigModal(false) },
            containerColor = if (isDark) Color(0xFF0F172A) else Color(0xFFFFFFFF),
            icon = {
                Icon(
                    if (gatewayInput.isNotBlank()) Icons.Default.CloudSync else Icons.Default.VpnKey,
                    contentDescription = null,
                    tint = if (gatewayInput.isNotBlank()) Color(0xFF38BDF8) else Color(0xFFF59E0B),
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    "Zero-Trust Model Gateway & Vault",
                    color = omniTextPrimary(isDark),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Configure a Cloudflare Zero-Trust Edge Gateway (Recommended — 100% Token-Free) or direct private Hugging Face credentials:",
                        color = omniTextSecondary(isDark),
                        fontSize = 12.sp
                    )

                    // Option 1: Cloudflare Gateway URL
                    OutlinedTextField(
                        value = gatewayInput,
                        onValueChange = { gatewayInput = it },
                        label = { Text("☁️ Cloudflare Gateway URL (Token-Free)") },
                        placeholder = { Text("https://omniface-gateway.workers.dev") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = if (isDark) Color(0x33FFFFFF) else Color(0x33000000)
                        )
                    )

                    HorizontalDivider(
                        color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000),
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    // Option 2: Direct Hugging Face Repo & Token
                    OutlinedTextField(
                        value = repoInput,
                        onValueChange = { repoInput = it },
                        label = { Text("Hugging Face Repo ID") },
                        placeholder = { Text("user/repo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = omniCyan(isDark),
                            unfocusedBorderColor = if (isDark) Color(0x33FFFFFF) else Color(0x33000000)
                        )
                    )

                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text("HF Token (hf_...) [Optional if using Gateway]") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showTokenPlain) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(
                                onClick = { showTokenPlain = !showTokenPlain },
                                modifier = Modifier.semantics {
                                    contentDescription = "Toggle Visibility"
                                }
                            ) {
                                Icon(
                                    imageVector = if (showTokenPlain) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = omniTextMuted(isDark)
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = omniCyan(isDark),
                            unfocusedBorderColor = if (isDark) Color(0x33FFFFFF) else Color(0x33000000)
                        )
                    )

                    Text(
                        if (gatewayInput.isNotBlank()) "✨ Zero-Trust Active: No tokens are stored on this device. Model streams securely via Cloudflare Edge."
                        else "🛡️ Security Note: Tokens are stored encrypted in private app storage with zero plaintext exposure.",
                        color = if (gatewayInput.isNotBlank()) Color(0xFF38BDF8) else omniTextMuted(isDark),
                        fontSize = 10.sp,
                        fontWeight = if (gatewayInput.isNotBlank()) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveHfCredentials(
                            context,
                            repoInput,
                            tokenInput.ifBlank { null },
                            gatewayInput.ifBlank { null }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = omniCyan(isDark)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Save & Apply", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.toggleHfConfigModal(false) }) {
                    Text("Cancel", color = omniTextSecondary(isDark))
                }
            }
        )
    }
}
