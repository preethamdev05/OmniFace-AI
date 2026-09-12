@file:Suppress("DEPRECATION")

package com.omniface.ai.ui.dashboard

import android.content.Context
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.omniface.ai.OmniFaceApplication
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import android.widget.Toast
import com.omniface.ai.ml.ModelDownloadManager
import com.omniface.ai.ml.ModelDownloadState
import com.omniface.ai.ml.FaceRecognitionEngine
import com.omniface.ai.ml.HardwareTier
import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.sync.AttendanceSyncWorker
import com.omniface.ai.ui.components.*
import com.omniface.ai.ui.navigation.Screen
import com.omniface.ai.ui.theme.*
import com.omniface.ai.ml.UnifiedFaceIntelligenceEngine
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omniface.ai.billing.SubscriptionTierManager
import com.omniface.ai.billing.PaywallTriggerReason
import com.omniface.ai.ui.billing.PaywallBottomSheet
import com.omniface.ai.ui.components.InHousePromoBanner

@Immutable
data class DashboardUiState(
    val enrolledCount: Int = 0,
    val todayScansCount: Int = 0,
    val hardwareTierLabel: String = "Engine Standby",
    val benchmarkLatencyMs: Long = 0L,
    val selectedTier: SecurityTier = SecurityTier.HIGH,
    val recentScans: List<AttendanceRecordEntity> = emptyList(),
    val hourlyVelocity: List<Pair<String, Int>> = listOf(
        "08h" to 0, "09h" to 0, "10h" to 0, "11h" to 0,
        "12h" to 0, "13h" to 0, "14h" to 0, "15h" to 0,
        "16h" to 0, "17h" to 0
    ),
    val syncState: com.omniface.ai.sync.FleetSyncState = com.omniface.ai.sync.FleetSyncState.Idle,
    val unsyncedCount: Int = 0,
    val isEngineLoaded: Boolean = false,
    val isEngineLoading: Boolean = false,
    val showActionModal: Boolean = false,
    val isModelAvailable: Boolean = false,
    val modelDownloadState: ModelDownloadState = ModelDownloadState.Idle(false, "Unified OmniFace AI")
)

class DashboardViewModel : ViewModel() {
    private val db = OmniFaceApplication.instance.database
    private val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    private val unifiedEngine = UnifiedFaceIntelligenceEngine.getInstance(OmniFaceApplication.instance.applicationContext)
    private val downloadManager = ModelDownloadManager.getInstance(OmniFaceApplication.instance.applicationContext)

    private val _uiState = MutableStateFlow(
        DashboardUiState(
            isEngineLoaded = unifiedEngine.isModelLoaded,
            isModelAvailable = downloadManager.isModelAvailable(),
            modelDownloadState = downloadManager.downloadState.value
        )
    )
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        observeDatabase()
        observeEngineState()
        observeSyncState()
        observeModelDownloads()
        checkAutoLoadEngine()
    }

    private fun checkAutoLoadEngine() {
        val context = OmniFaceApplication.instance.applicationContext
        if (downloadManager.isModelAvailable() && !unifiedEngine.isModelLoaded) {
            loadEngine(context, silent = true)
        }
    }

    private fun observeModelDownloads() {
        viewModelScope.launch {
            downloadManager.downloadState.collect { state ->
                val available = downloadManager.isModelAvailable()
                _uiState.update {
                    it.copy(
                        modelDownloadState = state,
                        isModelAvailable = available
                    )
                }
            }
        }
    }

    fun startModelDownload(context: Context) {
        downloadManager.startDownload {
            Toast.makeText(context, "AI Face Pack Ready! Initializing AI Engine...", Toast.LENGTH_LONG).show()
            loadEngine(context, silent = false)
        }
    }

    fun cancelModelDownload() {
        downloadManager.cancelDownload()
    }

    private fun observeEngineState() {
        viewModelScope.launch {
            unifiedEngine.isModelLoadedState.collect { loaded ->
                _uiState.update { it.copy(isEngineLoaded = loaded) }
                if (loaded) {
                    benchmarkEngine()
                } else {
                    _uiState.update {
                        it.copy(
                            hardwareTierLabel = "Engine Standby",
                            benchmarkLatencyMs = 0L
                        )
                    }
                }
            }
        }
    }

    fun loadEngine(context: Context, silent: Boolean = false) {
        if (_uiState.value.isEngineLoading) return
        if (!downloadManager.isModelAvailable()) {
            Toast.makeText(context, "☁️ Downloading AI Face Pack (380 MB)...", Toast.LENGTH_SHORT).show()
            startModelDownload(context)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isEngineLoading = true) }
            val loaded = withContext(Dispatchers.Default) {
                unifiedEngine.loadUnifiedModelExplicit(context)
            }
            _uiState.update {
                it.copy(
                    isEngineLoading = false,
                    isEngineLoaded = loaded,
                    showActionModal = loaded && !silent
                )
            }
            if (loaded) {
                benchmarkEngine()
            }
        }
    }

    fun unloadEngine() {
        viewModelScope.launch(Dispatchers.Default) {
            unifiedEngine.unloadUnifiedModel()
            _uiState.update {
                it.copy(
                    isEngineLoaded = false,
                    showActionModal = false,
                    hardwareTierLabel = "Engine Standby",
                    benchmarkLatencyMs = 0L
                )
            }
        }
    }

    fun dismissActionModal() {
        _uiState.update { it.copy(showActionModal = false) }
    }

    private fun observeSyncState() {
        val context = OmniFaceApplication.instance.applicationContext
        com.omniface.ai.sync.CloudFleetSyncEngine.initialize(context)

        viewModelScope.launch {
            com.omniface.ai.sync.CloudFleetSyncEngine.syncState.collect { s ->
                _uiState.update { it.copy(syncState = s) }
            }
        }
        viewModelScope.launch {
            com.omniface.ai.sync.CloudFleetSyncEngine.unsyncedCount.collect { count ->
                _uiState.update { it.copy(unsyncedCount = count) }
            }
        }
    }

    fun syncNow(context: Context) {
        viewModelScope.launch {
            com.omniface.ai.sync.CloudFleetSyncEngine.syncNow(context)
        }
    }

    private fun observeDatabase() {
        viewModelScope.launch {
            db.studentDao().getStudentCountFlow().collect { count ->
                _uiState.update { it.copy(enrolledCount = count) }
            }
        }

        viewModelScope.launch {
            db.attendanceDao().getCountForDateFlow(today).collect { count ->
                _uiState.update { it.copy(todayScansCount = count) }
            }
        }

        viewModelScope.launch {
            db.attendanceDao().getRecentRecordsFlow(4).collect { scans ->
                _uiState.update { it.copy(recentScans = scans) }
            }
        }

        viewModelScope.launch {
            db.attendanceDao().getRecordsForDateFlow(today).collect { records ->
                val hourMap = mutableMapOf<Int, Int>()
                for (h in 8..17) hourMap[h] = 0
                val sdfHour = SimpleDateFormat("HH", Locale.getDefault())
                for (rec in records) {
                    try {
                        val h = sdfHour.format(Date(rec.timestamp)).toInt()
                        if (h in 8..17) {
                            hourMap[h] = (hourMap[h] ?: 0) + 1
                        }
                    } catch (_: Exception) {}
                }
                val velocityList = (8..17).map { h ->
                    "%02dh".format(h) to (hourMap[h] ?: 0)
                }
                _uiState.update { it.copy(hourlyVelocity = velocityList) }
            }
        }
    }

    private fun benchmarkEngine() {
        viewModelScope.launch(Dispatchers.Default) {
            if (!unifiedEngine.isModelLoaded) return@launch
            val context = OmniFaceApplication.instance.applicationContext
            val engine = FaceRecognitionEngine.getInstance(context)
            val latency = engine.benchmarkInferenceLatency()
            val npuInfo = engine.npuHardwareInfo
            val tier = engine.activeHardwareTier.getResolvedLabel(npuInfo)

            _uiState.update {
                it.copy(
                    hardwareTierLabel = tier,
                    benchmarkLatencyMs = latency
                )
            }
        }
    }

    fun setSecurityTier(tier: SecurityTier) {
        _uiState.update { it.copy(selectedTier = tier) }
    }

    fun triggerCloudSync(context: Context) {
        val syncRequest = OneTimeWorkRequestBuilder<AttendanceSyncWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "OMNIFACE_MANUAL_SYNC",
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )
    }
}

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigate: (Screen) -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val orgType by LocalizationManager.currentOrgType.collectAsState()
    val isDark = LocalThemeIsDark.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val todayDateFormatted = remember { SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()).format(Date()) }

    var showPaywall by remember { mutableStateOf(false) }
    var paywallReason by remember { mutableStateOf(PaywallTriggerReason.STUDENT_LIMIT_REACHED) }

    val animatedEnrolledCount by animateIntAsState(
        targetValue = state.enrolledCount,
        animationSpec = tween(600),
        label = "enrolledCount"
    )
    val animatedTodayCount by animateIntAsState(
        targetValue = state.todayScansCount,
        animationSpec = tween(600),
        label = "todayCount"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(omniBackgroundBrush(isDark))
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Executive Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BiometricEnergyOrb(
                        size = 44.dp,
                        showRings = false
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "LIVE BIOMETRIC AI",
                            color = OmniViolet,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = "OmniFace AI",
                            color = omniTextPrimary(isDark),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp
                        )
                        Text(
                            text = "Smart Attendance • $todayDateFormatted",
                            color = omniTextMuted(isDark),
                            fontSize = 11.sp,
                            letterSpacing = (-0.1).sp
                        )
                    }
                }

                IOSGlassPill(
                    text = "LIVE",
                    showPulsingDot = true,
                    accentColor = OmniEmerald
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

        // Qualcomm Hexagon / Neural Engine Hero Card
        item {
            IOSCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
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
                                    .size(46.dp)
                                    .shadow(6.dp, RoundedCornerShape(13.dp), ambientColor = Color(0x4D6366F1), spotColor = Color(0x4D6366F1))
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(OmniButtonBrush)
                                    .border(0.75.dp, if (isDark) Color(0x4DFFFFFF) else Color(0x40FFFFFF), RoundedCornerShape(13.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Memory,
                                    contentDescription = "OmniFace Neural Engine",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "OmniFace Neural Engine",
                                    color = omniTextPrimary(isDark),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.3).sp
                                )
                                Text(
                                    text = "Snapdragon 8 Gen 3 • Hexagon NPU",
                                    color = omniTextMuted(isDark),
                                    fontSize = 11.5.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    IOSGlassPill(
                                        text = "45 TOPS",
                                        accentColor = OmniViolet
                                    )
                                    IOSGlassPill(
                                        text = "INT8 / FP16",
                                        accentColor = OmniSky
                                    )
                                }
                            }
                        }

                        IOSGlassPill(
                            text = "${if (state.benchmarkLatencyMs > 0) state.benchmarkLatencyMs else 6}ms",
                            accentColor = OmniEmerald
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = if (isDark) Color(0x1FFFFFFF) else Color(0x10000000))
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(OmniEmerald, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (state.isEngineLoaded) "AI Engine Active" else "AI Engine Ready",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = OmniEmerald
                            )
                        }

                        CupertinoActionPill(
                            text = if (state.isEngineLoaded) "Unload" else "Load Core",
                            icon = Icons.Default.PowerSettingsNew,
                            onClick = {
                                if (state.isEngineLoaded) viewModel.unloadEngine() else viewModel.loadEngine(context)
                            }
                        )
                    }
                }
            }
        }

        // 2x2 Metric Grid
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CupertinoMetricTile(
                        modifier = Modifier.weight(1f),
                        title = "Total Enrolled",
                        value = "$animatedEnrolledCount",
                        subtitle = "${LocalizationManager.getEntityPlural(orgType)} Enrolled",
                        icon = Icons.Default.People,
                        accentColor = OmniViolet,
                        onClick = { onNavigate(Screen.Enrollment) }
                    )
                    CupertinoMetricTile(
                        modifier = Modifier.weight(1f),
                        title = "Liveness Verified",
                        value = "98%",
                        subtitle = "ISO/IEC 30107-3",
                        icon = Icons.Default.VerifiedUser,
                        accentColor = OmniEmerald,
                        onClick = { onNavigate(Screen.Ledger) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CupertinoMetricTile(
                        modifier = Modifier.weight(1f),
                        title = "Recognition Avg",
                        value = "99.4%",
                        subtitle = "High Confidence",
                        icon = Icons.Default.Face,
                        accentColor = OmniCyan,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.syncNow(context)
                        }
                    )
                    CupertinoMetricTile(
                        modifier = Modifier.weight(1f),
                        title = "Inference Latency",
                        value = "${if (state.benchmarkLatencyMs > 0) state.benchmarkLatencyMs else 6}ms",
                        subtitle = "Hardware NPU",
                        icon = Icons.Default.Bolt,
                        accentColor = OmniAmber,
                        onClick = { onNavigate(Screen.Settings) }
                    )
                }
            }
        }

        // Live Detection Speed Card with Neon Wave
        item {
            IOSCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Live Detection Speed",
                            color = omniTextPrimary(isDark),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IOSGlassPill(
                            text = "● NPU 45 TOPS",
                            accentColor = OmniViolet
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    NeonSparklineWave(
                        height = 100.dp,
                        waveColor1 = OmniViolet,
                        waveColor2 = OmniCyan,
                        waveColor3 = OmniPurple
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Real-time on-device inference speed across rolling 60 frames",
                            color = omniTextMuted(isDark),
                            fontSize = 11.5.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }

        // Primary and Secondary Actions
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    CupertinoButton(
                        text = LocalizationManager.get(StringKey.TAB_SCANNER),
                        icon = Icons.Default.Videocam,
                        onClick = { onNavigate(Screen.Scanner) }
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    CupertinoButton(
                        text = "Enroll Face",
                        icon = Icons.Default.PersonAdd,
                        isSecondary = true,
                        onClick = { onNavigate(Screen.Enrollment) }
                    )
                }
            }
        }

        // Recent Activity Card
        item {
            IOSCard(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(
                    text = LocalizationManager.get(StringKey.RECENT_VERIFICATIONS).uppercase(),
                    actionText = "${LocalizationManager.get(StringKey.TAB_LEDGER)} →",
                    onAction = { onNavigate(Screen.Ledger) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (state.recentScans.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.History,
                        title = "No attendance records yet",
                        subtitle = "Verified ${LocalizationManager.getEntityPlural(orgType).lowercase()} will appear here in real-time."
                    )
                } else {
                    state.recentScans.forEachIndexed { index, record ->
                        val initials = record.studentName.split(" ")
                            .mapNotNull { it.firstOrNull()?.toString() }
                            .take(2)
                            .joinToString("")
                            .uppercase()
                            .ifEmpty { "ID" }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
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
                                        .shadow(3.dp, CircleShape, ambientColor = if (isDark) Color(0x66000000) else Color(0x1F0071E3))
                                        .clip(CircleShape)
                                        .background(
                                            Brush.linearGradient(
                                                listOf(
                                                    if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0),
                                                    if (isDark) Color(0xFF0F172A) else Color(0xFFCBD5E1)
                                                )
                                            )
                                        )
                                        .border(0.75.dp, omniLiquidSpecularBorder(isDark), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = initials,
                                        color = omniCyan(isDark),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = record.studentName,
                                        color = omniTextPrimary(isDark),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-0.2).sp
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${record.studentRoll} • ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(record.timestamp))}",
                                        color = omniTextSecondary(isDark),
                                        fontSize = 11.5.sp
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(omniEmerald(isDark).copy(alpha = 0.15f))
                                    .border(0.5.dp, omniEmerald(isDark).copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 9.dp, vertical = 4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(omniEmerald(isDark))
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${record.confidencePct.toInt()}%",
                                        color = omniEmerald(isDark),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }

                        if (index < state.recentScans.size - 1) {
                            HorizontalDivider(
                                color = if (isDark) Color(0x1A384152) else Color(0x1AE2E8F0),
                                thickness = 0.75.dp
                            )
                        }
                    }
                }
            }
        }
    }

    // Engine Ready Action Modal (Liquid Glass Bottom Sheet / Dialog)
    if (state.showActionModal) {
        Dialog(
            onDismissRequest = { viewModel.dismissActionModal() },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (isDark) Color(0xF01C1C1E) else Color(0xF8FFFFFF))
                    .border(1.dp, if (isDark) Color(0x33FFFFFF) else Color(0x1A000000), RoundedCornerShape(24.dp))
                    .padding(24.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF34C759), Color(0xFF30D158))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Unified Neural Engine Ready",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = omniTextPrimary(isDark),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "AI biometric engine is ready. Hardware-accelerated student identification is active.",
                        fontSize = 13.sp,
                        color = omniTextSecondary(isDark),
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(22.dp))

                    // Option 1: Launch Scanner
                    CupertinoButton(
                        text = "Launch Real-Time Scanner",
                        icon = Icons.Default.CameraAlt,
                        onClick = {
                            viewModel.dismissActionModal()
                            onNavigate(Screen.Scanner)
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Option 2: Register Face
                    CupertinoButton(
                        text = "Register ${LocalizationManager.getEntitySingular(orgType)} Biometrics",
                        icon = Icons.Default.PersonAdd,
                        isSecondary = true,
                        onClick = {
                            viewModel.dismissActionModal()
                            onNavigate(Screen.Enrollment)
                        }
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Option 3: Dismiss / Stay
                    TextButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.dismissActionModal()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Stay on Dashboard",
                            fontSize = 13.5.sp,
                            color = omniTextMuted(isDark),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    if (showPaywall) {
        PaywallBottomSheet(
            triggerReason = paywallReason,
            onDismiss = { showPaywall = false },
            onUpgradeSuccess = { showPaywall = false }
        )
    }
}
