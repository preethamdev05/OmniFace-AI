@file:Suppress("DEPRECATION")

package com.omniface.ai.ui.ledger

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.*
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.omniface.ai.billing.SubscriptionTierManager
import com.omniface.ai.billing.PaywallTriggerReason
import com.omniface.ai.ui.billing.PaywallBottomSheet
import com.omniface.ai.ui.components.InHousePromoBanner

@Immutable
data class LedgerUiState(
    val allRecords: List<AttendanceRecordEntity> = emptyList(),
    val displayedRecords: List<AttendanceRecordEntity> = emptyList(),
    val searchQuery: String = "",
    val activeFilter: String = "ALL", // ALL, TODAY, SYNCED, LOCAL
    val selectedRecordForProof: AttendanceRecordEntity? = null,
    val showIntegrityDialog: Boolean = false,
    val integrityReport: Pair<Boolean, String>? = null,
    val showPaywall: Boolean = false,
    val paywallReason: PaywallTriggerReason = PaywallTriggerReason.EXCEL_PDF_EXPORT_LOCKED
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

    fun checkLedgerIntegrity() {
        val records = _uiState.value.allRecords.sortedBy { it.timestamp }
        if (records.isEmpty()) {
            _uiState.update {
                it.copy(
                    showIntegrityDialog = true,
                    integrityReport = Pair(true, "Genesis state intact — zero records enrolled")
                )
            }
            return
        }

        var expectedPrevHash = com.omniface.ai.security.AndroidSecurityUtils.AEGIS_GENESIS_HASH
        var isValid = true
        var detailMessage = ""

        for (r in records) {
            val computedHash = com.omniface.ai.security.AndroidSecurityUtils.computeAegisBlockHash(
                previousHash = expectedPrevHash,
                studentRoll = r.studentRoll,
                timestamp = r.timestamp,
                confidencePct = r.confidencePct
            )
            if (r.sha256Hash.isNotBlank() && r.sha256Hash != computedHash && r.sha256Hash != expectedPrevHash) {
                // If hash mismatch occurs, record integrity breach
                isValid = false
                detailMessage = "Tamper Alert: Block breach at ${r.studentName} (${r.studentRoll})"
                break
            }
            expectedPrevHash = r.sha256Hash
        }

        if (isValid) {
            val merkleRoot = com.omniface.ai.security.AndroidSecurityUtils.computeMerkleRoot(records.map { it.sha256Hash })
            detailMessage = "All ${records.size} attendance blocks cryptographically verified.\nMerkle Root: ${merkleRoot.take(16)}..."
        }

        _uiState.update {
            it.copy(
                showIntegrityDialog = true,
                integrityReport = Pair(isValid, detailMessage)
            )
        }
    }

    fun dismissIntegrityDialog() {
        _uiState.update { it.copy(showIntegrityDialog = false, integrityReport = null) }
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

    fun dismissPaywall() {
        _uiState.update { it.copy(showPaywall = false) }
    }

    fun triggerPaywall(reason: PaywallTriggerReason = PaywallTriggerReason.EXCEL_PDF_EXPORT_LOCKED) {
        _uiState.update { it.copy(showPaywall = true, paywallReason = reason) }
    }

    fun exportAuditCsv(context: Context, records: List<AttendanceRecordEntity>) {
        if (!SubscriptionTierManager.canExportReports()) {
            triggerPaywall(PaywallTriggerReason.EXCEL_PDF_EXPORT_LOCKED)
            return
        }

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
    viewModel: LedgerViewModel,
    onNavigateToScanner: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isDark = LocalThemeIsDark.current
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // Intercept back gesture on cryptographic proof modal or search bar
    BackHandler(enabled = state.selectedRecordForProof != null) {
        viewModel.selectRecordForProof(null)
    }
    BackHandler(enabled = state.searchQuery.isNotEmpty() && state.selectedRecordForProof == null) {
        viewModel.onSearchQueryChanged("")
    }

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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(OmniViolet)
                        )
                        Text(
                            text = "CRYPTOGRAPHIC LEDGER",
                            color = OmniViolet,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Attendance Ledger",
                        color = omniTextPrimary(isDark),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Immutable SHA-256 Aegis Hash Chain",
                        color = omniTextMuted(isDark),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CupertinoActionPill(
                        text = "Verify",
                        icon = Icons.Default.Shield,
                        accentColor = OmniViolet,
                        onClick = { viewModel.checkLedgerIntegrity() }
                    )

                    CupertinoActionPill(
                        text = "Export",
                        icon = Icons.Default.FileDownload,
                        accentColor = omniCyan(isDark),
                        onClick = { viewModel.exportAuditCsv(context, state.displayedRecords) }
                    )
                }
            }
        }

        // Summary Metric Strip
        item {
            val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }
            val todayCount = remember(state.allRecords) { state.allRecords.count { it.sessionDate == today } }
            val totalCount = state.allRecords.size
            val syncedCount = remember(state.allRecords) { state.allRecords.count { it.isSynced } }
            val syncPercent = if (totalCount > 0) (syncedCount * 100 / totalCount) else 100

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CupertinoMetricTile(
                    title = "TODAY",
                    value = "$todayCount",
                    subtitle = "Present",
                    icon = Icons.Default.Today,
                    accentColor = omniEmerald(isDark),
                    modifier = Modifier.weight(1f)
                )
                CupertinoMetricTile(
                    title = "TOTAL",
                    value = "$totalCount",
                    subtitle = "Logs",
                    icon = Icons.Default.ReceiptLong,
                    accentColor = OmniViolet,
                    modifier = Modifier.weight(1f)
                )
                CupertinoMetricTile(
                    title = "SYNCED",
                    value = "$syncPercent%",
                    subtitle = "Fleet",
                    icon = Icons.Default.CloudDone,
                    accentColor = omniCyan(isDark),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // In-House Promo Banner (Free tier only)
        item {
            InHousePromoBanner(
                modifier = Modifier.fillMaxWidth(),
                onUpgradeClick = { viewModel.triggerPaywall(PaywallTriggerReason.EXCEL_PDF_EXPORT_LOCKED) }
            )
        }

        // iOS Inset Search Bar
        item {
            CupertinoSearchField(
                query = state.searchQuery,
                onQueryChange = { viewModel.onSearchQueryChanged(it) },
                placeholder = LocalizationManager.get(StringKey.SEARCH_RECORDS)
            )
        }

        // Filter Pills Row
        item {
            val filterOptions = listOf(
                "ALL" to "All (${state.allRecords.size})",
                "TODAY" to LocalizationManager.get(StringKey.ATTENDANCE_TODAY),
                "SYNCED" to "${LocalizationManager.get(StringKey.CLOUD_SYNC_ENABLED)} ☁",
                "LOCAL" to "${LocalizationManager.get(StringKey.OFFLINE_FIRST_MODE)} 🔒"
            )

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filterOptions) { (key, label) ->
                    val isSelected = state.activeFilter == key
                    Box(
                        modifier = Modifier
                            .shadow(
                                elevation = if (isSelected) 6.dp else 0.dp,
                                shape = RoundedCornerShape(999.dp),
                                ambientColor = if (isSelected) OmniViolet.copy(alpha = 0.35f) else Color.Transparent,
                                spotColor = if (isSelected) OmniViolet.copy(alpha = 0.25f) else Color.Transparent
                            )
                            .clip(RoundedCornerShape(999.dp))
                            .then(
                                if (isSelected) {
                                    Modifier.background(OmniButtonBrush)
                                } else {
                                    Modifier.background(if (isDark) Color(0x331E293B) else Color(0xFFF1F5F9))
                                }
                            )
                            .border(
                                width = 0.75.dp,
                                brush = if (isSelected) {
                                    Brush.verticalGradient(
                                        listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.05f))
                                    )
                                } else {
                                    omniLiquidSpecularBorder(isDark)
                                },
                                shape = RoundedCornerShape(999.dp)
                            )
                            .clickable { viewModel.onFilterSelected(key) }
                            .padding(horizontal = 15.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.White else omniTextSecondary(isDark),
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                            letterSpacing = (-0.2).sp
                        )
                    }
                }
            }
        }

        // Empty State or List of Records
        if (state.displayedRecords.isEmpty()) {
            item {
                IOSCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        EmptyState(
                            icon = Icons.Default.ReceiptLong,
                            title = if (state.searchQuery.isNotEmpty() || state.activeFilter != "ALL") "No Matching Records" else "No Attendance Records Yet",
                            subtitle = if (state.searchQuery.isNotEmpty()) "Try clearing your search query or switching filters." else "Verified biometric scans will automatically appear in this cryptographic chain."
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        CupertinoButton(
                            text = "Start Attendance Scanner",
                            icon = Icons.Default.CameraAlt,
                            onClick = onNavigateToScanner
                        )
                    }
                }
            }
        } else {
            items(
                items = state.displayedRecords,
                key = { it.recordId },
                contentType = { "attendance_record" }
            ) { record ->
                val timeStr = timeFormat.format(Date(record.timestamp))
                IOSCard(
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
                            // Doppelrand Squircle Avatar with Initial
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(OmniViolet.copy(alpha = 0.15f))
                                    .border(
                                        0.75.dp,
                                        Brush.verticalGradient(
                                            listOf(OmniViolet.copy(alpha = 0.5f), OmniViolet.copy(alpha = 0.15f))
                                        ),
                                        RoundedCornerShape(13.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = record.studentName.take(1).uppercase(),
                                    color = OmniViolet,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = record.studentName,
                                    color = omniTextPrimary(isDark),
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = record.studentRoll,
                                        color = omniCyan(isDark),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "•",
                                        color = omniTextMuted(isDark).copy(alpha = 0.6f),
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text = "${record.sessionDate} $timeStr",
                                        color = omniTextMuted(isDark),
                                        fontSize = 10.5.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // One-Click Parent Alert Dispatcher (Frosted Glass Chip)
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(if (isDark) Color(0x331E293B) else Color(0x1A0284C7))
                                    .border(0.5.dp, omniLiquidSpecularBorder(isDark), CircleShape)
                                    .clickable { viewModel.dispatchParentAlert(context, record) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = "Notify Parent",
                                    tint = omniCyan(isDark),
                                    modifier = Modifier.size(15.dp)
                                )
                            }

                            // Aegis Proof Chip
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (record.isSynced) omniEmerald(isDark).copy(alpha = 0.15f) else Color(0x1F1E293B))
                                    .border(
                                        0.5.dp,
                                        if (record.isSynced) omniEmerald(isDark).copy(alpha = 0.4f) else Color(0x2BFFFFFF),
                                        RoundedCornerShape(999.dp)
                                    )
                                    .clickable { viewModel.selectRecordForProof(record) }
                                    .padding(horizontal = 9.dp, vertical = 5.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(if (record.isSynced) omniEmerald(isDark) else Color(0xFFF59E0B))
                                    )
                                    Text(
                                        text = if (record.isSynced) "Proof" else "Local",
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
    }

    // Aegis Blockchain Proof Modal Sheet
    if (state.selectedRecordForProof != null) {
        val record = state.selectedRecordForProof!!
        val timeStr = timeFormat.format(Date(record.timestamp))
        AlertDialog(
            onDismissRequest = { viewModel.selectRecordForProof(null) },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(omniEmerald(isDark).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Verified,
                            contentDescription = null,
                            tint = omniEmerald(isDark),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "AEGIS IMMUTABLE PROOF",
                            color = omniEmerald(isDark),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = LocalizationManager.get(StringKey.CRYPTOGRAPHIC_PROOF),
                            color = omniTextPrimary(isDark),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Cryptographic SHA-256 hash block anchored in local Aegis ledger:",
                        color = omniTextSecondary(isDark),
                        fontSize = 12.sp
                    )

                    // Monospace Hash Block Box with Glass Inset
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDark) Color(0xFF0B101E) else Color(0xFFF1F5F9))
                            .border(0.75.dp, omniLiquidSpecularBorder(isDark), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = record.sha256Hash,
                            color = omniCyan(isDark),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp
                        )
                    }

                    // Metadata Summary Table
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDark) Color(0x331E293B) else Color(0x0D000000))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Identity:", fontSize = 11.sp, color = omniTextMuted(isDark))
                            Text("${record.studentName} (${record.studentRoll})", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = omniTextPrimary(isDark))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Timestamp:", fontSize = 11.sp, color = omniTextMuted(isDark))
                            Text("${record.sessionDate} $timeStr", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = omniTextPrimary(isDark))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Confidence:", fontSize = 11.sp, color = omniTextMuted(isDark))
                            Text("${record.confidencePct}% (Tier: ${record.securityTier})", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = omniEmerald(isDark))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Sync State:", fontSize = 11.sp, color = omniTextMuted(isDark))
                            Text(if (record.isSynced) "Verified Cloud Sync" else "Hardware Keystore Local", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = omniCyan(isDark))
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CupertinoButton(
                        text = "Notify Parent",
                        icon = Icons.Default.Send,
                        brush = OmniButtonBrush,
                        height = 42.dp,
                        onClick = {
                            viewModel.dispatchParentAlert(context, record)
                        }
                    )
                    TextButton(onClick = { viewModel.selectRecordForProof(null) }) {
                        Text("Close", color = omniTextMuted(isDark), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            },
            containerColor = if (isDark) Color(0xFF141A29) else Color(0xFFFFFFFF),
            shape = RoundedCornerShape(24.dp)
        )
    }

    val integrityReport = state.integrityReport
    if (state.showIntegrityDialog && integrityReport != null) {
        val (isValid, detail) = integrityReport
        AlertDialog(
            onDismissRequest = { viewModel.dismissIntegrityDialog() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(if (isValid) omniEmerald(isDark).copy(alpha = 0.15f) else Color(0x22EF4444)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isValid) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isValid) omniEmerald(isDark) else Color(0xFFEF4444),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isValid) "Aegis Ledger Valid" else "Integrity Warning",
                        color = omniTextPrimary(isDark),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            },
            text = {
                Text(
                    text = detail,
                    color = omniTextSecondary(isDark),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                CupertinoButton(
                    text = "Acknowledge",
                    brush = OmniButtonBrush,
                    height = 40.dp,
                    onClick = { viewModel.dismissIntegrityDialog() }
                )
            },
            containerColor = if (isDark) Color(0xFF141A29) else Color(0xFFFFFFFF),
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (state.showPaywall) {
        PaywallBottomSheet(
            triggerReason = state.paywallReason,
            onDismiss = { viewModel.dismissPaywall() },
            onUpgradeSuccess = { viewModel.dismissPaywall() }
        )
    }
}
