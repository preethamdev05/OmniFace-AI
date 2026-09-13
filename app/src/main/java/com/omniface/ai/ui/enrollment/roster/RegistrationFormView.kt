package com.omniface.ai.ui.enrollment.roster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.YuvImage
import com.google.mlkit.vision.face.FaceLandmark
import com.omniface.ai.hardware.NpuHardwareDetector
import com.omniface.ai.hardware.QrBadgeGenerator
import com.omniface.ai.hardware.QrCodeExporter
import com.omniface.ai.ml.BiometricCropUtils
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.util.Range
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import kotlin.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.omniface.ai.OmniFaceApplication
import com.omniface.ai.audio.BiometricSoundboard
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.data.local.entity.StudentEntity
import com.omniface.ai.ml.*
import com.omniface.ai.security.AndroidSecurityUtils
import com.omniface.ai.security.DeviceBiometricAuthManager
import com.omniface.ai.security.findFragmentActivity
import com.omniface.ai.ui.components.*
import com.omniface.ai.ui.dedup.BiometricDeduplicationStudio
import com.omniface.ai.ml.recognition.BiometricDeduplicationEngine
import com.omniface.ai.ml.recognition.DuplicateCheckResult
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey
import com.omniface.ai.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omniface.ai.billing.SubscriptionTierManager
import com.omniface.ai.billing.PaywallTriggerReason
import com.omniface.ai.ui.billing.PaywallBottomSheet

import com.omniface.ai.ui.enrollment.EnrollmentViewModel
import com.omniface.ai.ui.enrollment.EnrollmentUiState
import com.omniface.ai.ui.enrollment.EnrollmentStage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RegistrationFormView(
    viewModel: EnrollmentViewModel,
    state: EnrollmentUiState,
    isDark: Boolean,
    context: Context,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(omniBackgroundBrush(isDark))
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = LocalizationManager.getEntityPlural(state.orgType).uppercase(),
                        color = OmniViolet,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = LocalizationManager.getEntityPlural(state.orgType),
                        color = omniTextPrimary(isDark),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp,
                        maxLines = 1
                    )
                    Text(
                        text = "Biometric Vault & Multi-Role Directory",
                        color = omniTextMuted(isDark),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                IOSGlassPill(
                    text = "5-Angle 3D Vault",
                    icon = Icons.Default.Shield,
                    accentColor = OmniViolet
                )
            }
        }

        // Cupertino Segmented Control (Directory vs New Registration)
        item {
            CupertinoSegmentedControl(
                items = listOf(
                    "Directory (${state.enrolledStudentsList.size})",
                    "+ Enroll ${LocalizationManager.getEntitySingular(state.orgType)}"
                ),
                selectedIndex = selectedTab,
                onItemSelected = onTabSelected
            )
        }

        if (selectedTab == 0) {
            // Registered Directory List with Name Search & Profile Management
            item {
                IOSCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(
                        text = "ENROLLED ${LocalizationManager.getEntityPlural(state.orgType).uppercase()} (${state.enrolledStudentsList.size} / ${SubscriptionTierManager.getMaxStudentsDisplay()})",
                        actionText = "+ Add New",
                        onAction = { onTabSelected(1) }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Role Filter Chips Row
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    ) {
                        val filters = listOf(
                            "ALL" to "All People",
                            "PRIMARY" to LocalizationManager.getEntityPlural(state.orgType),
                            "STAFF" to "Staff & Faculty",
                            "VISITOR" to "Visitors"
                        )
                        items(filters) { (key, label) ->
                            val isSelected = state.selectedRoleFilter == key
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (isSelected) OmniViolet else (if (isDark) Color(0x1AFFFFFF) else Color(0x0D000000)))
                                    .border(0.75.dp, if (isSelected) OmniViolet else Color.Transparent, CircleShape)
                                    .clickable { viewModel.onRoleFilterChanged(key) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) Color.White else omniTextMuted(isDark),
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Search Box
                    CupertinoSearchField(
                        query = state.searchQuery,
                        onQueryChange = { viewModel.onSearchQueryChanged(it) },
                        placeholder = "Search by name, ${LocalizationManager.getIdLabel(state.orgType).lowercase()}, or department..."
                    )

                    if (state.searchQuery.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Found ${state.filteredEnrolledStudents.size} of ${state.enrolledStudentsList.size} registered profiles",
                            color = omniCyan(isDark),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (state.enrolledStudentsList.isEmpty()) {
                        EmptyState(
                            icon = Icons.Default.PersonAdd,
                            title = "No ${LocalizationManager.getEntityPlural(state.orgType).lowercase()} enrolled yet",
                            subtitle = "Register your first ${LocalizationManager.getEntitySingular(state.orgType).lowercase()} or staff member to enable face identification"
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        CupertinoButton(
                            text = "Enroll First ${LocalizationManager.getEntitySingular(state.orgType)}",
                            icon = Icons.Default.PersonAdd,
                            onClick = { onTabSelected(1) }
                        )
                    } else if (state.filteredEnrolledStudents.isEmpty()) {
                        EmptyState(
                            icon = Icons.Default.SearchOff,
                            title = LocalizationManager.get(StringKey.SEARCH_STUDENTS),
                            subtitle = "\"${state.searchQuery}\""
                        )
                    } else {
                        state.filteredEnrolledStudents.forEachIndexed { index, student ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { viewModel.openStudentProfile(student) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
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
                                            .clip(CircleShape)
                                            .background(OmniViolet.copy(alpha = 0.18f))
                                            .border(1.dp, OmniViolet.copy(alpha = 0.35f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = student.fullName.take(1).uppercase(),
                                            color = OmniViolet,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = student.fullName,
                                                color = omniTextPrimary(isDark),
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            val roleBadge = LocalizationManager.getRoleBadgeLabel(student.role)
                                            Box(
                                                modifier = Modifier
                                                    .clip(CircleShape)
                                                    .background(OmniViolet.copy(alpha = 0.15f))
                                                    .border(0.5.dp, OmniViolet.copy(alpha = 0.35f), CircleShape)
                                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = roleBadge.uppercase(),
                                                    color = OmniSky,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${student.rollNumber} • ${student.department} (${student.semester})",
                                            color = omniTextMuted(isDark),
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = LocalizationManager.get(StringKey.EDIT_PROFILE),
                                    tint = omniTextMuted(isDark).copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            if (index < state.filteredEnrolledStudents.size - 1) {
                                HorizontalDivider(color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000), thickness = 0.5.dp)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        CupertinoButton(
                            text = "Enroll New ${LocalizationManager.getEntitySingular(state.orgType)}",
                            icon = Icons.Default.Add,
                            onClick = { onTabSelected(1) }
                        )
                    }
                }
            }
        } else {
            // Registration Input Card
            item {
                IOSCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ENROLL NEW ${LocalizationManager.getEntitySingular(state.orgType).uppercase()}",
                            color = omniTextMuted(isDark),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        IOSGlassPill(
                            text = "✦ Enterprise 3D",
                            icon = Icons.Default.Bolt,
                            accentColor = OmniViolet
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))

                    // Role Selector Chips
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "ROLE & DESIGNATION",
                            color = omniTextMuted(isDark),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val roles = LocalizationManager.getAllowedRoles(state.orgType)
                            items(roles) { r ->
                                val isSelected = state.role.equals(r, ignoreCase = true)
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(if (isSelected) OmniViolet else (if (isDark) Color(0x1AFFFFFF) else Color(0x0D000000)))
                                        .border(0.75.dp, if (isSelected) OmniViolet else Color.Transparent, CircleShape)
                                        .clickable { viewModel.updateForm(role = r) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = LocalizationManager.getRoleBadgeLabel(r),
                                        color = if (isSelected) Color.White else omniTextMuted(isDark),
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    // Full Name
                    OutlinedTextField(
                        value = state.fullName,
                        onValueChange = { viewModel.updateForm(name = it) },
                        label = { Text(LocalizationManager.get(StringKey.FULL_NAME), fontSize = 12.sp) },
                        placeholder = { Text("e.g. John Doe", color = omniTextMuted(isDark), fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OmniViolet,
                            unfocusedBorderColor = if (isDark) Color(0x33FFFFFF) else Color(0x22000000),
                            focusedTextColor = omniTextPrimary(isDark),
                            unfocusedTextColor = omniTextPrimary(isDark)
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // ID / Roll Number
                    OutlinedTextField(
                        value = state.rollNumber,
                        onValueChange = { viewModel.updateForm(roll = it) },
                        label = { Text(LocalizationManager.getIdLabel(state.orgType), fontSize = 12.sp) },
                        placeholder = { Text(if (state.orgType.uppercase() == "CORPORATE") "e.g. EMP-1042" else "e.g. CS2024-042", color = omniTextMuted(isDark), fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OmniViolet,
                            unfocusedBorderColor = if (isDark) Color(0x33FFFFFF) else Color(0x22000000),
                            focusedTextColor = omniTextPrimary(isDark),
                            unfocusedTextColor = omniTextPrimary(isDark)
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = state.department,
                            onValueChange = { viewModel.updateForm(dept = it) },
                            label = { Text(LocalizationManager.get(StringKey.DEPARTMENT), fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1.5f),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = OmniViolet,
                                unfocusedBorderColor = if (isDark) Color(0x33FFFFFF) else Color(0x22000000),
                                focusedTextColor = omniTextPrimary(isDark),
                                unfocusedTextColor = omniTextPrimary(isDark)
                            )
                        )

                        OutlinedTextField(
                            value = state.semester,
                            onValueChange = { viewModel.updateForm(sem = it) },
                            label = { Text(LocalizationManager.getGroupLabel(state.orgType), fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = OmniViolet,
                                unfocusedBorderColor = if (isDark) Color(0x33FFFFFF) else Color(0x22000000),
                                focusedTextColor = omniTextPrimary(isDark),
                                unfocusedTextColor = omniTextPrimary(isDark)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Quick Department Suggestion Chips
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val depts = listOf("AI & Biometrics", "Computer Science", "Info Science", "Electronics")
                        items(depts) { dept ->
                            val isSelected = state.department == dept
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (isSelected) OmniViolet.copy(alpha = 0.20f) else (if (isDark) Color(0x1AFFFFFF) else Color(0x0D000000)))
                                    .border(0.75.dp, if (isSelected) OmniViolet else Color.Transparent, RoundedCornerShape(999.dp))
                                    .clickable { viewModel.updateForm(dept = dept) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(dept, color = if (isSelected) (if (isDark) OmniSky else OmniDeepPurple) else omniTextMuted(isDark), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    if (!state.isModelAvailable) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isDark) Color(0x26F59E0B) else Color(0x1AF59E0B))
                                .border(0.75.dp, Color(0xFFF59E0B).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "AI Recognition Pack Required",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = omniTextPrimary(isDark)
                                    )
                                    Text(
                                        text = "Download the 380 MB AI Face Pack in Settings to extract facial templates and enroll ${LocalizationManager.getEntityPlural(state.orgType).lowercase()}.",
                                        fontSize = 11.sp,
                                        color = omniTextSecondary(isDark),
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // Action Button: Continue to Face Enrollment
                    CupertinoButton(
                        text = if (state.isModelAvailable) LocalizationManager.get(StringKey.BEGIN_FACE_ENROLLMENT) else "AI Pack Required to Enroll",
                        icon = if (state.isModelAvailable) Icons.Default.Face else Icons.Default.CloudDownload,
                        enabled = state.isModelAvailable,
                        brush = if (state.isModelAvailable) OmniButtonBrush else null,
                        onClick = { viewModel.startBiometricStudio(context) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = { onTabSelected(0) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "← Back to Directory",
                            color = omniTextMuted(isDark),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    // Profile Management Sheet
    if (state.selectedStudentForManage != null) {
        val student = state.selectedStudentForManage
        val creationDateStr = remember(student.createdAt) {
            SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(student.createdAt))
        }

        ModalBottomSheet(
            onDismissRequest = { viewModel.closeStudentProfile() },
            containerColor = if (isDark) Color(0xFF161922) else Color(0xFFFFFFFF),
            dragHandle = { BottomSheetDefaults.DragHandle(color = omniTextMuted(isDark)) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 36.dp)
            ) {
                // Header Avatar & Identity
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(omniCyan(isDark).copy(alpha = 0.20f))
                            .border(1.5.dp, omniCyan(isDark), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = student.fullName.take(1).uppercase(),
                            color = omniCyan(isDark),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = student.fullName,
                                color = omniTextPrimary(isDark),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val roleBadge = LocalizationManager.getRoleBadgeLabel(student.role)
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(OmniViolet.copy(alpha = 0.15f))
                                    .border(0.5.dp, OmniViolet.copy(alpha = 0.35f), CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = roleBadge.uppercase(),
                                    color = OmniSky,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${LocalizationManager.getIdLabel(state.orgType)}: ${student.rollNumber}",
                            color = omniCyan(isDark),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Profile Details Card
                IOSCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(LocalizationManager.get(StringKey.DEPARTMENT).uppercase(), color = omniTextMuted(isDark), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(student.department, color = omniTextPrimary(isDark), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(LocalizationManager.getGroupLabel(state.orgType).uppercase(), color = omniTextMuted(isDark), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(if (student.semester.isNotBlank()) student.semester else "Standard", color = omniTextPrimary(isDark), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000), thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(LocalizationManager.get(StringKey.ENROLLED_ON).uppercase(), color = omniTextMuted(isDark), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(creationDateStr, color = omniTextPrimary(isDark), fontSize = 12.sp, fontWeight = FontWeight.Normal)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(LocalizationManager.get(StringKey.VAULT_STATUS).uppercase(), color = omniTextMuted(isDark), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(LocalizationManager.get(StringKey.AES_ENCRYPTED), color = EmeraldCore, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // 2FA Digital ID QR Badge Card
                val qrBitmap = remember(student.rollNumber) {
                    QrBadgeGenerator.generateStudentQrBitmap(
                        content = student.rollNumber,
                        sizePx = 384,
                        foregroundColor = android.graphics.Color.BLACK,
                        backgroundColor = android.graphics.Color.WHITE
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                IOSCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "2FA DIGITAL BADGE",
                            color = omniTextMuted(isDark),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )

                        IOSGlassPill(
                            text = "2FA Ready",
                            icon = Icons.Default.QrCodeScanner,
                            accentColor = omniCyan(isDark)
                        )
                    }

                    if (qrBitmap != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(130.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White)
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.foundation.Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "2FA QR Badge",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    QrCodeExporter.saveQrCodeToGallery(
                                        context = context,
                                        rollNumber = student.rollNumber,
                                        fullName = student.fullName,
                                        department = student.department,
                                        semester = student.semester
                                    )
                                },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0),
                                    contentColor = omniTextPrimary(isDark)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Save Image", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }

                            Button(
                                onClick = {
                                    QrCodeExporter.shareQrCode(
                                        context = context,
                                        rollNumber = student.rollNumber,
                                        fullName = student.fullName,
                                        department = student.department,
                                        semester = student.semester
                                    )
                                },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = omniCyan(isDark),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Share", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${LocalizationManager.getIdLabel(state.orgType)}: ${student.rollNumber} • Present to kiosk camera for 2-Factor Auth",
                        color = omniTextMuted(isDark),
                        fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons
                CupertinoButton(
                    text = "✏️ ${LocalizationManager.get(StringKey.EDIT_PROFILE)}",
                    isSecondary = true,
                    onClick = { viewModel.openEditProfileDialog() }
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = { viewModel.openDeleteConfirmDialog() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CrimsonCore.copy(alpha = 0.15f),
                        contentColor = CrimsonCore
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CrimsonCore.copy(alpha = 0.35f))
                ) {
                    Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(LocalizationManager.get(StringKey.DELETE_IDENTITY), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }

    // Edit Profile Dialog
    if (state.isEditProfileOpen && state.selectedStudentForManage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.closeEditProfileDialog() },
            containerColor = if (isDark) Color(0xFF1E2129) else Color(0xFFFFFFFF),
            title = {
                Text(
                    text = LocalizationManager.get(StringKey.EDIT_PROFILE),
                    color = omniTextPrimary(isDark),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "${LocalizationManager.getIdLabel(state.orgType)}: ${state.selectedStudentForManage.rollNumber}",
                        color = omniTextMuted(isDark),
                        fontSize = 12.sp
                    )

                    // Role selector chips
                    Text(
                        text = "ROLE & DESIGNATION",
                        color = omniTextMuted(isDark),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val roles = LocalizationManager.getAllowedRoles(state.orgType)
                        items(roles) { r ->
                            val isSelected = state.editRole.equals(r, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (isSelected) OmniViolet else (if (isDark) Color(0x1AFFFFFF) else Color(0x0D000000)))
                                    .border(0.75.dp, if (isSelected) OmniViolet else Color.Transparent, CircleShape)
                                    .clickable { viewModel.updateEditFields(role = r) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = LocalizationManager.getRoleBadgeLabel(r),
                                    color = if (isSelected) Color.White else omniTextMuted(isDark),
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = state.editFullName,
                        onValueChange = { viewModel.updateEditFields(name = it) },
                        label = { Text(LocalizationManager.get(StringKey.FULL_NAME), fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = omniCyan(isDark),
                            unfocusedBorderColor = if (isDark) Color(0x33FFFFFF) else Color(0x22000000),
                            focusedTextColor = omniTextPrimary(isDark),
                            unfocusedTextColor = omniTextPrimary(isDark)
                        )
                    )

                    OutlinedTextField(
                        value = state.editDepartment,
                        onValueChange = { viewModel.updateEditFields(dept = it) },
                        label = { Text(LocalizationManager.get(StringKey.DEPARTMENT), fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = omniCyan(isDark),
                            unfocusedBorderColor = if (isDark) Color(0x33FFFFFF) else Color(0x22000000),
                            focusedTextColor = omniTextPrimary(isDark),
                            unfocusedTextColor = omniTextPrimary(isDark)
                        )
                    )

                    OutlinedTextField(
                        value = state.editSemester,
                        onValueChange = { viewModel.updateEditFields(sem = it) },
                        label = { Text(LocalizationManager.getGroupLabel(state.orgType), fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = omniCyan(isDark),
                            unfocusedBorderColor = if (isDark) Color(0x33FFFFFF) else Color(0x22000000),
                            focusedTextColor = omniTextPrimary(isDark),
                            unfocusedTextColor = omniTextPrimary(isDark)
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.saveEditedProfile(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = omniCyan(isDark)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(LocalizationManager.get(StringKey.SAVE_CHANGES), color = if (isDark) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.closeEditProfileDialog() }) {
                    Text(LocalizationManager.get(StringKey.CANCEL_ACTION), color = omniTextMuted(isDark))
                }
            }
        )
    }

    // Delete Confirmation Dialog
    if (state.isDeleteConfirmOpen && state.selectedStudentForManage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.closeDeleteConfirmDialog() },
            containerColor = if (isDark) Color(0xFF1E2129) else Color(0xFFFFFFFF),
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = CrimsonCore,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = LocalizationManager.get(StringKey.DELETE_IDENTITY),
                    color = omniTextPrimary(isDark),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete ${state.selectedStudentForManage.fullName} (${state.selectedStudentForManage.rollNumber})? This will permanently wipe all biometric templates and face vectors from the local vault.",
                    color = omniTextMuted(isDark),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmDeleteSelectedStudent(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonCore),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(LocalizationManager.get(StringKey.DELETE_PERMANENTLY), color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.closeDeleteConfirmDialog() }) {
                    Text(LocalizationManager.get(StringKey.CANCEL_ACTION), color = omniTextMuted(isDark))
                }
            }
        )
    }
}

