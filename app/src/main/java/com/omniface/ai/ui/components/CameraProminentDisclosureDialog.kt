package com.omniface.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniface.ai.ui.theme.LocalThemeIsDark
import com.omniface.ai.ui.theme.omniBackground
import com.omniface.ai.ui.theme.omniEmerald
import com.omniface.ai.ui.theme.omniTextPrimary
import com.omniface.ai.ui.theme.omniTextSecondary

/**
 * Google Play Policy Prominent Disclosure & Consent Modal.
 *
 * Explicitly informs the user prior to triggering the Android runtime CAMERA permission:
 * 1. Specific technical purpose (biometric attendance and liveness anti-spoofing).
 * 2. 100% on-device processing in volatile RAM.
 * 3. Zero transmission or cloud storage of raw facial images.
 */
@Composable
fun CameraProminentDisclosureDialog(
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = LocalThemeIsDark.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (isDark) Color(0xFF0F172A) else Color(0xFFFFFFFF),
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF10B981), Color(0xFF06B6D4))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Camera",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column {
                    Text(
                        text = "Camera Access Required",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = omniTextPrimary(isDark)
                    )
                    Text(
                        text = "Biometric Verification & Liveness",
                        fontSize = 12.sp,
                        color = omniEmerald(isDark),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "OmniFace AI requires access to your camera to enable on-device facial recognition and kiosk attendance verification.",
                    fontSize = 13.sp,
                    color = omniTextPrimary(isDark),
                    lineHeight = 18.sp
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isDark) Color(0x33000000) else Color(0x0A000000))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text("• ", fontWeight = FontWeight.Bold, color = omniEmerald(isDark))
                        Text(
                            "Real-Time Authentication: Used solely to match registered profiles and defeat photo/video spoofing.",
                            fontSize = 12.sp,
                            color = omniTextSecondary(isDark)
                        )
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        Text("• ", fontWeight = FontWeight.Bold, color = omniEmerald(isDark))
                        Text(
                            "100% On-Device Processing: Video frames are analyzed in volatile RAM and immediately discarded. No photos are saved to your gallery.",
                            fontSize = 12.sp,
                            color = omniTextSecondary(isDark)
                        )
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        Text("• ", fontWeight = FontWeight.Bold, color = omniEmerald(isDark))
                        Text(
                            "Zero Cloud Uploads: Raw images are never transmitted to external servers, cloud providers, or advertisers.",
                            fontSize = 12.sp,
                            color = omniTextSecondary(isDark)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = omniEmerald(isDark)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Continue & Grant Access", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not Now", color = omniTextSecondary(isDark))
            }
        }
    )
}
