package com.omniface.ai.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.omniface.ai.hardware.QrBadgeGenerator
import com.omniface.ai.hardware.QrCodeExporter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.data.local.entity.StudentEntity
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey
import com.omniface.ai.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentInfoSheet(
    student: StudentEntity,
    templates: List<FaceTemplateEntity> = emptyList(),
    attendanceCount: Int = 0,
    recentRecords: List<AttendanceRecordEntity> = emptyList(),
    isDark: Boolean,
    onDismiss: () -> Unit,
    onEditClick: () -> Unit = {},
    onViewAttendanceClick: () -> Unit = {},
    onReEnrollClick: () -> Unit = {}
) {
    BackHandler {
        onDismiss()
    }

    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()

    val regDateStr = remember(student.createdAt) {
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(student.createdAt))
    }

    val latestRecord = recentRecords.firstOrNull()
    val lastSeenStr = remember(latestRecord?.timestamp) {
        if (latestRecord != null) {
            SimpleDateFormat("dd MMM yyyy • hh:mm a", Locale.getDefault()).format(Date(latestRecord.timestamp))
        } else "No attendance logged yet"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = if (isDark) Color(0xFF0F172A) else Color(0xFFFFFFFF),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.5.dp)
                    .clip(CircleShape)
                    .background(if (isDark) Color(0x40FFFFFF) else Color(0x30000000))
            )
        },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Student Identity Hero Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar Initials Badge
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = listOf(Color(0xFF0284C7), Color(0xFF38BDF8))
                            )
                        )
                        .border(
                            width = 2.dp,
                            color = Color(0xFF38BDF8).copy(alpha = 0.5f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = student.fullName.take(2).uppercase(),
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = student.fullName,
                        color = omniTextPrimary(isDark),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Roll: ${student.rollNumber}",
                        color = omniCyan(isDark),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IOSGlassPill(
                            text = student.department.ifBlank { "General" },
                            accentColor = omniCyan(isDark)
                        )
                        IOSGlassPill(
                            text = if (student.semester.isNotBlank()) "Sem ${student.semester}" else "Active",
                            accentColor = Color(0xFF10B981)
                        )
                    }
                }
            }

            HorizontalDivider(color = if (isDark) Color(0x26FFFFFF) else Color(0x14000000))

            // 2. Academic & Enrollment Details Card
            IOSCard(cornerRadius = 16.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "ACADEMIC INFORMATION",
                        color = omniTextMuted(isDark),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Department", color = omniTextMuted(isDark), fontSize = 11.5.sp)
                            Text(student.department.ifBlank { "N/A" }, color = omniTextPrimary(isDark), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Semester / Year", color = omniTextMuted(isDark), fontSize = 11.5.sp)
                            Text(student.semester.ifBlank { "N/A" }, color = omniTextPrimary(isDark), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enrolled Since", color = omniTextMuted(isDark), fontSize = 11.5.sp)
                            Text(regDateStr, color = omniTextPrimary(isDark), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Attendance Logged", color = omniTextMuted(isDark), fontSize = 11.5.sp)
                            Text("${recentRecords.size} Days Present", color = Color(0xFF10B981), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // 2.5 Two-Factor Authentication QR Badge
            val qrBitmap = remember(student.rollNumber) {
                QrBadgeGenerator.generateStudentQrBitmap(
                    content = student.rollNumber,
                    sizePx = 384,
                    foregroundColor = android.graphics.Color.BLACK,
                    backgroundColor = android.graphics.Color.WHITE
                )
            }

            IOSCard(cornerRadius = 16.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "2FA STUDENT DIGITAL BADGE",
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
                        Box(
                            modifier = Modifier
                                .size(140.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "Student 2FA QR Badge",
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // Export & Share Action Buttons
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
                                shape = RoundedCornerShape(12.dp),
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
                                shape = RoundedCornerShape(12.dp),
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

                    Text(
                        text = "Hold badge up to camera during kiosk scan for 2FA validation",
                        color = omniTextMuted(isDark),
                        fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            // 3. Biometric Security & Vault Status
            IOSCard(cornerRadius = 16.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "BIOMETRIC VAULT STATUS",
                            color = omniTextMuted(isDark),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )

                        IOSGlassPill(
                            text = "${templates.size} Templates",
                            icon = Icons.Default.Shield,
                            accentColor = Color(0xFF10B981)
                        )
                    }

                    // Enrolled Angle Badges
                    val angles = listOf("FRONTAL", "LEFT_15", "RIGHT_15", "UP_10", "DOWN_10", "MASTER_CENTROID")
                    val enrolledAngles = templates.map { it.angleType.uppercase() }.toSet()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        angles.take(5).forEach { angle ->
                            val isEnrolled = enrolledAngles.any { it.contains(angle.replace("_15", "").replace("_10", "")) }
                            val label = when (angle) {
                                "FRONTAL" -> "Front"
                                "LEFT_15" -> "Left"
                                "RIGHT_15" -> "Right"
                                "UP_10" -> "Up"
                                "DOWN_10" -> "Down"
                                else -> angle
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isEnrolled) Color(0xFF10B981).copy(alpha = 0.15f)
                                        else if (isDark) Color(0x22FFFFFF) else Color(0x0D000000)
                                    )
                                    .border(
                                        width = 0.75.dp,
                                        color = if (isEnrolled) Color(0xFF10B981).copy(alpha = 0.4f) else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isEnrolled) "✓ $label" else label,
                                    color = if (isEnrolled) Color(0xFF10B981) else omniTextMuted(isDark),
                                    fontSize = 10.sp,
                                    fontWeight = if (isEnrolled) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Hardware Keystore AES-256 encryption status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Encrypted",
                            tint = omniCyan(isDark),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Hardware Keystore AES-256-GCM Encrypted",
                            color = omniTextMuted(isDark),
                            fontSize = 11.5.sp
                        )
                    }
                }
            }

            // 4. Latest Verification / Activity Card
            IOSCard(cornerRadius = 16.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "LAST SEEN VERIFICATION",
                        color = omniTextMuted(isDark),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = lastSeenStr,
                        color = omniTextPrimary(isDark),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (latestRecord != null) {
                        Text(
                            text = "Confidence: % • Tier: ",
                            color = omniCyan(isDark),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 5. Full Action Suite Button Group
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Edit Details
                Button(
                    onClick = {
                        onDismiss()
                        onEditClick()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDark) Color(0x3338BDF8) else Color(0x1A0284C7),
                        contentColor = omniCyan(isDark)
                    ),
                    border = BorderStroke(1.dp, omniCyan(isDark).copy(alpha = 0.4f))
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Edit", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                // View Attendance Records
                Button(
                    onClick = {
                        onDismiss()
                        onViewAttendanceClick()
                    },
                    modifier = Modifier
                        .weight(1.3f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0284C7),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.History, contentDescription = "Records", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Attendance", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                // Re-enroll Face
                Button(
                    onClick = {
                        onDismiss()
                        onReEnrollClick()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDark) Color(0x33F59E0B) else Color(0x1AF59E0B),
                        contentColor = Color(0xFFF59E0B)
                    ),
                    border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f))
                ) {
                    Icon(Icons.Default.Face, contentDescription = "Re-enroll", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Re-enroll", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}
