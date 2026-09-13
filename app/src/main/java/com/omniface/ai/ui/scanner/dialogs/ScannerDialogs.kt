package com.omniface.ai.ui.scanner.dialogs

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniface.ai.ml.HardwareTier
import com.omniface.ai.hardware.NpuHardwareDetector
import com.omniface.ai.hardware.ThermalState
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey
import com.omniface.ai.ui.components.CupertinoButton
import com.omniface.ai.ui.theme.*

@Composable
internal fun ModelManagerDialog(
    isDark: Boolean,
    config: com.omniface.ai.ml.NeuralModelConfig,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF8B5CF6))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "NEURAL MODEL MANAGER",
                    color = omniTextPrimary(isDark),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = "Toggle auxiliary neural models on/off in real-time. Core Face Detection & ArcFace 512-D remain active.",
                        color = omniTextSecondary(isDark),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }

                // Core Baseline
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDark) Color(0x1F1E293B) else Color(0x0A000000))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Core Face Detection & Recognition", color = omniTextPrimary(isDark), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("ML Kit Face + ArcFace 512-D Embedding", color = omniTextMuted(isDark), fontSize = 10.sp)
                        }
                        Text("ACTIVE", color = Color(0xFF10B981), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // MiniFASNet PAD
                item {
                    ModelToggleRow(
                        title = "MiniFASNetV2 Passive PAD",
                        subtitle = "Neural Screen / Photo Anti-Spoofing",
                        checked = config.isPassivePadEnabled,
                        onCheckedChange = { com.omniface.ai.ml.NeuralModelConfigManager.setPassivePadEnabled(it) },
                        isDark = isDark,
                        tint = Color(0xFF007AFF)
                    )
                }

                // Multi-Stage Texture & Glare
                item {
                    ModelToggleRow(
                        title = "Multi-Stage Texture / Glare",
                        subtitle = "LBP Texture Entropy & Specular Analysis",
                        checked = config.isMultiStageLivenessEnabled,
                        onCheckedChange = { com.omniface.ai.ml.NeuralModelConfigManager.setMultiStageLivenessEnabled(it) },
                        isDark = isDark,
                        tint = Color(0xFF06B6D4)
                    )
                }

                // Temporal Liveness
                item {
                    ModelToggleRow(
                        title = "Temporal Micro-Motion",
                        subtitle = "Optical Flow & Blink Continuity",
                        checked = config.isTemporalLivenessEnabled,
                        onCheckedChange = { com.omniface.ai.ml.NeuralModelConfigManager.setTemporalLivenessEnabled(it) },
                        isDark = isDark,
                        tint = Color(0xFF3B82F6)
                    )
                }



                // Dynamic Centroid Adaptation
                item {
                    ModelToggleRow(
                        title = "Dynamic Centroid Adaptation",
                        subtitle = "Continuous Learning on Verified Scans",
                        checked = config.isDynamicCentroidAdaptationEnabled,
                        onCheckedChange = { com.omniface.ai.ml.NeuralModelConfigManager.setDynamicCentroidAdaptationEnabled(it) },
                        isDark = isDark,
                        tint = Color(0xFF10B981)
                    )
                }

                // FAISS HNSW Index
                item {
                    ModelToggleRow(
                        title = "FAISS / HNSW Vector Index",
                        subtitle = "Sub-Millisecond Candidate Search",
                        checked = config.isFaissHnswIndexEnabled,
                        onCheckedChange = { com.omniface.ai.ml.NeuralModelConfigManager.setFaissHnswIndexEnabled(it) },
                        isDark = isDark,
                        tint = Color(0xFF8B5CF6)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(LocalizationManager.get(StringKey.CLOSE_ACTION), color = omniCyan(isDark), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = { com.omniface.ai.ml.NeuralModelConfigManager.resetToDefaults() }
            ) {
                Text("Reset Defaults", color = Color(0xFFEF4444), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        containerColor = if (isDark) Color(0xFF141A29) else Color(0xFFFFFFFF),
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
internal fun ModelToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isDark: Boolean,
    tint: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isDark) Color(0x1F1E293B) else Color(0x0A000000))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = omniTextPrimary(isDark), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(1.dp))
            Text(subtitle, color = omniTextMuted(isDark), fontSize = 10.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = tint
            ),
            modifier = Modifier.height(28.dp)
        )
    }
}

@Composable
internal fun HardwareSwitcherDialog(
    isDark: Boolean,
    currentTier: String,
    onDismiss: () -> Unit,
    onSelectTier: (HardwareTier) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF00E5FF))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "NEURAL HARDWARE ACCELERATOR",
                    color = omniTextPrimary(isDark),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Select active silicon execution engine for unified multi-task biometrics:",
                    color = omniTextSecondary(isDark),
                    fontSize = 12.sp
                )

                val detectedNpu = remember { NpuHardwareDetector.detectNpuHardware() }
                val npuTitle = "⚡ ${detectedNpu.npuName}"
                val npuDesc = "Per-Channel INT8 Quantized • Sub-8ms • ${detectedNpu.peakTops} peak"
                val gpuTitle = when {
                    detectedNpu.socManufacturer.contains("Qualcomm", ignoreCase = true) -> "🚀 Qualcomm Adreno GPU"
                    detectedNpu.socManufacturer.contains("MediaTek", ignoreCase = true) -> "🚀 ARM Mali / Immortalis GPU"
                    detectedNpu.socManufacturer.contains("Google", ignoreCase = true) -> "🚀 Mali-G715 / G710 GPU"
                    detectedNpu.socManufacturer.contains("Samsung", ignoreCase = true) -> "🚀 Samsung Xclipse GPU"
                    else -> "🚀 Mobile GPU Delegate"
                }
                val options = listOf(
                    Triple(
                        HardwareTier.NPU_NNAPI,
                        npuTitle,
                        npuDesc
                    ),
                    Triple(
                        HardwareTier.GPU_DELEGATE,
                        gpuTitle,
                        "FP16 Accelerated • OpenCL/Vulkan Hardware Delegate"
                    ),
                    Triple(
                        HardwareTier.CPU_XNNPACK,
                        "⚙️ ARM64 Multi-Core CPU",
                        "Multi-Threaded XNNPACK (4 Threads) • NEON DotProd"
                    )
                )

                options.forEach { (tier, title, desc) ->
                    val isSelected = currentTier.contains(tier.name.take(3), ignoreCase = true) ||
                        (tier == HardwareTier.NPU_NNAPI && (currentTier.contains("NPU", ignoreCase = true) || currentTier.contains("Hexagon", ignoreCase = true) || currentTier.contains("APU", ignoreCase = true)))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSelected) (if (isDark) Color(0x33007AFF) else Color(0x1A007AFF)) else (if (isDark) Color(0x1F1E293B) else Color(0x0A000000)))
                            .border(if (isSelected) 1.5.dp else 0.75.dp, if (isSelected) Color(0xFF007AFF) else (if (isDark) Color(0x38FFFFFF) else Color(0x1A000000)), RoundedCornerShape(14.dp))
                            .clickable { onSelectTier(tier) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(title, color = omniTextPrimary(isDark), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(desc, color = omniTextMuted(isDark), fontSize = 10.sp)
                        }
                        if (isSelected) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Active", tint = Color(0xFF007AFF), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = omniCyan(isDark), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = if (isDark) Color(0xFF141A29) else Color(0xFFFFFFFF),
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
internal fun ManualOverrideDialog(
    isDark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var roll by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    // Hoist context here — LocalContext.current cannot be called inside onClick lambdas
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(LocalizationManager.get(StringKey.MANUAL_TRIGGER), color = omniTextPrimary(isDark), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    LocalizationManager.get(StringKey.ADMIN_PIN),
                    color = omniTextSecondary(isDark),
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = roll,
                    onValueChange = { roll = it },
                    label = { Text(LocalizationManager.get(StringKey.ROLL_NUMBER), fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(LocalizationManager.get(StringKey.FULL_NAME), fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text(LocalizationManager.get(StringKey.ADMIN_PIN), fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                if (errorText != null) {
                    Text(errorText!!, color = Color(0xFFEF4444), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            CupertinoButton(
                modifier = Modifier.width(110.dp),
                text = LocalizationManager.get(StringKey.CONFIRM_ACTION),
                brush = OmniButtonBrush,
                height = 38.dp,
                onClick = {
                    // Unified PBKDF2 + constant-time verification via KioskLockController
                    val lockoutActive = com.omniface.ai.hardware.KioskLockController.isLockedOut()
                    val pinOk = !lockoutActive &&
                        com.omniface.ai.hardware.KioskLockController.verifyAdminPin(context, pin)
                    if (lockoutActive) {
                        errorText = "Too many attempts — wait for lockout to expire"
                    } else if (!pinOk) {
                        errorText = "Invalid Admin PIN"
                    } else if (roll.isBlank() || name.isBlank()) {
                        errorText = "Please fill in Roll and Name"
                    } else {
                        onConfirm(roll.trim(), name.trim())
                    }
                }
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(LocalizationManager.get(StringKey.CANCEL_ACTION), color = omniTextMuted(isDark), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        containerColor = if (isDark) Color(0xFF141A29) else Color(0xFFFFFFFF),
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
internal fun ThermalGovernorDialog(
    isDark: Boolean,
    thermalState: ThermalState,
    temperature: Float,
    isAutoScalingEnabled: Boolean,
    onDismiss: () -> Unit,
    onToggleAutoScaling: (Boolean) -> Unit,
    onSimulateState: (ThermalState?) -> Unit
) {
    val stateColor = when (thermalState) {
        ThermalState.NOMINAL -> Color(0xFF34C759)
        ThermalState.WARM -> Color(0xFFFF9F0A)
        ThermalState.CRITICAL -> Color(0xFFFF453A)
    }

    val stateIcon = when (thermalState) {
        ThermalState.NOMINAL -> "❄️"
        ThermalState.WARM -> "⚡"
        ThermalState.CRITICAL -> "🔥"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = stateIcon, fontSize = 20.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "HARDWARE THERMAL GOVERNOR",
                        color = omniTextPrimary(isDark),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Dynamic Face Detector Resolution Scaling",
                        color = omniTextMuted(isDark),
                        fontSize = 11.sp
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Current Temperature Gauge Card
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isDark) Color(0x331E293B) else Color(0x0D000000))
                        .border(1.dp, stateColor.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "DEVICE TEMPERATURE",
                            color = omniTextMuted(isDark),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "%.1f°C".format(temperature),
                            color = stateColor,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(stateColor.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = thermalState.name,
                                color = stateColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${thermalState.targetResolution.width}×${thermalState.targetResolution.height} • ${thermalState.maxFps} FPS",
                            color = omniTextSecondary(isDark),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // 2. Auto Dynamic Resolution Scaling Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isDark) Color(0x1F1E293B) else Color(0x08000000))
                        .border(0.75.dp, if (isDark) Color(0x38FFFFFF) else Color(0x1A000000), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto Resolution Scaling",
                            color = omniTextPrimary(isDark),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Downscale ML input buffer dynamically to prevent thermal throttling and battery drain.",
                            color = omniTextMuted(isDark),
                            fontSize = 10.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = isAutoScalingEnabled,
                        onCheckedChange = { onToggleAutoScaling(it) }
                    )
                }

                // 3. Operating Point Resolution Breakdown
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "SCALING PROFILES",
                        color = omniTextMuted(isDark),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )

                    val profiles = listOf(
                        Triple(ThermalState.NOMINAL, "640×480 @ 30 FPS", "< 38.0°C • Peak Accuracy & Full Frame Rate"),
                        Triple(ThermalState.WARM, "480×360 @ 20 FPS", "38.0°C–42.0°C • -45% Compute Load"),
                        Triple(ThermalState.CRITICAL, "320×240 @ 10 FPS", "> 42.0°C • -75% Emergency Thermal Guard")
                    )

                    profiles.forEach { (state, title, desc) ->
                        val isCurrent = thermalState == state
                        val borderCol = if (isCurrent) stateColor else if (isDark) Color(0x1FFFFFFF) else Color(0x0A000000)
                        val bgCol = if (isCurrent) stateColor.copy(alpha = 0.08f) else Color.Transparent

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(bgCol)
                                .border(if (isCurrent) 1.dp else 0.5.dp, borderCol, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "${state.name}: $title",
                                    color = if (isCurrent) stateColor else omniTextPrimary(isDark),
                                    fontSize = 11.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                                )
                                Text(
                                    text = desc,
                                    color = omniTextMuted(isDark),
                                    fontSize = 9.sp
                                )
                            }
                            if (isCurrent) {
                                Text(
                                    text = "ACTIVE",
                                    color = stateColor,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }

                // 4. Hardware Simulation Mode
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "THERMAL TESTING & SIMULATION",
                        color = omniTextMuted(isDark),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { onSimulateState(ThermalState.NOMINAL) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isDark) Color(0x3334C759) else Color(0x1A34C759)),
                            shape = CircleShape
                        ) {
                            Text("❄️ Nominal", color = Color(0xFF34C759), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { onSimulateState(ThermalState.WARM) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isDark) Color(0x33FF9F0A) else Color(0x1AFF9F0A)),
                            shape = CircleShape
                        ) {
                            Text("⚡ Warm", color = Color(0xFFFF9F0A), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { onSimulateState(ThermalState.CRITICAL) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isDark) Color(0x33FF453A) else Color(0x1AFF453A)),
                            shape = CircleShape
                        ) {
                            Text("🔥 Critical", color = Color(0xFFFF453A), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { onSimulateState(null) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isDark) Color(0x33007AFF) else Color(0x1A007AFF)),
                            shape = CircleShape
                        ) {
                            Text("🔄 Auto", color = Color(0xFF007AFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            CupertinoButton(
                modifier = Modifier.width(90.dp),
                text = "Close",
                brush = Brush.horizontalGradient(listOf(stateColor, stateColor.copy(alpha = 0.85f))),
                height = 38.dp,
                onClick = onDismiss
            )
        },
        containerColor = if (isDark) Color(0xFF141A29) else Color(0xFFFFFFFF),
        shape = RoundedCornerShape(24.dp)
    )
}

