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
import com.omniface.ai.ml.FaceRecognitionEngine
import com.omniface.ai.ml.HardwareTier
import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.sync.AttendanceSyncWorker
import com.omniface.ai.ui.components.*
import com.omniface.ai.ui.navigation.Screen
import com.omniface.ai.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Immutable
data class DashboardUiState(
    val enrolledCount: Int = 0,
    val todayScansCount: Int = 0,
    val hardwareTierLabel: String = "NPU Accelerated",
    val benchmarkLatencyMs: Long = 6L,
    val selectedTier: SecurityTier = SecurityTier.HIGH,
    val recentScans: List<AttendanceRecordEntity> = emptyList(),
    val hourlyVelocity: List<Pair<String, Int>> = listOf(
        "08h" to 0, "09h" to 0, "10h" to 0, "11h" to 0,
        "12h" to 0, "13h" to 0, "14h" to 0, "15h" to 0,
        "16h" to 0, "17h" to 0
    ),
    val syncState: com.omniface.ai.sync.FleetSyncState = com.omniface.ai.sync.FleetSyncState.Idle,
    val unsyncedCount: Int = 0
)

class DashboardViewModel : ViewModel() {
    private val db = OmniFaceApplication.instance.database
    private val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        observeDatabase()
        benchmarkEngine()
        observeSyncState()
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
    val isDark = LocalThemeIsDark.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val todayDateFormatted = remember { SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()).format(Date()) }

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
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .shadow(if (isDark) 6.dp else 8.dp, RoundedCornerShape(14.dp), ambientColor = if (isDark) Color(0x660A84FF) else Color(0x330071E3))
                            .clip(RoundedCornerShape(14.dp))
                            .background(omniLiquidSurfaceBrush(isDark))
                            .border(1.dp, omniLiquidSpecularBorder(isDark), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = com.omniface.ai.R.drawable.app_logo),
                            contentDescription = "OmniFace AI Logo",
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = todayDateFormatted.uppercase(),
                            color = omniTextMuted(isDark),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "OmniFace AI",
                            color = omniTextPrimary(isDark),
                            fontSize = 21.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                IOSGlassPill(
                    text = LocalizationManager.get(StringKey.STATUS_ACTIVE).uppercase(),
                    showPulsingDot = true,
                    accentColor = omniEmerald(isDark)
                )
            }
        }

        // Refined Neural Engine Hero Card (Luxury Titanium Glass)
        item {
            IOSCard(
                modifier = Modifier.fillMaxWidth()
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
                                .size(48.dp)
                                .shadow(
                                    elevation = if (isDark) 6.dp else 8.dp,
                                    shape = RoundedCornerShape(14.dp),
                                    ambientColor = if (isDark) Color(0x660A84FF) else Color(0x330071E3),
                                    spotColor = if (isDark) Color(0x4D0A84FF) else Color(0x260071E3)
                                )
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    Brush.linearGradient(
                                        if (isDark) listOf(Color(0xFF0A84FF), Color(0xFF0055B3))
                                        else listOf(Color(0xFF0071E3), Color(0xFF0050A5))
                                    )
                                )
                                .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = LocalizationManager.get(StringKey.CAT_NEURAL),
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = LocalizationManager.get(StringKey.CAT_NEURAL).uppercase(),
                                color = omniTextMuted(isDark),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = state.hardwareTierLabel,
                                color = omniTextPrimary(isDark),
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.2).sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "ArcFace 512-D • AES-256 GCM",
                                color = omniTextSecondary(isDark),
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                    IOSGlassPill(
                        text = "${state.benchmarkLatencyMs}ms",
                        accentColor = omniCyan(isDark),
                        showPulsingDot = true
                    )
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
                        title = LocalizationManager.get(StringKey.TAB_STUDENTS),
                        value = "$animatedEnrolledCount",
                        subtitle = LocalizationManager.get(StringKey.STUDENTS_ENROLLED),
                        icon = Icons.Default.People,
                        accentColor = omniCyan(isDark),
                        onClick = { onNavigate(Screen.Enrollment) }
                    )
                    CupertinoMetricTile(
                        modifier = Modifier.weight(1f),
                        title = LocalizationManager.get(StringKey.ATTENDANCE_TODAY),
                        value = "$animatedTodayCount",
                        subtitle = LocalizationManager.get(StringKey.VERIFIED_BADGE),
                        icon = Icons.Default.CheckCircle,
                        accentColor = omniEmerald(isDark),
                        onClick = { onNavigate(Screen.Ledger) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val syncTileValue = when (val s = state.syncState) {
                        is com.omniface.ai.sync.FleetSyncState.Syncing -> "Syncing..."
                        is com.omniface.ai.sync.FleetSyncState.Synced -> "Fleet Synced"
                        is com.omniface.ai.sync.FleetSyncState.OfflineReady -> {
                            if (state.unsyncedCount > 0) "${state.unsyncedCount} Pending" else "Fleet Ready"
                        }
                        is com.omniface.ai.sync.FleetSyncState.Error -> "Sync Error"
                        else -> if (state.unsyncedCount > 0) "${state.unsyncedCount} Pending" else "Fleet Ready"
                    }
                    val syncTileSubtitle = when (val s = state.syncState) {
                        is com.omniface.ai.sync.FleetSyncState.Syncing -> s.message
                        is com.omniface.ai.sync.FleetSyncState.Synced -> "${s.peerNodeCount} Node • Synced"
                        is com.omniface.ai.sync.FleetSyncState.OfflineReady -> if (state.unsyncedCount > 0) "Tap to Sync Now" else "P2P Mesh Online"
                        is com.omniface.ai.sync.FleetSyncState.Error -> s.error.take(24)
                        else -> "Tap to Sync Now"
                    }
                    val syncTileColor = when (state.syncState) {
                        is com.omniface.ai.sync.FleetSyncState.Synced -> omniEmerald(isDark)
                        is com.omniface.ai.sync.FleetSyncState.Syncing -> if (isDark) AmberCore else LightAmberCore
                        else -> omniCyan(isDark)
                    }

                    CupertinoMetricTile(
                        modifier = Modifier.weight(1f),
                        title = "CLOUD SYNC",
                        value = syncTileValue,
                        subtitle = syncTileSubtitle,
                        icon = Icons.Default.CloudSync,
                        accentColor = syncTileColor,
                        onClick = {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            viewModel.syncNow(context)
                        }
                    )
                    CupertinoMetricTile(
                        modifier = Modifier.weight(1f),
                        title = LocalizationManager.get(StringKey.DECISION_TIER_SETTING),
                        value = when (state.selectedTier) {
                            SecurityTier.STANDARD -> LocalizationManager.get(StringKey.TIER_STANDARD)
                            SecurityTier.HIGH -> LocalizationManager.get(StringKey.TIER_HIGH)
                            SecurityTier.STRICT -> LocalizationManager.get(StringKey.TIER_STRICT)
                        },
                        subtitle = LocalizationManager.get(StringKey.STATUS_ACTIVE),
                        icon = Icons.Default.Security,
                        accentColor = if (isDark) AmberCore else LightAmberCore,
                        onClick = { onNavigate(Screen.Settings) }
                    )
                }
            }
        }

        // Hourly Check-In Velocity Chart Card
        item {
            IOSCard(modifier = Modifier.fillMaxWidth()) {
                val totalActivity = state.hourlyVelocity.sumOf { it.second }
                val maxVelocity = state.hourlyVelocity.maxOfOrNull { it.second } ?: 0
                var selectedIndex by remember { mutableStateOf<Int?>(null) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = LocalizationManager.get(StringKey.VERIFICATION_SPEED).uppercase(),
                        color = omniTextMuted(isDark),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )

                    val activeLabel = if (selectedIndex != null && selectedIndex in state.hourlyVelocity.indices) {
                        val item = state.hourlyVelocity[selectedIndex!!]
                        "${item.first}: ${item.second} verifications"
                    } else if (totalActivity > 0) {
                        "Peak: ${maxVelocity}/h"
                    } else null

                    if (activeLabel != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(omniCyan(isDark).copy(alpha = 0.18f))
                                .border(0.5.dp, omniCyan(isDark).copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(activeLabel, color = omniCyan(isDark), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (totalActivity == 0) {
                    EmptyState(
                        icon = Icons.Default.ShowChart,
                        title = "No attendance activity yet",
                        subtitle = "Your first verification will appear here."
                    )
                } else {
                    val data = state.hourlyVelocity
                    val cyanColor = omniCyan(isDark)
                    val emeraldColor = omniEmerald(isDark)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(115.dp)
                            .pointerInput(data) {
                                detectTapGestures(
                                    onPress = { offset ->
                                        val stepX = size.width / (data.size - 1)
                                        val idx = (offset.x / stepX).toInt().coerceIn(0, data.size - 1)
                                        selectedIndex = idx
                                    },
                                    onTap = { selectedIndex = null }
                                )
                            }
                            .pointerInput(data) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val stepX = size.width / (data.size - 1)
                                        val idx = (offset.x / stepX).toInt().coerceIn(0, data.size - 1)
                                        selectedIndex = idx
                                    },
                                    onDragEnd = { selectedIndex = null },
                                    onDragCancel = { selectedIndex = null },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        val stepX = size.width / (data.size - 1)
                                        val idx = (change.position.x / stepX).toInt().coerceIn(0, data.size - 1)
                                        selectedIndex = idx
                                    }
                                )
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val width = size.width
                            val height = size.height
                            val maxVal = (maxVelocity.toFloat()).coerceAtLeast(10f)
                            val stepX = width / (data.size - 1)

                            val points = data.mapIndexed { idx, item ->
                                val x = idx * stepX
                                val y = height - (item.second / maxVal * (height - 24f)) - 12f
                                Offset(x, y)
                            }

                            val fillPath = Path().apply {
                                moveTo(0f, height)
                                lineTo(points.first().x, points.first().y)
                                for (i in 0 until points.size - 1) {
                                    val p0 = points[i]
                                    val p1 = points[i + 1]
                                    val cx = (p0.x + p1.x) / 2
                                    cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                                }
                                lineTo(width, height)
                                close()
                            }

                            drawPath(
                                path = fillPath,
                                brush = Brush.verticalGradient(
                                    listOf(cyanColor.copy(alpha = if (isDark) 0.35f else 0.22f), Color.Transparent)
                                )
                            )

                            val strokePath = Path().apply {
                                moveTo(points.first().x, points.first().y)
                                for (i in 0 until points.size - 1) {
                                    val p0 = points[i]
                                    val p1 = points[i + 1]
                                    val cx = (p0.x + p1.x) / 2
                                    cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                                }
                            }

                            drawPath(
                                path = strokePath,
                                brush = Brush.horizontalGradient(listOf(cyanColor, emeraldColor)),
                                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                            )

                            // Scrubber Vertical Guideline
                            selectedIndex?.let { idx ->
                                if (idx in points.indices) {
                                    val pt = points[idx]
                                    drawLine(
                                        color = cyanColor.copy(alpha = 0.6f),
                                        start = Offset(pt.x, 0f),
                                        end = Offset(pt.x, height),
                                        strokeWidth = 1.5.dp.toPx()
                                    )
                                }
                            }

                            points.forEachIndexed { idx, pt ->
                                val isSelected = idx == selectedIndex
                                drawCircle(
                                    color = if (isDark) Color(0xFF0F172A) else Color.White,
                                    radius = (if (isSelected) 6.dp else 4.dp).toPx(),
                                    center = pt
                                )
                                drawCircle(
                                    color = if (isSelected) emeraldColor else cyanColor,
                                    radius = (if (isSelected) 4.dp else 2.5.dp).toPx(),
                                    center = pt
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        data.forEachIndexed { idx, item ->
                            val isSelected = idx == selectedIndex
                            Text(
                                text = item.first,
                                color = if (isSelected) omniCyan(isDark) else omniTextMuted(isDark),
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold
                            )
                        }
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
                        subtitle = "Verified students will appear here in real-time."
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
}
