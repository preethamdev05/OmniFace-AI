package com.omniface.ai.ui.dedup

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omniface.ai.OmniFaceApplication
import com.omniface.ai.data.local.entity.StudentEntity
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey
import com.omniface.ai.ml.recognition.BiometricDeduplicationEngine
import com.omniface.ai.ml.recognition.DuplicateCluster
import com.omniface.ai.security.findFragmentActivity
import com.omniface.ai.ui.components.*
import com.omniface.ai.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class DeduplicationUiState(
    val isScanning: Boolean = false,
    val hasScanned: Boolean = false,
    val scannedIdentityCount: Int = 0,
    val duplicateClusters: List<DuplicateCluster> = emptyList(),
    val similarityThreshold: Float = 0.80f,
    val resolvedClustersCount: Int = 0,
    val isProcessingAction: Boolean = false
)

class DeduplicationViewModel : ViewModel() {
    private val db = OmniFaceApplication.instance.database
    private val _uiState = MutableStateFlow(DeduplicationUiState())
    val uiState: StateFlow<DeduplicationUiState> = _uiState.asStateFlow()

    fun runDeduplicationScan() {
        if (_uiState.value.isScanning || _uiState.value.isProcessingAction) return
        _uiState.update { it.copy(isScanning = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val students = db.studentDao().getAllStudents()
            val templates = db.studentDao().getAllTemplates()
            val studentMap = students.associate { it.rollNumber to it.fullName }

            val clusters = BiometricDeduplicationEngine.scanDatabaseForDuplicates(
                templates = templates,
                studentMap = studentMap,
                threshold = _uiState.value.similarityThreshold
            )

            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        hasScanned = true,
                        scannedIdentityCount = students.size,
                        duplicateClusters = clusters
                    )
                }
            }
        }
    }

    fun mergeDuplicateRecords(primaryRoll: String, candidateRoll: String) {
        if (_uiState.value.isProcessingAction) return
        _uiState.update { it.copy(isProcessingAction = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val candidateTemplates = db.studentDao().getTemplatesForStudent(candidateRoll)
                val primaryTemplates = db.studentDao().getTemplatesForStudent(primaryRoll)
                val primaryAngles = primaryTemplates.map { it.angleType }.toSet()

                val templatesToMigrate = candidateTemplates.filter { it.angleType !in primaryAngles }
                    .map { it.copy(id = UUID.randomUUID().toString(), studentRoll = primaryRoll) }
                if (templatesToMigrate.isNotEmpty()) {
                    db.studentDao().insertTemplates(templatesToMigrate)
                }

                db.studentDao().deleteStudentByRoll(candidateRoll)
                db.studentDao().deleteTemplatesForStudent(candidateRoll)

                withContext(Dispatchers.Main) {
                    _uiState.update { current ->
                        val updated = current.duplicateClusters.mapNotNull { cluster ->
                            val remaining = cluster.duplicateCandidates.filter { it.rollNumber != candidateRoll }
                            if (remaining.isEmpty()) null else cluster.copy(duplicateCandidates = remaining)
                        }
                        current.copy(
                            duplicateClusters = updated,
                            resolvedClustersCount = current.resolvedClustersCount + 1,
                            isProcessingAction = false
                        )
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isProcessingAction = false) }
                }
            }
        }
    }

    fun unlinkCandidate(primaryRoll: String, candidateRoll: String) {
        _uiState.update { current ->
            val updated = current.duplicateClusters.mapNotNull { cluster ->
                if (cluster.primaryRoll == primaryRoll) {
                    val remaining = cluster.duplicateCandidates.filter { it.rollNumber != candidateRoll }
                    if (remaining.isEmpty()) null else cluster.copy(duplicateCandidates = remaining)
                } else {
                    cluster
                }
            }
            current.copy(
                duplicateClusters = updated,
                resolvedClustersCount = current.resolvedClustersCount + 1
            )
        }
    }

    fun deleteDuplicateRecord(candidateRoll: String) {
        if (_uiState.value.isProcessingAction) return
        _uiState.update { it.copy(isProcessingAction = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.studentDao().deleteStudentByRoll(candidateRoll)
                db.studentDao().deleteTemplatesForStudent(candidateRoll)
                withContext(Dispatchers.Main) {
                    _uiState.update { current ->
                        val updated = current.duplicateClusters.mapNotNull { cluster ->
                            val remaining = cluster.duplicateCandidates.filter { it.rollNumber != candidateRoll }
                            if (remaining.isEmpty()) null else cluster.copy(duplicateCandidates = remaining)
                        }
                        current.copy(
                            duplicateClusters = updated,
                            resolvedClustersCount = current.resolvedClustersCount + 1,
                            isProcessingAction = false
                        )
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isProcessingAction = false) }
                }
            }
        }
    }

    fun dismissCluster(primaryRoll: String) {
        _uiState.update { current ->
            current.copy(
                duplicateClusters = current.duplicateClusters.filter { it.primaryRoll != primaryRoll },
                resolvedClustersCount = current.resolvedClustersCount + 1
            )
        }
    }
}

@Composable
fun BiometricDeduplicationStudio(
    viewModel: DeduplicationViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onClose: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isDark = LocalThemeIsDark.current
    val context = LocalContext.current

    BackHandler {
        onClose()
    }

    LaunchedEffect(Unit) {
        if (!state.hasScanned) {
            viewModel.runDeduplicationScan()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(omniBackgroundBrush(isDark))
    ) {
        // Studio Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF59E0B).copy(alpha = if (isDark) 0.25f else 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FindReplace,
                        contentDescription = "Dedup",
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = LocalizationManager.get(StringKey.DEDUP_STUDIO_TITLE),
                        color = omniTextPrimary(isDark),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = LocalizationManager.get(StringKey.DEDUP_STUDIO_SUBTITLE),
                        color = omniTextMuted(isDark),
                        fontSize = 12.sp
                    )
                }
            }

            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = omniTextPrimary(isDark))
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Telemetry KPI Banner
            item {
                IOSCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "${state.scannedIdentityCount} ${LocalizationManager.get(StringKey.DEDUP_ENROLLED_COUNT)}",
                                color = omniTextPrimary(isDark),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (state.isScanning) LocalizationManager.get(StringKey.DEDUP_SCANNING)
                                else if (state.duplicateClusters.isEmpty()) "✓ ${LocalizationManager.get(StringKey.DEDUP_NO_DUPLICATES)}"
                                else "⚠️ ${state.duplicateClusters.size} ${LocalizationManager.get(StringKey.DEDUP_FOUND_CLUSTERS)}",
                                color = if (state.duplicateClusters.isEmpty()) Color(0xFF34C759) else Color(0xFFF59E0B),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        CupertinoActionPill(
                            text = if (state.isScanning) "Scanning..." else LocalizationManager.get(StringKey.DEDUP_RESCAN_BUTTON),
                            icon = Icons.Default.Refresh,
                            onClick = { viewModel.runDeduplicationScan() }
                        )
                    }
                }
            }

            // 2. Scan in Progress Indicator
            if (state.isScanning) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF38BDF8))
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = LocalizationManager.get(StringKey.DEDUP_SCANNING),
                                color = omniTextMuted(isDark),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            } else if (state.duplicateClusters.isEmpty()) {
                // Empty State — Clean Database in Glass Card
                item {
                    IOSCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                        EmptyState(
                            title = LocalizationManager.get(StringKey.DEDUP_NO_DUPLICATES),
                            subtitle = LocalizationManager.get(StringKey.DEDUP_CLEAN_DESC),
                            icon = Icons.Default.VerifiedUser
                        )
                    }
                }
            } else {
                // Section Header
                item {
                    Text(
                        text = "${LocalizationManager.get(StringKey.DEDUP_COLLISIONS_HEADER)} (${state.duplicateClusters.size})",
                        color = omniTextMuted(isDark),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                    )
                }

                // 3. Duplicate Clusters List
                items(state.duplicateClusters) { cluster ->
                    IOSCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Primary Identity Header
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF38BDF8).copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = cluster.primaryName.take(1).uppercase(),
                                            color = Color(0xFF38BDF8),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = cluster.primaryName,
                                            color = omniTextPrimary(isDark),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Roll: ${cluster.primaryRoll} • ${LocalizationManager.get(StringKey.DEDUP_PRIMARY_LABEL)}",
                                            color = omniTextMuted(isDark),
                                            fontSize = 11.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                IOSGlassPill(
                                    text = LocalizationManager.get(StringKey.DEDUP_PRIMARY_LABEL),
                                    accentColor = Color(0xFF38BDF8)
                                )
                            }

                            HorizontalDivider(color = if (isDark) Color(0x38FFFFFF) else Color(0x1A000000))

                            // Duplicate Candidates
                            cluster.duplicateCandidates.forEach { candidate ->
                                val simPct = (candidate.similarityScore * 100).toInt().coerceIn(0, 99)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (isDark) Color(0x261E293B) else Color(0xFFF8FAFC)
                                        )
                                        .border(
                                            width = 0.75.dp,
                                            color = Color(0xFFFF3B30).copy(alpha = 0.35f),
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .padding(14.dp)
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(34.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0xFFFF3B30).copy(alpha = 0.15f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = candidate.fullName.take(1).uppercase(),
                                                        color = Color(0xFFFF3B30),
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = candidate.fullName,
                                                        color = omniTextPrimary(isDark),
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = "Roll: ${candidate.rollNumber} • ${candidate.matchedAngle}",
                                                        color = omniTextMuted(isDark),
                                                        fontSize = 11.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }

                                            IOSGlassPill(
                                                text = "$simPct% ${LocalizationManager.get(StringKey.DEDUP_MATCH_LABEL)}",
                                                accentColor = Color(0xFFFF3B30)
                                            )
                                        }

                                        // Similarity Delta Bar
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            LinearProgressIndicator(
                                                progress = { candidate.similarityScore.coerceIn(0f, 1f) },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(4.dp)
                                                    .clip(RoundedCornerShape(2.dp)),
                                                color = Color(0xFFFF3B30),
                                                trackColor = if (isDark) Color(0x22FFFFFF) else Color(0x14000000)
                                            )
                                        }

                                        // Action Button Row 1: Unlink & Merge
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Unlink
                                            OutlinedButton(
                                                onClick = {
                                                    viewModel.unlinkCandidate(cluster.primaryRoll, candidate.rollNumber)
                                                    Toast.makeText(context, "${candidate.fullName} unlinked", Toast.LENGTH_SHORT).show()
                                                },
                                                enabled = !state.isProcessingAction && !state.isScanning,
                                                modifier = Modifier.weight(1f).height(38.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                shape = RoundedCornerShape(10.dp),
                                                border = BorderStroke(0.75.dp, omniTextMuted(isDark).copy(alpha = 0.4f))
                                            ) {
                                                Text(
                                                    text = LocalizationManager.get(StringKey.DEDUP_UNLINK_ACTION),
                                                    color = omniTextPrimary(isDark),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            // Merge with Biometric Auth
                                            Button(
                                                onClick = {
                                                    val act = context.findFragmentActivity()
                                                    if (act != null && com.omniface.ai.security.DeviceBiometricAuthManager.canAuthenticate(context)) {
                                                        com.omniface.ai.security.DeviceBiometricAuthManager.authenticate(
                                                            activity = act,
                                                            title = "Authorize Biometric Merge",
                                                            subtitle = "Authenticate to merge candidate into primary profile",
                                                            onSuccess = {
                                                                viewModel.mergeDuplicateRecords(cluster.primaryRoll, candidate.rollNumber)
                                                                Toast.makeText(context, "Merged ${candidate.fullName} into ${cluster.primaryName}", Toast.LENGTH_SHORT).show()
                                                            },
                                                            onError = { err ->
                                                                Toast.makeText(context, "Authentication Failed: $err", Toast.LENGTH_SHORT).show()
                                                            }
                                                        )
                                                    } else {
                                                        viewModel.mergeDuplicateRecords(cluster.primaryRoll, candidate.rollNumber)
                                                        Toast.makeText(context, "Merged ${candidate.fullName} into ${cluster.primaryName}", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                enabled = !state.isProcessingAction && !state.isScanning,
                                                modifier = Modifier.weight(1f).height(38.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                shape = RoundedCornerShape(10.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color(0xFF38BDF8).copy(alpha = 0.25f),
                                                    contentColor = Color(0xFF38BDF8)
                                                )
                                            ) {
                                                Text(
                                                    text = LocalizationManager.get(StringKey.DEDUP_MERGE_ACTION),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        // Action Button Row 2: Delete Duplicate Profile with Biometric Auth
                                        Button(
                                            onClick = {
                                                val act = context.findFragmentActivity()
                                                if (act != null && com.omniface.ai.security.DeviceBiometricAuthManager.canAuthenticate(context)) {
                                                    com.omniface.ai.security.DeviceBiometricAuthManager.authenticate(
                                                        activity = act,
                                                        title = "Authorize Biometric Deletion",
                                                        subtitle = "Scan fingerprint or screen lock to delete duplicate profile",
                                                        onSuccess = {
                                                            viewModel.deleteDuplicateRecord(candidate.rollNumber)
                                                            Toast.makeText(context, "Deleted duplicate profile ${candidate.fullName}", Toast.LENGTH_SHORT).show()
                                                        },
                                                        onError = { err ->
                                                            Toast.makeText(context, "Authentication Failed: $err", Toast.LENGTH_SHORT).show()
                                                        }
                                                    )
                                                } else {
                                                    viewModel.deleteDuplicateRecord(candidate.rollNumber)
                                                    Toast.makeText(context, "Deleted duplicate profile ${candidate.fullName}", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            enabled = !state.isProcessingAction && !state.isScanning,
                                            modifier = Modifier.fillMaxWidth().height(38.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFFFF3B30).copy(alpha = 0.18f),
                                                contentColor = Color(0xFFFF3B30)
                                            )
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = null,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = LocalizationManager.get(StringKey.DEDUP_PURGE_RECORD),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }

                            // Dismiss Cluster Action
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { viewModel.dismissCluster(cluster.primaryRoll) },
                                    modifier = Modifier.height(32.dp).widthIn(max = 200.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = LocalizationManager.get(StringKey.DEDUP_DISMISS_ACTION),
                                        color = omniTextMuted(isDark),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

