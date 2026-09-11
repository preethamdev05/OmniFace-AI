package com.omniface.ai.ui.onboarding

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniface.ai.OmniFaceApplication
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.data.local.entity.StudentEntity
import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.ui.components.IOSCard
import com.omniface.ai.ui.components.IOSGlassPill
import com.omniface.ai.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class OrgType(val title: String, val icon: ImageVector, val subtitle: String) {
    SCHOOL("School / College", Icons.Default.School, "Classrooms, lecture halls & student attendance"),
    COACHING("Coaching & Tuition", Icons.Default.AutoStories, "Batches, test series & faculty tracking"),
    CORPORATE("Corporate & IT Office", Icons.Default.Business, "Staff check-ins, shift timings & export"),
    GYM_EVENT("Gym, Club & Events", Icons.Default.FitnessCenter, "Membership gate access & visitor verification")
}

@Composable
fun OnboardingWizard(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val isDark = LocalThemeIsDark.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var currentStep by remember { mutableIntStateOf(1) }
    val totalSteps = 4

    // Step 1 State
    var orgName by remember { mutableStateOf("OmniFace Academy") }
    var selectedOrgType by remember { mutableStateOf(OrgType.SCHOOL) }

    // Step 2 State
    var rosterOption by remember { mutableIntStateOf(1) } // 1=Biometric Studio, 2=CSV Import, 3=Cloud Sync

    // Step 3 State
    var selectedSecurityTier by remember { mutableStateOf(SecurityTier.HIGH) }
    var requireTwoFactor by remember { mutableStateOf(false) }

    fun finishOnboarding() {
        val prefs = context.getSharedPreferences("omniface_app_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("onboarding_completed", true)
            putString("org_name", orgName)
            putString("org_type", selectedOrgType.name)
            putString("security_tier", selectedSecurityTier.name)
            putBoolean("two_factor_required", requireTwoFactor)
            putInt("roster_bootstrap_mode", rosterOption)
            apply()
        }
        onComplete()
    }

    Scaffold(
        containerColor = if (isDark) Color(0xFF090D16) else Color(0xFFF8FAFC),
        bottomBar = {
            Surface(
                color = if (isDark) Color(0xFF0F172A).copy(alpha = 0.95f) else Color(0xFFFFFFFF).copy(alpha = 0.95f),
                tonalElevation = 8.dp,
                shadowElevation = 16.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back or Skip Button
                    if (currentStep > 1) {
                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentStep--
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = omniTextPrimary(isDark)
                            )
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Back", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        TextButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                finishOnboarding()
                            }
                        ) {
                            Text("Skip Setup", color = omniTextMuted(isDark), fontSize = 13.sp)
                        }
                    }

                    // Step indicator dots
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (i in 1..totalSteps) {
                            Box(
                                modifier = Modifier
                                    .size(if (i == currentStep) 24.dp else 8.dp, 8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (i == currentStep) OmniViolet
                                        else if (i < currentStep) omniEmerald(isDark)
                                        else if (isDark) Color(0xFF334155) else Color(0xFFCBD5E1)
                                    )
                            )
                        }
                    }

                    // Next / Finish Button
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (currentStep < totalSteps) {
                                currentStep++
                            } else {
                                finishOnboarding()
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OmniViolet,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .shadow(8.dp, RoundedCornerShape(14.dp), ambientColor = OmniViolet.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = if (currentStep == totalSteps) "Launch Kiosk" else "Continue",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Step Header Badge
            IOSGlassPill(
                text = "STEP $currentStep OF $totalSteps",
                icon = Icons.Default.AutoAwesome,
                accentColor = OmniViolet
            )

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally { it / 2 } + fadeIn(tween(250)) togetherWith
                                slideOutHorizontally { -it / 2 } + fadeOut(tween(200))
                    } else {
                        slideInHorizontally { -it / 2 } + fadeIn(tween(250)) togetherWith
                                slideOutHorizontally { it / 2 } + fadeOut(tween(200))
                    }
                },
                label = "onboarding_step"
            ) { step ->
                when (step) {
                    1 -> Step1OrgSetup(
                        orgName = orgName,
                        onOrgNameChange = { orgName = it },
                        selectedOrgType = selectedOrgType,
                        onOrgTypeSelected = { selectedOrgType = it },
                        isDark = isDark
                    )
                    2 -> Step2RosterBootstrap(
                        rosterOption = rosterOption,
                        onOptionSelected = { rosterOption = it },
                        isDark = isDark
                    )
                    3 -> Step3BiometricPolicy(
                        selectedTier = selectedSecurityTier,
                        onTierSelected = { selectedSecurityTier = it },
                        requireTwoFactor = requireTwoFactor,
                        onToggleTwoFactor = { requireTwoFactor = it },
                        isDark = isDark
                    )
                    4 -> Step4ReadyToLaunch(
                        orgName = orgName,
                        orgType = selectedOrgType,
                        rosterOption = rosterOption,
                        securityTier = selectedSecurityTier,
                        isDark = isDark
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Step1OrgSetup(
    orgName: String,
    onOrgNameChange: (String) -> Unit,
    selectedOrgType: OrgType,
    onOrgTypeSelected: (OrgType) -> Unit,
    isDark: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Welcome to OmniFace AI",
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            color = omniTextPrimary(isDark),
            textAlign = TextAlign.Center,
            letterSpacing = (-0.5).sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Set up your organization for sub-100ms offline face attendance",
            fontSize = 13.5.sp,
            color = omniTextMuted(isDark),
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        IOSCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "ORGANIZATION NAME",
                color = OmniViolet,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = orgName,
                onValueChange = onOrgNameChange,
                placeholder = { Text("e.g. Springfield High or Acme Corp", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OmniViolet,
                    unfocusedBorderColor = if (isDark) Color(0x33FFFFFF) else Color(0x22000000)
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "SELECT ORGANIZATION TYPE",
            color = omniTextMuted(isDark),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp)
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OrgType.values().forEach { type ->
                val isSelected = type == selectedOrgType
                IOSCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOrgTypeSelected(type) }
                        .then(
                            if (isSelected) Modifier.border(1.5.dp, OmniViolet, RoundedCornerShape(20.dp))
                            else Modifier
                        )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) OmniViolet.copy(alpha = 0.2f) else if (isDark) Color(0x22FFFFFF) else Color(0x11000000)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                type.icon,
                                contentDescription = null,
                                tint = if (isSelected) OmniViolet else omniTextMuted(isDark),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = type.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.5.sp,
                                color = if (isSelected) omniTextPrimary(isDark) else omniTextSecondary(isDark)
                            )
                            Text(
                                text = type.subtitle,
                                fontSize = 11.sp,
                                color = omniTextMuted(isDark)
                            )
                        }
                        RadioButton(
                            selected = isSelected,
                            onClick = { onOrgTypeSelected(type) },
                            colors = RadioButtonDefaults.colors(selectedColor = OmniViolet)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Step2RosterBootstrap(
    rosterOption: Int,
    onOptionSelected: (Int) -> Unit,
    isDark: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Bootstrap Member Roster",
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            color = omniTextPrimary(isDark),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Choose how to initialize your organization's verified members",
            fontSize = 13.5.sp,
            color = omniTextMuted(isDark),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Option 1: Biometric Studio (Recommended)
        val isOpt1Selected = rosterOption == 1
        IOSCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOptionSelected(1) }
                .then(
                    if (isOpt1Selected) Modifier.border(1.5.dp, OmniViolet, RoundedCornerShape(20.dp))
                    else Modifier
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(OmniViolet.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PersonAdd,
                        contentDescription = null,
                        tint = OmniViolet,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Register Members via Studio",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp,
                            color = omniTextPrimary(isDark)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        IOSGlassPill(text = "Recommended", accentColor = OmniViolet)
                    }
                    Text(
                        text = "Enroll real faces using the 5-angle CameraX biometric studio with hardware Keystore encryption",
                        fontSize = 11.5.sp,
                        color = omniTextMuted(isDark),
                        lineHeight = 15.sp
                    )
                }
                RadioButton(
                    selected = isOpt1Selected,
                    onClick = { onOptionSelected(1) },
                    colors = RadioButtonDefaults.colors(selectedColor = OmniViolet)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Option 2: Import CSV
        val isOpt2Selected = rosterOption == 2
        IOSCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOptionSelected(2) }
                .then(
                    if (isOpt2Selected) Modifier.border(1.5.dp, omniCyan(isDark), RoundedCornerShape(20.dp))
                    else Modifier
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(omniCyan(isDark).copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.UploadFile,
                        contentDescription = null,
                        tint = omniCyan(isDark),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Import Existing CSV Roster",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.5.sp,
                        color = omniTextPrimary(isDark)
                    )
                    Text(
                        text = "Bulk import student roll numbers, names, and departments from spreadsheet",
                        fontSize = 11.5.sp,
                        color = omniTextMuted(isDark)
                    )
                }
                RadioButton(
                    selected = isOpt2Selected,
                    onClick = { onOptionSelected(2) },
                    colors = RadioButtonDefaults.colors(selectedColor = omniCyan(isDark))
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Option 3: Cloud Fleet Sync
        val isOpt3Selected = rosterOption == 3
        IOSCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOptionSelected(3) }
                .then(
                    if (isOpt3Selected) Modifier.border(1.5.dp, omniEmerald(isDark), RoundedCornerShape(20.dp))
                    else Modifier
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(omniEmerald(isDark).copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = omniEmerald(isDark),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cloud Fleet Sync",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.5.sp,
                        color = omniTextPrimary(isDark)
                    )
                    Text(
                        text = "Pair device to pull enrolled members and templates from institution backend",
                        fontSize = 11.5.sp,
                        color = omniTextMuted(isDark)
                    )
                }
                RadioButton(
                    selected = isOpt3Selected,
                    onClick = { onOptionSelected(3) },
                    colors = RadioButtonDefaults.colors(selectedColor = omniEmerald(isDark))
                )
            }
        }
    }
}

@Composable
private fun Step3BiometricPolicy(
    selectedTier: SecurityTier,
    onTierSelected: (SecurityTier) -> Unit,
    requireTwoFactor: Boolean,
    onToggleTwoFactor: (Boolean) -> Unit,
    isDark: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Biometric Security Policy",
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            color = omniTextPrimary(isDark),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Configure anti-spoofing sensitivity and verification rules",
            fontSize = 13.5.sp,
            color = omniTextMuted(isDark),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Tier Options
        val tiers = listOf(
            Triple(
                SecurityTier.STRICT,
                "Bank / Exam Grade (0.80 Match)",
                "Strict 3DMM depth liveness + 0.80 cosine threshold. Zero false positives."
            ),
            Triple(
                SecurityTier.HIGH,
                "Recommended High (0.72 Match)",
                "Balanced for schools & offices. ISO/IEC compliant anti-spoofing."
            ),
            Triple(
                SecurityTier.STANDARD,
                "Standard Transit (0.65 Match)",
                "Sub-60ms recognition for high-density entry gates and rapid check-ins."
            )
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiers.forEach { (tier, title, desc) ->
                val isSelected = tier == selectedTier
                IOSCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTierSelected(tier) }
                        .then(
                            if (isSelected) Modifier.border(1.5.dp, OmniViolet, RoundedCornerShape(20.dp))
                            else Modifier
                        )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = if (isSelected) omniTextPrimary(isDark) else omniTextSecondary(isDark)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = desc,
                                fontSize = 11.sp,
                                color = omniTextMuted(isDark),
                                lineHeight = 15.sp
                            )
                        }
                        RadioButton(
                            selected = isSelected,
                            onClick = { onTierSelected(tier) },
                            colors = RadioButtonDefaults.colors(selectedColor = OmniViolet)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2FA Switch
        IOSCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Require Two-Factor (QR + Face)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = omniTextPrimary(isDark)
                    )
                    Text(
                        text = "Students scan ID card barcode/QR before face verification",
                        fontSize = 11.5.sp,
                        color = omniTextMuted(isDark)
                    )
                }
                Switch(
                    checked = requireTwoFactor,
                    onCheckedChange = onToggleTwoFactor,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = OmniViolet)
                )
            }
        }
    }
}

@Composable
private fun Step4ReadyToLaunch(
    orgName: String,
    orgType: OrgType,
    rosterOption: Int,
    securityTier: SecurityTier,
    isDark: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(omniEmerald(isDark).copy(alpha = 0.2f), OmniViolet.copy(alpha = 0.2f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = omniEmerald(isDark),
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Your Kiosk is Ready!",
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            color = omniTextPrimary(isDark),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "OmniFace AI is primed for secure, on-device biometric check-ins",
            fontSize = 13.5.sp,
            color = omniTextMuted(isDark),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        IOSCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "CONFIGURATION SUMMARY",
                color = OmniViolet,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            SummaryRow("Organization", orgName, isDark)
            SummaryRow("Facility Type", orgType.title, isDark)
            SummaryRow("Security Policy", securityTier.name, isDark)
            SummaryRow("Active Plan", "Free Starter (25 Members)", isDark)
            SummaryRow("Offline Storage", "Hardware Keystore Encrypted", isDark)
            val rosterModeText = when (rosterOption) {
                1 -> "Manual Biometric Capture"
                2 -> "CSV Roster Import"
                else -> "Cloud Fleet Sync"
            }
            SummaryRow("Roster Mode", rosterModeText, isDark)
        }

        Spacer(modifier = Modifier.height(16.dp))

        IOSCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = null,
                    tint = omniEmerald(isDark),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "100% On-Device & Privacy Guaranteed",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = omniTextPrimary(isDark)
                    )
                    Text(
                        text = "Zero raw face photos are stored. Only mathematical 512-D vectors are kept in your local database.",
                        fontSize = 11.sp,
                        color = omniTextMuted(isDark),
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, isDark: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = omniTextMuted(isDark), fontSize = 12.sp)
        Text(value, color = omniTextPrimary(isDark), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
