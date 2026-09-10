package com.omniface.ai.ui.auth

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "AuthViewModel"
private const val PREFS_NAME = "omniface_auth_vault"
private const val KEY_AUTHENTICATED = "is_authenticated"
private const val KEY_USER_EMAIL = "user_email"
private const val KEY_USER_NAME = "user_name"
private const val KEY_USER_UID = "user_uid"

data class AuthUiState(
    val isAuthenticated: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val verificationPending: Boolean = false,
    val unverifiedEmail: String = "",
    val userEmail: String = "",
    val userName: String = "",
    val resendCooldown: Int = 0
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val prefs by lazy {
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _uiState = MutableStateFlow(
        AuthUiState(
            isAuthenticated = prefs.getBoolean(KEY_AUTHENTICATED, false),
            userEmail = prefs.getString(KEY_USER_EMAIL, "") ?: "",
            userName = prefs.getString(KEY_USER_NAME, "") ?: ""
        )
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        checkCurrentAuthSession()
    }

    fun checkCurrentAuthSession() {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            currentUser.reload().addOnCompleteListener { task ->
                val isVerified = currentUser.isEmailVerified
                if (isVerified) {
                    persistUserSession(currentUser)
                    _uiState.update {
                        it.copy(
                            isAuthenticated = true,
                            verificationPending = false,
                            userEmail = currentUser.email ?: "",
                            userName = currentUser.displayName ?: currentUser.email?.substringBefore('@') ?: "Operator"
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isAuthenticated = false,
                            verificationPending = true,
                            unverifiedEmail = currentUser.email ?: ""
                        )
                    }
                }
            }
        } else if (prefs.getBoolean(KEY_AUTHENTICATED, false)) {
            // Already authenticated locally
            _uiState.update { it.copy(isAuthenticated = true) }
        }
    }

    /**
     * Sign In with Google OAuth ID token obtained from Google Play Services
     */
    fun signInWithGoogle(idToken: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                val authResult = auth.signInWithCredential(credential).await()
                val user = authResult.user

                if (user != null) {
                    persistUserSession(user)
                    _uiState.update {
                        it.copy(
                            isAuthenticated = true,
                            isLoading = false,
                            verificationPending = false,
                            userEmail = user.email ?: "",
                            userName = user.displayName ?: user.email?.substringBefore('@') ?: "Operator"
                        )
                    }
                    onComplete()
                } else {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Google sign-in returned empty user.") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Google sign-in error: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.localizedMessage ?: "Google sign-in failed. Please retry."
                    )
                }
            }
        }
    }

    /**
     * Email & Password Sign-In
     */
    fun signInWithEmail(email: String, pass: String, onComplete: () -> Unit) {
        if (email.isBlank() || pass.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter both email and password.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val result = auth.signInWithEmailAndPassword(email.trim(), pass).await()
                val user = result.user

                if (user != null) {
                    if (user.isEmailVerified) {
                        persistUserSession(user)
                        _uiState.update {
                            it.copy(
                                isAuthenticated = true,
                                isLoading = false,
                                verificationPending = false,
                                userEmail = user.email ?: "",
                                userName = user.displayName ?: user.email?.substringBefore('@') ?: "Operator"
                            )
                        }
                        onComplete()
                    } else {
                        // Email verification required
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                verificationPending = true,
                                unverifiedEmail = user.email ?: email.trim()
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Email sign-in failed: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.localizedMessage ?: "Invalid credentials. Please verify."
                    )
                }
            }
        }
    }

    /**
     * Email & Password Sign-Up with automatic verification email dispatch
     */
    fun signUpWithEmail(email: String, pass: String) {
        if (email.isBlank() || pass.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please fill in all fields.") }
            return
        }
        if (pass.length < 6) {
            _uiState.update { it.copy(errorMessage = "Password must be at least 6 characters.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val result = auth.createUserWithEmailAndPassword(email.trim(), pass).await()
                val user = result.user
                if (user != null) {
                    user.sendEmailVerification().await()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            verificationPending = true,
                            unverifiedEmail = email.trim(),
                            resendCooldown = 60
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Registration error: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.localizedMessage ?: "Registration failed."
                    )
                }
            }
        }
    }

    /**
     * Resend verification email
     */
    fun resendVerificationEmail() {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            try {
                user.sendEmailVerification().await()
                _uiState.update { it.copy(resendCooldown = 60) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resend verification: ${e.message}")
            }
        }
    }

    /**
     * Check if email has been verified
     */
    fun checkEmailVerification(onVerified: () -> Unit) {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                user.reload().await()
                if (user.isEmailVerified) {
                    persistUserSession(user)
                    _uiState.update {
                        it.copy(
                            isAuthenticated = true,
                            isLoading = false,
                            verificationPending = false,
                            userEmail = user.email ?: "",
                            userName = user.displayName ?: user.email?.substringBefore('@') ?: "Operator"
                        )
                    }
                    onVerified()
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Email not verified yet. Please click the link in your inbox."
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Could not verify email status. Check internet connection."
                    )
                }
            }
        }
    }

    fun dismissVerification() {
        _uiState.update { it.copy(verificationPending = false, errorMessage = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Sign in using verified Google account info from device account picker
     */
    fun signInWithGoogleAccountInfo(email: String, displayName: String, onComplete: () -> Unit) {
        val resolvedEmail = email.ifBlank { "operator@omniface.ai" }
        val resolvedName = displayName.ifBlank { resolvedEmail.substringBefore('@') }
        prefs.edit().apply {
            putBoolean(KEY_AUTHENTICATED, true)
            putString(KEY_USER_EMAIL, resolvedEmail)
            putString(KEY_USER_NAME, resolvedName)
            putString(KEY_USER_UID, "google_${resolvedEmail.hashCode()}")
            apply()
        }
        _uiState.update {
            it.copy(
                isAuthenticated = true,
                isLoading = false,
                verificationPending = false,
                userEmail = resolvedEmail,
                userName = resolvedName
            )
        }
        onComplete()
    }

    /**
     * Quick Institutional / Dev Bypass
     */
    fun bypassForDevelopment(onComplete: () -> Unit) {
        prefs.edit().apply {
            putBoolean(KEY_AUTHENTICATED, true)
            putString(KEY_USER_EMAIL, "admin@omniface.ai")
            putString(KEY_USER_NAME, "Institutional Admin")
            apply()
        }
        _uiState.update {
            it.copy(
                isAuthenticated = true,
                userEmail = "admin@omniface.ai",
                userName = "Institutional Admin"
            )
        }
        onComplete()
    }

    fun signOut() {
        auth.signOut()
        prefs.edit().clear().apply()
        _uiState.update {
            AuthUiState(
                isAuthenticated = false,
                userEmail = "",
                userName = ""
            )
        }
    }

    private fun persistUserSession(user: FirebaseUser) {
        prefs.edit().apply {
            putBoolean(KEY_AUTHENTICATED, true)
            putString(KEY_USER_EMAIL, user.email ?: "")
            putString(KEY_USER_NAME, user.displayName ?: user.email?.substringBefore('@') ?: "Operator")
            putString(KEY_USER_UID, user.uid)
            apply()
        }
    }
}
