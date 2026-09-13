package com.omniface.ai.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.omniface.ai.i18n.languages.*

/**
 * 10 Major Indian Languages supported by OmniFace AI.
 */
enum class AppLanguage(val code: String, val displayName: String, val nativeName: String) {
    ENGLISH("en", "English", "English"),
    HINDI("hi", "Hindi", "हिन्दी"),
    KANNADA("kn", "Kannada", "ಕನ್ನಡ"),
    TAMIL("ta", "Tamil", "தமிழ்"),
    TELUGU("te", "Telugu", "తెలుగు"),
    MALAYALAM("ml", "Malayalam", "മലയാളം"),
    BENGALI("bn", "Bengali", "বাংলা"),
    MARATHI("mr", "Marathi", "मराठी"),
    GUJARATI("gu", "Gujarati", "ગુજરાતી"),
    PUNJABI("pa", "Punjabi", "ਪੰਜਾਬੀ")
}

/**
 * Strongly typed String Keys for all localized UI text across OmniFace AI.
 */
enum class StringKey {
    // Navigation
    TAB_OVERVIEW,
    TAB_SCANNER,
    TAB_STUDENTS,
    TAB_LEDGER,
    TAB_SETTINGS,

    // Scanner UI
    SCANNER_TITLE,
    READY_TO_SCAN,
    STUDENTS_ENROLLED,
    MANAGE_DATABASE,
    RECOGNITION_CONFIRMED,
    VERIFICATION_FAILED,
    SPOOF_DETECTED,
    VIEW_FULL_PROFILE,
    MODE_SINGLE,
    LENS_FRONT,
    LENS_REAR,
    STATUS_ACTIVE,
    STATUS_PAUSED,
    MODELS_SUITE,
    MANUAL_TRIGGER,
    TIER_STANDARD,
    TIER_HIGH,
    TIER_STRICT,

    // Student Profile & Identification
    STUDENT_DETAILS,
    FULL_NAME,
    ROLL_NUMBER,
    DEPARTMENT,
    SEMESTER,
    ENROLLED_ON,
    VAULT_STATUS,
    AES_ENCRYPTED,
    CONFIDENCE_SCORE,
    MERKLE_ROOT_HASH,
    EDIT_PROFILE,
    DELETE_IDENTITY,

    // Enrollment Studio
    ENROLLMENT_TITLE,
    STUDENT_IDENTITY_DETAILS,
    BEGIN_FACE_ENROLLMENT,
    SINGLE_SHOT_BADGE,
    SINGLE_SHOT_DESC,
    BURST_STUDIO_BUTTON,
    BURST_STUDIO_BADGE,
    BURST_STUDIO_DESC,
    REGISTERED_IDENTITIES,
    SEARCH_STUDENTS,
    DUPLICATE_ALERT_TITLE,
    DUPLICATE_ALERT_DESC,
    OVERWRITE_EXISTING,
    CANCEL_ACTION,

    // 5-Angle Burst Studio
    BURST_STUDIO_HEADER,
    BURST_POSE_GUIDE,
    BURST_SHOT_PROGRESS,
    BURST_POSE_CENTER,
    BURST_POSE_LEFT,
    BURST_POSE_RIGHT,
    BURST_POSE_UP,
    BURST_POSE_DOWN,
    BURST_CAPTURE_SHOT,
    BURST_PROCESSING,
    BURST_COMPUTING_CENTROID,

    // Deduplication Studio
    DEDUP_STUDIO_TITLE,
    DEDUP_SCAN_BUTTON,
    DEDUP_SCANNING,
    DEDUP_NO_DUPLICATES,
    DEDUP_FOUND_CLUSTERS,
    DEDUP_MERGE_RECORDS,
    DEDUP_PURGE_RECORD,

    // Ledger
    LEDGER_TITLE,
    LEDGER_SUBTITLE,
    SEARCH_RECORDS,
    EXPORT_CSV,
    VERIFIED_BADGE,
    CRYPTOGRAPHIC_PROOF,

    // Overview
    OVERVIEW_TITLE,
    OVERVIEW_SUBTITLE,
    ATTENDANCE_TODAY,
    VERIFICATION_SPEED,
    NPU_ACCELERATION,
    SYSTEM_HEALTH,
    RECENT_VERIFICATIONS,

    // Settings Master Hub
    SETTINGS_TITLE,
    SETTINGS_SUBTITLE,
    CAT_APPEARANCE,
    CAT_APPEARANCE_DESC,
    CAT_BIOMETRICS,
    CAT_BIOMETRICS_DESC,
    CAT_NEURAL,
    CAT_NEURAL_DESC,
    CAT_QUALCOMM,
    CAT_QUALCOMM_DESC,
    CAT_KIOSK,
    CAT_KIOSK_DESC,
    CAT_DATA,
    CAT_DATA_DESC,

    // Settings Subtabs
    THEME_SETTING,
    THEME_DARK,
    THEME_LIGHT,
    THEME_SYSTEM,
    LANGUAGE_SETTING,
    ACOUSTIC_MODE_SETTING,
    ACOUSTIC_HALLWAY,
    ACOUSTIC_CLASSROOM,
    ACOUSTIC_SILENT,
    DECISION_TIER_SETTING,
    TWO_FACTOR_QR,
    TWO_FACTOR_QR_DESC,
    KEYSTORE_SECURITY,
    KEYSTORE_SECURITY_DESC,
    NPU_BENCHMARK,
    MODEL_READY,
    DOWNLOAD_MODEL,
    REMOVE_MODEL,
    TURNSTILE_RELAY,
    KIOSK_LOCK,
    BACKUP_DATABASE,
    EXPORT_COMPLIANCE_PDF,
    PURGE_DPDP_RETENTION,
    WIPE_LEDGER,
    // Additional Dedicated Settings & Dedup Keys
    DEDUP_STUDIO_SUBTITLE,
    DEDUP_CLEAN_DESC,
    SETTINGS_APPEARANCE_TITLE,
    SETTINGS_APPEARANCE_SUBTITLE,
    SETTINGS_THEME_TITLE,
    SETTINGS_THEME_SUBTITLE,
    SETTINGS_LANGUAGE_TITLE,
    SETTINGS_LANGUAGE_SUBTITLE,
    SETTINGS_SOUND_TITLE,
    SETTINGS_SOUND_SUBTITLE,
    SETTINGS_DATA_GOVERNANCE_TITLE,
    SETTINGS_DATA_GOVERNANCE_SUBTITLE,
    DEDUP_MERGE_ACTION,
    DEDUP_UNLINK_ACTION,
    DEDUP_DISMISS_ACTION,
    DEDUP_PRIMARY_LABEL,
    DEDUP_MATCH_LABEL,
    DEDUP_RESCAN_BUTTON,
    DEDUP_ENROLLED_COUNT,
    DEDUP_COLLISIONS_HEADER,
    // Dedicated Biometric Tier & Hardware Cryptography Keys
    TIER_STANDARD_DESC,
    TIER_HIGH_DESC,
    TIER_STRICT_DESC,
    ISO_OPERATING_POINTS,
    STRONGBOX_ACTIVE,
    TEE_ACTIVE,
    KEYSTORE_EXPLANATION,
    MERKLE_ROOT_PROOF,
    DOOR_PULSE_ACTION,
    DOOR_UNLOCKED,
    RELAY_OPEN,
    RELAY_LATCHED,
    KIOSK_SELF_TEST,
    KIOSK_SELF_TEST_DESC,
    RUN_TEST,
    TESTING,
    BLE_FLEET_MESH,
    BLE_FLEET_MESH_DESC,
    VIEW_MESH,
    CLOUD_SYNC_INTEGRATION,
    CLOUD_SYNC_ENABLED,
    OFFLINE_FIRST_MODE,
    DISPATCH_CLOUD_SYNC,
    ENCRYPTED_BACKUP_TITLE,
    ENCRYPTED_BACKUP_DESC,
    COMPLIANCE_REPORT_TITLE,
    COMPLIANCE_REPORT_DESC,
    DPDP_RETENTION_TITLE,
    DPDP_RETENTION_DESC,
    WIPE_LEDGER_TITLE,
    WIPE_LEDGER_DESC,
    SCAN_DUPLICATES,
    BACKUP_ACTION,
    EXPORT_ACTION,
    PURGE_ACTION,
    WIPE_ALL_ACTION,
    RETRY_ACTION,
    SAVE_CHANGES,
    DELETE_PERMANENTLY,
    CLOSE_ACTION,
    CONFIRM_ACTION,
    ADMIN_PIN,
    COMING_SOON,
}

/**
 * Singleton LocalizationManager providing reactive multi-lingual text in 10 Indian languages.
 */
object LocalizationManager {

    private const val PREFS_NAME = "omniface_i18n_prefs"
    private const val KEY_LANG = "selected_app_language"
    private const val APP_PREFS_NAME = "omniface_app_prefs"
    private const val KEY_ORG_TYPE = "org_type"

    private val _currentLanguage = MutableStateFlow(AppLanguage.ENGLISH)
    val currentLanguage: StateFlow<AppLanguage> = _currentLanguage.asStateFlow()

    private val _currentOrgType = MutableStateFlow("SCHOOL")
    val currentOrgType: StateFlow<String> = _currentOrgType.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedCode = prefs.getString(KEY_LANG, AppLanguage.ENGLISH.code)
        val lang = AppLanguage.entries.find { it.code == savedCode } ?: AppLanguage.ENGLISH
        _currentLanguage.value = lang

        initOrgType(context)
    }

    fun initOrgType(context: Context) {
        val prefs = context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_ORG_TYPE, "SCHOOL") ?: "SCHOOL"
        _currentOrgType.value = saved.uppercase()
    }

    fun setOrgType(orgType: String, context: Context? = null) {
        val normalized = orgType.uppercase()
        _currentOrgType.value = normalized
        context?.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_ORG_TYPE, normalized)
            ?.apply()
    }

    fun setLanguage(context: Context, language: AppLanguage) {
        _currentLanguage.value = language
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANG, language.code).apply()
    }

    fun getString(key: StringKey, lang: AppLanguage = _currentLanguage.value): String {
        return DICTIONARY[lang]?.get(key)
            ?: DICTIONARY[AppLanguage.ENGLISH]?.get(key)
            ?: key.name
    }

    @Composable
    fun get(key: StringKey): String {
        val lang by currentLanguage.collectAsState()
        return getString(key, lang)
    }

    fun getDirectoryTabTitle(orgType: String = _currentOrgType.value): String {
        return when (orgType.uppercase()) {
            "CORPORATE" -> "Employees"
            "GYM_EVENT" -> "Members"
            "COACHING" -> "Learners"
            else -> getString(StringKey.TAB_STUDENTS)
        }
    }

    fun getEntityPlural(orgType: String = _currentOrgType.value): String {
        return when (orgType.uppercase()) {
            "CORPORATE" -> "Employees"
            "GYM_EVENT" -> "Members"
            "COACHING" -> "Learners"
            else -> "Students"
        }
    }

    fun getEntitySingular(orgType: String = _currentOrgType.value): String {
        return when (orgType.uppercase()) {
            "CORPORATE" -> "Employee"
            "GYM_EVENT" -> "Member"
            "COACHING" -> "Learner"
            else -> "Student"
        }
    }

    fun getIdLabel(orgType: String = _currentOrgType.value): String {
        return when (orgType.uppercase()) {
            "CORPORATE" -> "Employee ID"
            "GYM_EVENT" -> "Member ID"
            "COACHING" -> "Student ID"
            else -> getString(StringKey.ROLL_NUMBER)
        }
    }

    fun getGroupLabel(orgType: String = _currentOrgType.value): String {
        return when (orgType.uppercase()) {
            "CORPORATE" -> "Shift / Designation"
            "GYM_EVENT" -> "Membership Plan"
            "COACHING" -> "Batch / Course"
            else -> getString(StringKey.SEMESTER)
        }
    }

    fun getAllowedRoles(orgType: String = _currentOrgType.value): List<String> {
        return when (orgType.uppercase()) {
            "CORPORATE" -> listOf("EMPLOYEE", "STAFF", "MANAGER", "CONTRACTOR", "VISITOR")
            "GYM_EVENT" -> listOf("MEMBER", "TRAINER", "STAFF", "VISITOR")
            "COACHING" -> listOf("STUDENT", "FACULTY", "STAFF", "VISITOR")
            else -> listOf("STUDENT", "FACULTY", "STAFF", "VISITOR")
        }
    }

    fun getRoleBadgeLabel(role: String): String {
        return when (role.uppercase()) {
            "FACULTY" -> "Faculty"
            "STAFF" -> "Staff"
            "MANAGER" -> "Manager"
            "EMPLOYEE" -> "Employee"
            "CONTRACTOR" -> "Contractor"
            "MEMBER" -> "Member"
            "TRAINER" -> "Trainer"
            "VISITOR" -> "Visitor"
            else -> "Student"
        }
    }

    // Comprehensive Dictionary across 10 Languages
    private val DICTIONARY: Map<AppLanguage, Map<StringKey, String>> = mapOf(
        AppLanguage.ENGLISH to EnglishStrings,
        AppLanguage.HINDI to HindiStrings,
        AppLanguage.KANNADA to KannadaStrings,
        AppLanguage.TAMIL to TamilStrings,
        AppLanguage.TELUGU to TeluguStrings,
        AppLanguage.MALAYALAM to MalayalamStrings,
        AppLanguage.BENGALI to BengaliStrings,
        AppLanguage.MARATHI to MarathiStrings,
        AppLanguage.GUJARATI to GujaratiStrings,
        AppLanguage.PUNJABI to PunjabiStrings
    )
}
