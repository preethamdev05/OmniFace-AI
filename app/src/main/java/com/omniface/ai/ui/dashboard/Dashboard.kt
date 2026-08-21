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
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    )
)

class DashboardViewModel : ViewModel() {
    private val db = OmniFaceApplication.instance.database
    private val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        observeDatabase()
        benchmarkEngine()
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
            val engine = FaceRecognitionEngine(context)
            val latency = engine.benchmarkInferenceLatency()
            val npuInfo = engine.npuHardwareInfo
            val tier = if (engine.activeHardwareTier == HardwareTier.NPU_NNAPI) {
                if (npuInfo.isGenuineNpuDetected) {
                    when {
                        npuInfo.npuName.contains("Hexagon") -> "Hexagon NPU (${npuInfo.peakTops})"
                        npuInfo.npuName.contains("Tensor") -> "Tensor TPU (${npuInfo.peakTops})"
                        npuInfo.npuName.contains("APU") -> "NeuroPilot APU (${npuInfo.peakTops})"
                        else -> "NPU Accelerated (${npuInfo.peakTops})"
                    }
                } else "NPU Accelerated"
            } else engine.activeHardwareTier.label
            engine.close()

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
    val todayDateFormatted = remember { SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date()) }

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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .shadow(if (isDark) 6.dp else 8.dp, RoundedCornerShape(15.dp), ambientColor = if (isDark) Color(0x660A84FF) else Color(0x330071E3))
                            .clip(RoundedCornerShape(15.dp))
                            .background(omniLiquidSurfaceBrush(isDark))
                            .border(1.dp, omniLiquidSpecularBorder(isDark), RoundedCornerShape(15.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = com.omniface.ai.R.drawable.app_logo),
                            contentDescription = "OmniFace AI Logo",
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(13.dp))
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = todayDateFormatted.uppercase(),
                            color = omniTextMuted(isDark),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "OmniFace AI",
                            color = omniTextPrimary(isDark),
                            fontSize = 27.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .shadow(4.dp, RoundedCornerShape(999.dp), ambientColor = Color(0x3330D158), spotColor = Color(0x2630D158))
                            .clip(RoundedCornerShape(999.dp))
                            .background(omniEmerald(isDark).copy(alpha = if (isDark) 0.16f else 0.12f))
                            .border(0.75.dp, omniEmerald(isDark).copy(alpha = if (isDark) 0.45f else 0.30f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(omniEmerald(isDark))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "OPERATIONAL",
                            color = omniEmerald(isDark),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.6.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .shadow(if (isDark) 4.dp else 6.dp, CircleShape, ambientColor = if (isDark) Color(0x66000000) else Color(0x1F000000))
                            .clip(CircleShape)
                            .background(omniLiquidSurfaceBrush(isDark))
                            .border(0.75.dp, omniLiquidSpecularBorder(isDark), CircleShape)
                            .clickable { onOpenSettings() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = omniTextPrimary(isDark),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }

        // Refined Neural Engine Hero Card (Luxury Titanium Glass)
        item {
            IOSCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenSettings
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
                                .size(44.dp)
                                .shadow(4.dp, RoundedCornerShape(13.dp), ambientColor = Color(0x330A84FF))
                                .clip(RoundedCornerShape(13.dp))
                                .background(omniCyan(isDark).copy(alpha = if (isDark) 0.18f else 0.12f))
                                .border(0.75.dp, omniCyan(isDark).copy(alpha = if (isDark) 0.40f else 0.25f), RoundedCornerShape(13.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = omniCyan(isDark),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "NEURAL ENGINE",
                                    color = omniTextMuted(isDark),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.6.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(omniCyan(isDark).copy(alpha = 0.20f))
                                        .padding(horizontal = 5.dp, vertical = 1.5.dp)
                                ) {
                                    Text(
                                        text = "${state.benchmarkLatencyMs}ms",
                                        color = omniCyan(isDark),
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = state.hardwareTierLabel,
                                color = omniTextPrimary(isDark),
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.2).sp
                            )
                            Text(
                                text = "ArcFace 512-D • Hardware KeyStore AES-256",
                                color = omniTextSecondary(isDark),
                                fontSize = 11.5.sp
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(omniCyan(isDark).copy(alpha = 0.12f))
                            .border(0.5.dp, omniCyan(isDark).copy(alpha = 0.30f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Specs →",
                            color = omniCyan(isDark),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
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
                        title = "Enrolled",
                        value = "$animatedEnrolledCount",
                        subtitle = "Identities",
                        icon = Icons.Default.People,
                        accentColor = omniCyan(isDark),
                        onClick = { onNavigate(Screen.Enrollment) }
                    )
                    CupertinoMetricTile(
                        modifier = Modifier.weight(1f),
                        title = "Today",
                        value = "$animatedTodayCount",
                        subtitle = "Verified",
                        icon = Icons.Default.CheckCircle,
                        accentColor = omniEmerald(isDark),
                        onClick = { onNavigate(Screen.Ledger) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CupertinoMetricTile(
                        modifier = Modifier.weight(1f),
                        title = "Cloud State",
                        value = "Sync",
                        subtitle = "Aegis Chain",
                        icon = Icons.Default.CloudQueue,
                        accentColor = omniCyan(isDark),
                        onClick = { viewModel.triggerCloudSync(context) }
                    )
                    CupertinoMetricTile(
                        modifier = Modifier.weight(1f),
                        title = "Security Gate",
                        value = state.selectedTier.name.lowercase().replaceFirstChar { it.uppercase() },
                        subtitle = "Active Gate",
                        icon = Icons.Default.Security,
                        accentColor = if (isDark) AmberCore else LightAmberCore,
                        onClick = onOpenSettings
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
                        text = "HOURLY CHECK-IN VELOCITY",
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
                        icon = Icons.AutoMirrored.Filled.ShowChart,
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
                                    listOf(cyanColor.copy(alpha = 0.35f), Color.Transparent)
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
                Box(modifier = Modifier.weight(1.2f)) {
                    CupertinoButton(
                        text = "📹 Scan Attendance",
                        onClick = { onNavigate(Screen.Scanner) }
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    CupertinoButton(
                        text = "➕ Enroll",
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
                    text = "RECENT VERIFICATIONS",
                    actionText = "Full Ledger →",
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
