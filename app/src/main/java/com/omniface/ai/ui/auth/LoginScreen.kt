@file:Suppress("DEPRECATION")

package com.omniface.ai.ui.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.omniface.ai.ui.components.BiometricEnergyOrb
import com.omniface.ai.ui.components.IOSCard
import com.omniface.ai.ui.components.IOSGlassPill
import com.omniface.ai.ui.theme.*

private const val TAG = "LoginScreen"

@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onLoginSuccess: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isDark = LocalThemeIsDark.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var authMode by remember { mutableStateOf<AuthMode>(AuthMode.SIGN_IN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    // Google Sign-In Client & Launcher with real Web Client ID for Firebase Auth token exchange
    val defaultWebClientId = remember(context) {
        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        if (resId != 0) {
            context.getString(resId)
        } else {
            "323760410829-sdff0g8kcgd1nqdjgkp87q56hkplvii7.apps.googleusercontent.com"
        }
    }
    val gso = remember(defaultWebClientId) {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(defaultWebClientId)
            .requestEmail()
            .build()
    }
    val googleSignInClient = remember(context, gso) { GoogleSignIn.getClient(context, gso) }

    val googleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account?.idToken
            if (!idToken.isNullOrEmpty()) {
                viewModel.signInWithGoogle(idToken, onLoginSuccess)
            } else {
                viewModel.showError("Google Sign-In did not return an ID token. Please verify Google Play Services.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In error: ${e.message}", e)
            viewModel.showError(e.localizedMessage ?: "Google Sign-In failed.")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF06080D),
                        Color(0xFF090D15),
                        Color(0xFF04060A)
                    )
                )
            )
            .systemBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // TOP SECTION: Brand Header & Biometric Halo
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
            ) {
                // Biometric Holographic Orb
                Box(
                    modifier = Modifier.size(90.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BiometricEnergyOrb(size = 90.dp, showRings = true)
                }

                Spacer(modifier = Modifier.height(16.dp))

                IOSGlassPill(
                    text = "✦ SOVEREIGN FLEET AUTHORITY",
                    accentColor = omniEmerald(isDark)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "OmniFace AI",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (state.verificationPending) "Email Verification Required"
                    else if (authMode == AuthMode.SIGN_IN) "Offline-First Biometric Kiosk & Fleet Access"
                    else "Register New Organization Terminal",
                    color = omniTextMuted(isDark),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }

            // MIDDLE SECTION: Dynamic Content (Verification vs Auth Forms)
            AnimatedContent(
                targetState = state.verificationPending,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "auth_flow_animation"
            ) { isPending ->
                if (isPending) {
                    // VIEW: GMAIL VERIFICATION REQUIRED
                    IOSCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 24.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(omniEmerald(isDark).copy(alpha = 0.15f))
                                    .border(1.dp, omniEmerald(isDark).copy(alpha = 0.4f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MarkEmailRead,
                                    contentDescription = null,
                                    tint = omniEmerald(isDark),
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Verify Your Gmail / Email",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "A verification link was sent to:",
                                color = omniTextMuted(isDark),
                                fontSize = 12.5.sp
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = state.unverifiedEmail.ifEmpty { state.userEmail },
                                color = omniEmerald(isDark),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(omniEmerald(isDark).copy(alpha = 0.12f))
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            // Action: Open Gmail or Email App
                            Button(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_MAIN).apply {
                                        addCategory(Intent.CATEGORY_APP_EMAIL)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com"))
                                        context.startActivity(webIntent)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFEA4335).copy(alpha = 0.15f),
                                    contentColor = Color.White
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEA4335).copy(alpha = 0.4f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Email,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = Color(0xFFEA4335)
                                    )
                                    Text("Open Gmail Inbox", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Action: I've Verified My Email
                            Button(
                                onClick = { viewModel.checkEmailVerification(onLoginSuccess) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = omniEmerald(isDark),
                                    contentColor = Color.White
                                )
                            ) {
                                Text(
                                    if (state.isLoading) "Verifying..." else "I've Verified My Email",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.5.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (state.resendCooldown > 0) "Resend in ${state.resendCooldown}s" else "Resend verification",
                                    color = if (state.resendCooldown > 0) omniTextMuted(isDark) else omniCyan(isDark),
                                    fontSize = 12.sp,
                                    modifier = Modifier.clickable(enabled = state.resendCooldown == 0) {
                                        viewModel.resendVerificationEmail()
                                    }
                                )

                                Text(
                                    text = "← Back to Login",
                                    color = omniTextMuted(isDark),
                                    fontSize = 12.sp,
                                    modifier = Modifier.clickable { viewModel.dismissVerification() }
                                )
                            }
                        }
                    }
                } else {
                    // VIEW: LOGIN / REGISTER MAIN TERMINAL
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Segmented Mode Control
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF0F172A).copy(alpha = 0.8f))
                                .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(14.dp))
                                .padding(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (authMode == AuthMode.SIGN_IN) omniEmerald(isDark).copy(alpha = 0.2f)
                                        else Color.Transparent
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        authMode = AuthMode.SIGN_IN
                                        viewModel.clearError()
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Sign In",
                                    color = if (authMode == AuthMode.SIGN_IN) Color.White else omniTextMuted(isDark),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (authMode == AuthMode.REGISTER) omniCyan(isDark).copy(alpha = 0.2f)
                                        else Color.Transparent
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        authMode = AuthMode.REGISTER
                                        viewModel.clearError()
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Register Fleet",
                                    color = if (authMode == AuthMode.REGISTER) Color.White else omniTextMuted(isDark),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Error Banner
                        state.errorMessage?.let { error ->
                            Surface(
                                color = Color(0x33EF4444),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x55EF4444)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Outlined.ErrorOutline, null, tint = Color(0xFFFCA5A5), modifier = Modifier.size(16.dp))
                                    Text(error, color = Color(0xFFFCA5A5), fontSize = 12.sp)
                                }
                            }
                        }

                        // Hero CTA: CONTINUE WITH GOOGLE
                        Button(
                            onClick = {
                                val signInIntent = googleSignInClient.signInIntent
                                googleLauncher.launch(signInIntent)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0x1FFFFFFF),
                                contentColor = Color.White
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                Brush.horizontalGradient(
                                    listOf(Color(0x334285F4), Color(0x3334A853), Color(0x33FBBC05), Color(0x33EA4335))
                                )
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // Google G Icon Indicator
                                Surface(
                                    shape = CircleShape,
                                    color = Color.White,
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.Black, fontSize = 14.sp)
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Text(
                                    text = "Continue with Google",
                                    color = Color.White,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(omniEmerald(isDark).copy(alpha = 0.2f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Gmail Verified",
                                        color = omniEmerald(isDark),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Hairline Divider
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0x1AFFFFFF))
                            Text(
                                text = "  OR BIOMETRIC CREDENTIALS  ",
                                color = omniTextMuted(isDark),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp
                            )
                            HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0x1AFFFFFF))
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Email Field
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            placeholder = { Text("admin@institution.edu", color = omniTextMuted(isDark), fontSize = 13.5.sp) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Email, null, tint = omniEmerald(isDark), modifier = Modifier.size(20.dp))
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = omniEmerald(isDark),
                                unfocusedBorderColor = Color(0x26FFFFFF),
                                focusedContainerColor = Color(0x1A0F172A),
                                unfocusedContainerColor = Color(0x1A0F172A),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Password Field
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            placeholder = { Text("••••••••••••", color = omniTextMuted(isDark), fontSize = 13.5.sp) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Lock, null, tint = omniCyan(isDark), modifier = Modifier.size(20.dp))
                            },
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        imageVector = if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = null,
                                        tint = omniTextMuted(isDark),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            },
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                focusManager.clearFocus()
                                if (authMode == AuthMode.SIGN_IN) {
                                    viewModel.signInWithEmail(email, password, onLoginSuccess)
                                } else {
                                    viewModel.signUpWithEmail(email, password)
                                }
                            }),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = omniCyan(isDark),
                                unfocusedBorderColor = Color(0x26FFFFFF),
                                focusedContainerColor = Color(0x1A0F172A),
                                unfocusedContainerColor = Color(0x1A0F172A),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        // Authenticate CTA Button
                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                if (authMode == AuthMode.SIGN_IN) {
                                    viewModel.signInWithEmail(email, password, onLoginSuccess)
                                } else {
                                    viewModel.signUpWithEmail(email, password)
                                }
                            },
                            enabled = !state.isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = omniEmerald(isDark),
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                text = if (state.isLoading) "Authenticating..."
                                else if (authMode == AuthMode.SIGN_IN) "Sign In to Terminal"
                                else "Create Administrator Account",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.5.sp
                            )
                        }


                    }
                }
            }

            // BOTTOM SECTION: Sovereign Edge Cryptography Footnote
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Security,
                        contentDescription = null,
                        tint = omniTextMuted(isDark),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "Hardware Keystore & On-Device LiteRT Biometrics",
                        color = omniTextMuted(isDark),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

private enum class AuthMode {
    SIGN_IN,
    REGISTER
}
