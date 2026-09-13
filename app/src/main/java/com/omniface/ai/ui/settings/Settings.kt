@file:Suppress("DEPRECATION")

package com.omniface.ai.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.omniface.ai.OmniFaceApplication
import com.omniface.ai.audio.BiometricSoundboard
import com.omniface.ai.audio.SoundEnvironmentMode
import com.omniface.ai.billing.PlayBillingManager
import com.omniface.ai.billing.PaywallTriggerReason
import com.omniface.ai.billing.SubscriptionTier
import com.omniface.ai.billing.SubscriptionTierManager
import com.omniface.ai.data.local.ScannerPreferences
import com.omniface.ai.hardware.*
import com.omniface.ai.i18n.AppLanguage
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey
import com.omniface.ai.ml.*
import com.omniface.ai.security.AndroidSecurityUtils
import com.omniface.ai.security.ComplianceEvidenceReportGenerator
import com.omniface.ai.sync.AttendanceSyncWorker
import com.omniface.ai.ui.billing.PaywallBottomSheet
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

@Immutable
data class SettingsUiState(
    val hardwareTier: String = "NPU / NNAPI INT8",
    val latencyMs: Long = 6L,
    val selectedTier: SecurityTier = SecurityTier.HIGH,
    val selectedThemeMode: ThemeMode = ThemeMode.SYSTEM,
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
    val modelDownloadState: ModelDownloadState = ModelDownloadState.Idle(false, "OmniFace Deep AI Engine"),
    val activeModelDisplayName: String = "OmniFace Deep AI Engine",
    val isNeuralModelInstalled: Boolean = false,
    val isAutoScanOnOpen: Boolean = ScannerPreferences.isAutoScanOnOpen(),
    val autoPauseOnMatch: Boolean = ScannerPreferences.isAutoPauseOnMatch(),
    val showHfConfigModal: Boolean = false,
    val neuralModelConfig: NeuralModelConfig = NeuralModelConfigManager.configState.value,
    val isQualcommDevice: Boolean = NpuHardwareDetector.isQualcommAiHubDevice(),
    val orgType: String = LocalizationManager.currentOrgType.value
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
            isNeuralModelInstalled = downloadManager.isNeuralModelInstalled(),
            activeModelDisplayName = downloadManager.getActiveModelDisplayName(),
            orgType = LocalizationManager.currentOrgType.value,
            neuralModelConfig = NeuralModelConfigManager.configState.value
        )
    )
    private val ctx = OmniFaceApplication.instance
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun setOrgType(newType: String) {
        LocalizationManager.setOrgType(newType, OmniFaceApplication.instance)
        _uiState.update { it.copy(orgType = newType) }
    }

    init {
        benchmarkHardware()
        computeMerkleBatch()
        observeModelDownloads()
        observeNeuralModelConfig()
    }

    private fun observeNeuralModelConfig() {
        viewModelScope.launch {
            NeuralModelConfigManager.configState.collect { config ->
                _uiState.update { it.copy(neuralModelConfig = config) }
            }
        }
    }

    private fun observeModelDownloads() {
        viewModelScope.launch {
            downloadManager.downloadState.collect { state ->
                val isInstalled = downloadManager.isNeuralModelInstalled()
                val name = downloadManager.getActiveModelDisplayName()
                _uiState.update {
                    it.copy(
                        modelDownloadState = state,
                        isNeuralModelInstalled = isInstalled,
                        activeModelDisplayName = name
                    )
                }
            }
        }
    }

    fun startModelDownload(context: Context) {
        downloadManager.startDownload {
            Toast.makeText(context, "🎉 OmniFace Custom Neural Model Active!", Toast.LENGTH_LONG).show()
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
        return when (prefs.getString("theme_mode", "SYSTEM")) {
            "DARK" -> ThemeMode.DARK
            "LIGHT" -> ThemeMode.LIGHT
            else -> ThemeMode.SYSTEM
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

    fun toggleAutoScanOnOpen(enabled: Boolean) {
        ScannerPreferences.setAutoScanOnOpen(enabled)
        _uiState.update { it.copy(isAutoScanOnOpen = enabled) }
    }

    fun toggleAutoPauseOnMatch(enabled: Boolean) {
        ScannerPreferences.setAutoPauseOnMatch(enabled)
        _uiState.update { it.copy(autoPauseOnMatch = enabled) }
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
                val destFile = AndroidSecurityUtils.createEncryptedDatabaseBackup(context, db)
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

/**
 * Sovereign Apple iOS Master Settings Hub.
 *
 * Displays lightweight grouped category cards on the root level and routes seamlessly
 * to dedicated category sub-screens with zero recomposition churn.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onThemeModeChanged: (ThemeMode) -> Unit = {},
    onDismiss: (() -> Unit)? = null,
    onSignOut: (() -> Unit)? = null,
    userEmail: String = "",
    userName: String = ""
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isDark = LocalThemeIsDark.current
    var currentSubScreen by remember { mutableStateOf<SettingsCategory?>(null) }
    var showHardwareInfoDialog by remember { mutableStateOf(false) }
    var showPaywall by remember { mutableStateOf(false) }
    var paywallReason by remember { mutableStateOf(PaywallTriggerReason.STUDENT_LIMIT_REACHED) }
    val activeTier by SubscriptionTierManager.currentTier.collectAsState()
    val playPrice by PlayBillingManager.formattedPrice.collectAsState()
    val deviceCapacity = remember { DeviceCapacityGovernor.evaluateDeviceCapacity(context) }

    // Intercept back gesture on active sub-screens to return to the root settings category list
    BackHandler(enabled = currentSubScreen != null) {
        currentSubScreen = null
    }

    // Intercept back gesture on hardware info modal
    BackHandler(enabled = showHardwareInfoDialog) {
        showHardwareInfoDialog = false
    }

    // Intercept back gesture on active root dialogs/modals
    BackHandler(enabled = state.showSelfTestModal) {
        viewModel.dismissSelfTestModal()
    }
    BackHandler(enabled = state.showFleetModal) {
        viewModel.toggleFleetModal(false)
    }
    BackHandler(enabled = state.showHfConfigModal) {
        viewModel.toggleHfConfigModal(false)
    }
    BackHandler(enabled = state.showPurgeConfirmDialog) {
        viewModel.showPurgeDialog(false)
    }
    BackHandler(enabled = state.showCloudConsentDialog) {
        viewModel.showCloudConsentDialog(false)
    }

    AnimatedContent(
        targetState = currentSubScreen,
        transitionSpec = {
            if (targetState != null) {
                slideInHorizontally { width -> width } + fadeIn() togetherWith
                    slideOutHorizontally { width -> -width / 3 } + fadeOut()
            } else {
                slideInHorizontally { width -> -width / 3 } + fadeIn() togetherWith
                    slideOutHorizontally { width -> width } + fadeOut()
            }
        },
        label = "settingsSubScreenTransition"
    ) { subScreen ->
        when (subScreen) {
            SettingsCategory.APPEARANCE -> {
                AppearanceSettingsSubScreen(
                    state = state,
                    viewModel = viewModel,
                    onBack = { currentSubScreen = null },
                    onThemeModeChanged = onThemeModeChanged
                )
            }
            SettingsCategory.BIOMETRIC_SECURITY -> {
                BiometricSettingsSubScreen(
                    state = state,
                    viewModel = viewModel,
                    onBack = { currentSubScreen = null }
                )
            }
            SettingsCategory.KIOSK_ACCESS -> {
                KioskAccessSettingsSubScreen(
                    state = state,
                    viewModel = viewModel,
                    onBack = { currentSubScreen = null }
                )
            }
            SettingsCategory.DATA_GOVERNANCE -> {
                DataGovernanceSettingsSubScreen(
                    state = state,
                    viewModel = viewModel,
                    onBack = { currentSubScreen = null }
                )
            }
            null -> {
                // ── MASTER SETTINGS CATEGORIES HUB ──
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(omniBackgroundBrush(isDark))
                        .padding(horizontal = 20.dp),
                    contentPadding = PaddingValues(top = if (onDismiss != null) 8.dp else 20.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header Bar
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "SYSTEM PREFERENCES",
                                    color = OmniViolet,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "System Preferences",
                                    color = omniTextPrimary(isDark),
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = (-0.5).sp
                                )
                                Text(
                                    text = "Hardware & Neural Engine",
                                    color = omniTextMuted(isDark),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Normal
                                )
                            }

                            IOSGlassPill(
                                text = "● LIVE NPU",
                                icon = Icons.Default.Bolt,
                                accentColor = Color(0xFF10B981)
                            )
                        }
                    }

                    // Hardware Silicon Overview Banner
                    item {
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
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(OmniViolet.copy(alpha = if (isDark) 0.22f else 0.14f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Memory,
                                                contentDescription = "SoC",
                                                tint = OmniViolet,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = state.npuHardwareInfo.socModel.removePrefix("Qualcomm "),
                                                color = omniTextPrimary(isDark),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 2,
                                                lineHeight = 17.sp,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = LocalizationManager.get(StringKey.NPU_ACCELERATION),
                                                color = omniTextMuted(isDark),
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        IOSGlassPill(
                                            text = state.npuHardwareInfo.shortNpuLabel,
                                            accentColor = omniCyan(isDark)
                                        )
                                        IconButton(
                                            onClick = { showHardwareInfoDialog = true },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Info,
                                                contentDescription = "Hardware Information",
                                                tint = omniCyan(isDark),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }

                                HorizontalDivider(color = if (isDark) Color(0x26FFFFFF) else Color(0x14000000))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(if (isDark) Color(0x1A1E293B) else Color(0xFFF1F5F9))
                                            .padding(vertical = 8.dp, horizontal = 10.dp)
                                    ) {
                                        Column {
                                            Text(
                                                text = "PEAK TOPS",
                                                color = omniTextMuted(isDark),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(state.npuHardwareInfo.peakTops, color = OmniViolet, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(if (isDark) Color(0x1A1E293B) else Color(0xFFF1F5F9))
                                            .padding(vertical = 8.dp, horizontal = 10.dp)
                                    ) {
                                        Column {
                                            Text(
                                                text = "MEMORY",
                                                color = omniTextMuted(isDark),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text("${"%.1f".format(deviceCapacity.totalRamGb)} GB", color = omniCyan(isDark), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(if (isDark) Color(0x1A1E293B) else Color(0xFFF1F5F9))
                                            .padding(vertical = 8.dp, horizontal = 10.dp)
                                    ) {
                                        Column {
                                            Text(
                                                text = "LATENCY",
                                                color = omniTextMuted(isDark),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text("${state.latencyMs}ms", color = Color(0xFF10B981), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Commercial Subscription & Plan Management Card
                    item {
                        IOSCard(cornerRadius = 20.dp) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (activeTier == SubscriptionTier.FREE) Color(0xFF64748B).copy(alpha = 0.18f)
                                                    else Color(0xFF10B981).copy(alpha = 0.18f)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (activeTier == SubscriptionTier.FREE) Icons.Default.Lock else Icons.Default.WorkspacePremium,
                                                contentDescription = null,
                                                tint = if (activeTier == SubscriptionTier.FREE) omniTextSecondary(isDark) else Color(0xFF10B981),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f, fill = false)) {
                                            Text(
                                                text = activeTier.title,
                                                color = omniTextPrimary(isDark),
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = when (activeTier) {
                                                    SubscriptionTier.FREE -> "25 people • $playPrice"
                                                    SubscriptionTier.PREMIUM -> "250 people • Active"
                                                    SubscriptionTier.PRO -> "500 people • Active"
                                                    SubscriptionTier.INSTITUTION -> "500+ people • Fleet"
                                                },
                                                color = omniTextMuted(isDark),
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            if (activeTier == SubscriptionTier.FREE) {
                                                paywallReason = PaywallTriggerReason.STUDENT_LIMIT_REACHED
                                                showPaywall = true
                                            } else if (activeTier == SubscriptionTier.PREMIUM || activeTier == SubscriptionTier.PRO) {
                                                PlayBillingManager.openSubscriptionManagement(context)
                                            } else {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://omniface.vercel.app/subscription"))
                                                try { context.startActivity(intent) } catch (_: Exception) {}
                                            }
                                        },
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (activeTier == SubscriptionTier.FREE) Color(0xFF10B981) else (if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0))
                                        ),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text(
                                            text = if (activeTier == SubscriptionTier.FREE) "UPGRADE" else "MANAGE",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (activeTier == SubscriptionTier.FREE) Color.White else omniTextPrimary(isDark)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Section Title: Operator Session & Identity
                    item {
                        Text(
                            text = "OPERATOR SESSION & IDENTITY",
                            color = omniTextMuted(isDark),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    }

                    item {
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
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    Brush.linearGradient(
                                                        listOf(Color(0xFF6366F1), Color(0xFF06B6D4))
                                                    )
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.VerifiedUser,
                                                contentDescription = "Operator",
                                                tint = Color.White,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (userName.isNotBlank()) userName else "Fleet Operator",
                                                color = omniTextPrimary(isDark),
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = if (userEmail.isNotBlank()) userEmail else "admin@omniface.ai",
                                                color = omniTextMuted(isDark),
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    IOSGlassPill(
                                        text = "VERIFIED",
                                        icon = Icons.Default.CheckCircle,
                                        accentColor = Color(0xFF10B981)
                                    )
                                }

                                if (onSignOut != null) {
                                    HorizontalDivider(color = if (isDark) Color(0x26FFFFFF) else Color(0x14000000))

                                    Button(
                                        onClick = onSignOut,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFFEF4444).copy(alpha = if (isDark) 0.16f else 0.10f)
                                        ),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                        modifier = Modifier.fillMaxWidth().height(42.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ExitToApp,
                                            contentDescription = "Sign Out",
                                            tint = Color(0xFFEF4444),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Sign Out & Switch Authority",
                                            color = Color(0xFFEF4444),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Section Title: Organization Profile
                    item {
                        Text(
                            text = "ORGANIZATION & TERMINOLOGY",
                            color = omniTextMuted(isDark),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    }

                    // Organization Profile Switcher Card
                    item {
                        val currentOrgType by LocalizationManager.currentOrgType.collectAsState()
                        IOSCard(cornerRadius = 20.dp) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(CircleShape)
                                                .background(OmniViolet.copy(alpha = 0.18f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Business,
                                                contentDescription = "Organization Profile",
                                                tint = OmniViolet,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = "Organization Profile",
                                                color = omniTextPrimary(isDark),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "Roster: ${LocalizationManager.getEntityPlural(currentOrgType)} • ID: ${LocalizationManager.getIdLabel(currentOrgType)}",
                                                color = omniTextMuted(isDark),
                                                fontSize = 11.sp
                                            )
                                        }
                                    }

                                    IOSGlassPill(
                                        text = currentOrgType,
                                        accentColor = OmniViolet
                                    )
                                }

                                // Selector Chips
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                ) {
                                    val orgTypes = listOf(
                                        "SCHOOL" to "School / College",
                                        "CORPORATE" to "Corporate Office",
                                        "GYM_EVENT" to "Gym / Fitness",
                                        "COACHING" to "Coaching / Institute"
                                    )
                                    items(orgTypes) { (typeKey, typeLabel) ->
                                        val isSelected = currentOrgType.equals(typeKey, ignoreCase = true)
                                        Box(
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .background(if (isSelected) OmniViolet else (if (isDark) Color(0x1AFFFFFF) else Color(0x0D000000)))
                                                .border(0.75.dp, if (isSelected) OmniViolet else Color.Transparent, CircleShape)
                                                .clickable {
                                                    LocalizationManager.setOrgType(typeKey, context)
                                                    viewModel.setOrgType(typeKey)
                                                }
                                                .padding(horizontal = 14.dp, vertical = 7.dp)
                                        ) {
                                            Text(
                                                text = typeLabel,
                                                color = if (isSelected) Color.White else omniTextMuted(isDark),
                                                fontSize = 11.5.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Section Title
                    item {
                        Text(
                            text = "SECURITY & ACCESS CONTROL",
                            color = omniTextMuted(isDark),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    }

                    // 2FA QR Code Verification Mode Quick Toggle Card
                    item {
                        IOSCard(cornerRadius = 20.dp) {
                            SettingRow(
                                title = "Two-Factor 2D/3D Mode",
                                subtitle = if (state.isTwoFactorEnabled) {
                                    "Require QR Code + Face for verification • Hardware Barcode Active"
                                } else {
                                    "Require QR Code + Face for verification (1-Factor Face Mode Active)"
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

                    // In-House Promo Banner (Free tier only)
                    item {
                        InHousePromoBanner(
                            modifier = Modifier.fillMaxWidth(),
                            onUpgradeClick = {
                                paywallReason = PaywallTriggerReason.ADS_REMOVAL
                                showPaywall = true
                            }
                        )
                    }

                    // 6 Grouped Settings Category Cards
                    items(SettingsCategory.values().size) { idx ->
                        val cat = SettingsCategory.values()[idx]
                        SettingsCategoryCard(
                            category = cat,
                            isDark = isDark,
                            onClick = { currentSubScreen = cat }
                        )
                    }
                }
            }
        }
    }

    // ── Master Modals & Alert Dialogs ──
    com.omniface.ai.ui.settings.dialogs.SettingsMasterDialogs(
        state = state,
        viewModel = viewModel,
        context = context,
        isDark = isDark,
        showHardwareInfoDialog = showHardwareInfoDialog,
        onDismissHardwareInfo = { showHardwareInfoDialog = false },
        deviceCapacity = deviceCapacity
    )

    if (showPaywall) {
        PaywallBottomSheet(
            triggerReason = paywallReason,
            onDismiss = { showPaywall = false },
            onUpgradeSuccess = { showPaywall = false }
        )
    }
}
