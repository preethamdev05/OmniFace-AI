package com.omniface.ai.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey

enum class SettingsCategory(
    val id: String,
    val titleKey: StringKey,
    val subtitleKey: StringKey,
    val icon: ImageVector,
    val accentColor: Color,
    val badge: String? = null
) {
    APPEARANCE(
        id = "appearance",
        titleKey = StringKey.CAT_APPEARANCE,
        subtitleKey = StringKey.CAT_APPEARANCE_DESC,
        icon = Icons.Default.Palette,
        accentColor = Color(0xFF38BDF8)
    ),
    BIOMETRIC_SECURITY(
        id = "biometrics",
        titleKey = StringKey.CAT_BIOMETRICS,
        subtitleKey = StringKey.CAT_BIOMETRICS_DESC,
        icon = Icons.Default.Security,
        accentColor = Color(0xFF34C759)
    ),
    NEURAL_ENGINE(
        id = "neural_engine",
        titleKey = StringKey.CAT_NEURAL,
        subtitleKey = StringKey.CAT_NEURAL_DESC,
        icon = Icons.Default.Memory,
        accentColor = Color(0xFFA855F7),
        badge = "INT8"
    ),
    KIOSK_ACCESS(
        id = "kiosk_access",
        titleKey = StringKey.CAT_KIOSK,
        subtitleKey = StringKey.CAT_KIOSK_DESC,
        icon = Icons.Default.MeetingRoom,
        accentColor = Color(0xFF007AFF)
    ),
    DATA_GOVERNANCE(
        id = "data_governance",
        titleKey = StringKey.CAT_DATA,
        subtitleKey = StringKey.CAT_DATA_DESC,
        icon = Icons.Default.Storage,
        accentColor = Color(0xFFEC4899)
    );

    val title: String
        get() = LocalizationManager.getString(titleKey)

    val subtitle: String
        get() = LocalizationManager.getString(subtitleKey)
}

