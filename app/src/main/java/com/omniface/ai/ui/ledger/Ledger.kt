package com.omniface.ai.ui.ledger

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omniface.ai.OmniFaceApplication
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.ui.components.*
import com.omniface.ai.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Immutable
data class LedgerUiState(
    val allRecords: List<AttendanceRecordEntity> = emptyList(),
    val displayedRecords: List<AttendanceRecordEntity> = emptyList(),
    val searchQuery: String = "",
    val activeFilter: String = "ALL", // ALL, TODAY, SYNCED, LOCAL
    val selectedRecordForProof: AttendanceRecordEntity? = null
)

class LedgerViewModel : ViewModel() {
    private val db = OmniFaceApplication.instance.database
    private val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private val _uiState = MutableStateFlow(LedgerUiState())
    val uiState: StateFlow<LedgerUiState> = _uiState.asStateFlow()

    init {
        observeRecords()
    }

    private fun observeRecords() {
        viewModelScope.launch {
            db.attendanceDao().getAllRecordsFlow().collect { records ->
                _uiState.update {
                    it.copy(
                        allRecords = records,
                        displayedRecords = filterList(records, it.searchQuery, it.activeFilter)
                    )
                }
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query,
                displayedRecords = filterList(it.allRecords, query, it.activeFilter)
            )
        }
    }

    fun onFilterSelected(filter: String) {
        _uiState.update {
            it.copy(
                activeFilter = filter,
                displayedRecords = filterList(it.allRecords, it.searchQuery, filter)
            )
        }
    }

    fun selectRecordForProof(record: AttendanceRecordEntity?) {
        _uiState.update { it.copy(selectedRecordForProof = record) }
    }

    private fun filterList(
        list: List<AttendanceRecordEntity>,
        query: String,
        filter: String
    ): List<AttendanceRecordEntity> {
        val q = query.trim().lowercase(Locale.getDefault())
        return list.filter { r ->
            val matchesQuery = if (q.isEmpty()) true else {
                r.studentName.lowercase(Locale.getDefault()).contains(q) ||
                r.studentRoll.lowercase(Locale.getDefault()).contains(q)
            }

            val matchesFilter = when (filter) {
                "TODAY" -> r.sessionDate == todayDate
                "SYNCED" -> r.isSynced
                "LOCAL" -> !r.isSynced
                else -> true
            }

            matchesQuery && matchesFilter
        }
    }

    fun dispatchParentAlert(context: Context, record: AttendanceRecordEntity) {
        val timeFormatted = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(record.timestamp))
        val message = "🏛️ OmniFace AI Biometric Notice:\n" +
                "Student: ${record.studentName} (${record.studentRoll})\n" +
                "Status: Verified Check-in\n" +
                "Date: ${record.sessionDate} at $timeFormatted\n" +
                "Gate: Main Kiosk Alpha\n" +
                "Aegis Blockchain Proof: ${record.sha256Hash.take(16)}..."

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, message)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Send Parent Attendance Notice")
        shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(shareIntent)
    }

    fun exportAuditCsv(context: Context, records: List<AttendanceRecordEntity>) {
        if (records.isEmpty()) {
            Toast.makeText(context, "No attendance records to export", Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val filename = "OmniFace_Audit_Ledger_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.csv"
                val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
                val exportFile = File(exportDir, filename)
                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

                FileWriter(exportFile).use { writer ->
                    writer.append("Record_ID,Roll_Number,Full_Name,Date,Time,Confidence_Score,Security_Tier,Aegis_SHA256_Proof,Sync_Status\n")
                    for (r in records) {
                        val timeStr = timeFormat.format(Date(r.timestamp))
                        writer.append("${r.recordId},\"${r.studentRoll}\",\"${r.studentName}\",${r.sessionDate},$timeStr,${r.confidencePct}%,${r.securityTier},${r.sha256Hash},${if (r.isSynced) "SYNCED_CLOUD" else "LOCAL_STORED"}\n")
                    }
                }

                val contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    exportFile
                )

                withContext(Dispatchers.Main) {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_SUBJECT, "OmniFace AI Verified Biometric Ledger")
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Export Biometric Audit Ledger"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Export Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@Composable
fun LedgerScreen(
    viewModel: LedgerViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isDark = LocalThemeIsDark.current
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(omniBackgroundBrush(isDark))
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Large iOS Title Header & Action
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "FORENSIC AUDIT",
                        color = omniTextMuted(isDark),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Audit Ledger",
                        color = omniTextPrimary(isDark),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                // Export CSV / Report Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(omniCyan(isDark).copy(alpha = 0.15f))
                        .border(1.dp, omniCyan(isDark).copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                        .clickable { viewModel.exportAuditCsv(context, state.displayedRecords) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Export CSV",
                            tint = omniCyan(isDark),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "EXPORT",
                            color = omniCyan(isDark),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // iOS Inset Search Bar
        item {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                placeholder = { Text("Search by name or roll number...", color = omniTextMuted(isDark), fontSize = 13.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = omniTextMuted(isDark),
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = omniTextMuted(isDark), modifier = Modifier.size(16.dp))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = omniTextPrimary(isDark),
                    unfocusedTextColor = omniTextPrimary(isDark),
                    focusedBorderColor = omniCyan(isDark),
                    unfocusedBorderColor = if (isDark) Color(0x29FFFFFF) else Color(0xFFCBD5E1),
                    focusedContainerColor = if (isDark) Color(0x1F1E293B) else Color(0xFFFFFFFF),
                    unfocusedContainerColor = if (isDark) Color(0x1F1E293B) else Color(0xFFFFFFFF)
                )
            )
        }

        // Filter Pills Row
        item {
            val filterOptions = listOf(
                "ALL" to "All (${state.allRecords.size})",
                "TODAY" to "Today",
                "SYNCED" to "Synced ☁",
                "LOCAL" to "Local 🔒"
            )

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filterOptions) { (key, label) ->
                    val isSelected = state.activeFilter == key
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (isSelected) omniCyan(isDark) else (if (isDark) Color(0x1F1E293B) else Color(0xFFE2E8F0))
                            )
                            .clickable { viewModel.onFilterSelected(key) }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.White else omniTextSecondary(isDark),
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Empty State or List of Records
        if (state.displayedRecords.isEmpty()) {
            item {
                IOSCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.ReceiptLong,
                        title = "No Audit Records",
                        subtitle = "Attendance verifications will appear here with their cryptographic proof."
                    )
                }
            }
        } else {
            items(
                items = state.displayedRecords,
                key = { it.recordId },
                contentType = { "attendance_record" }
            ) { record ->
                val timeStr = timeFormat.format(Date(record.timestamp))
                FrostedGlassCard(
                    modifier = Modifier.fillMaxWidth()
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
                            // Initial Avatar
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(omniCyan(isDark).copy(alpha = 0.15f))
                                    .border(1.dp, omniCyan(isDark).copy(alpha = 0.35f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = record.studentName.take(1).uppercase(),
                                    color = omniCyan(isDark),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = record.studentName,
                                    color = omniTextPrimary(isDark),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = record.studentRoll,
                                        color = omniCyan(isDark),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = " • ${record.sessionDate} $timeStr",
                                        color = omniTextMuted(isDark),
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // One-Click Parent Alert Dispatcher
                            IconButton(
                                onClick = { viewModel.dispatchParentAlert(context, record) },
                                modifier = Modifier
                                    .size(32.dp)
                                    .semantics { contentDescription = "Notify Parent" }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Chat,
                                    contentDescription = null,
                                    tint = omniCyan(isDark),
                                    modifier = Modifier.size(18.dp)
                                )

                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            // Aegis Proof Chip
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (record.isSynced) omniEmerald(isDark).copy(alpha = 0.15f) else Color(0x1F1E293B))
                                    .clickable { viewModel.selectRecordForProof(record) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (record.isSynced) "☁ Proof" else "🔒 Local",
                                    color = if (record.isSynced) omniEmerald(isDark) else omniTextSecondary(isDark),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Aegis Blockchain Proof Modal Sheet
    if (state.selectedRecordForProof != null) {
        val record = state.selectedRecordForProof!!
        val timeStr = timeFormat.format(Date(record.timestamp))
        AlertDialog(
            onDismissRequest = { viewModel.selectRecordForProof(null) },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Verified, contentDescription = null, tint = omniEmerald(isDark))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Aegis Cryptographic Proof",
                        color = omniTextPrimary(isDark),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "This biometric attendance punch has been cryptographically sealed with hardware SHA-256 hash:",
                        color = omniTextSecondary(isDark),
                        fontSize = 12.sp
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDark) Color(0xFF0F172A) else Color(0xFFF1F5F9))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = record.sha256Hash,
                            color = omniCyan(isDark),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Text(
                        text = "Student: ${record.studentName} (${record.studentRoll})\nTimestamp: ${record.sessionDate} $timeStr\nConfidence: ${record.confidencePct}%\nTier: ${record.securityTier}",
                        color = omniTextSecondary(isDark),
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            viewModel.dispatchParentAlert(context, record)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = omniCyan(isDark)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Notify Parent", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { viewModel.selectRecordForProof(null) }) {
                        Text("Close", color = omniTextMuted(isDark), fontWeight = FontWeight.Bold)
                    }
                }
            },
            containerColor = if (isDark) Color(0xFF1E293B) else Color(0xFFFFFFFF),
            shape = RoundedCornerShape(16.dp)
        )
    }
}
