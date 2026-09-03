package com.omniface.ai.i18n

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit Test Suite for Global 10-Language Localization Engine.
 */
class LocalizationManagerTest {

    @Test
    fun testAll10LanguagesDefined() {
        val languages = AppLanguage.entries
        assertEquals("Must support exactly 10 Indian and global languages", 10, languages.size)

        val codes = languages.map { it.code }.toSet()
        assertEquals(10, codes.size)

        val expectedCodes = listOf("en", "hi", "kn", "ta", "te", "ml", "bn", "mr", "gu", "pa")
        assertTrue(codes.containsAll(expectedCodes))
    }

    @Test
    fun testEveryStringKeyHasTranslationForAllLanguages() {
        for (lang in AppLanguage.entries) {
            for (key in StringKey.entries) {
                val value = LocalizationManager.getString(key, lang)
                assertNotNull("Value for $key in ${lang.displayName} must not be null", value)
                assertTrue("Value for $key in ${lang.displayName} must not be blank", value.isNotBlank())
                // Ensure it does not fallback to the raw enum name
                assertNotEquals("Value for $key in ${lang.displayName} should not be the raw key name", key.name, value)
            }
        }
    }

    @Test
    fun testNavigationTabsTranslation() {
        val overviewEn = LocalizationManager.getString(StringKey.TAB_OVERVIEW, AppLanguage.ENGLISH)
        val overviewHi = LocalizationManager.getString(StringKey.TAB_OVERVIEW, AppLanguage.HINDI)
        val overviewKn = LocalizationManager.getString(StringKey.TAB_OVERVIEW, AppLanguage.KANNADA)
        val overviewTa = LocalizationManager.getString(StringKey.TAB_OVERVIEW, AppLanguage.TAMIL)
        val overviewTe = LocalizationManager.getString(StringKey.TAB_OVERVIEW, AppLanguage.TELUGU)
        val overviewMl = LocalizationManager.getString(StringKey.TAB_OVERVIEW, AppLanguage.MALAYALAM)
        val overviewBn = LocalizationManager.getString(StringKey.TAB_OVERVIEW, AppLanguage.BENGALI)
        val overviewMr = LocalizationManager.getString(StringKey.TAB_OVERVIEW, AppLanguage.MARATHI)
        val overviewGu = LocalizationManager.getString(StringKey.TAB_OVERVIEW, AppLanguage.GUJARATI)
        val overviewPa = LocalizationManager.getString(StringKey.TAB_OVERVIEW, AppLanguage.PUNJABI)

        assertEquals("Overview", overviewEn)
        assertEquals("अवलोकन", overviewHi)
        assertEquals("ಅವಲೋಕನ", overviewKn)
        assertEquals("கண்ணோட்டம்", overviewTa)
        assertEquals("అవలోకనం", overviewTe)
        assertEquals("അവലോകനം", overviewMl)
        assertEquals("ওভারভিউ", overviewBn)
        assertEquals("आढावा", overviewMr)
        assertEquals("વિહંગાવલોકન", overviewGu)
        assertEquals("ਸੰਖੇਪ", overviewPa)
    }

    @Test
    fun testCalibratedDecisionTiersTranslation() {
        for (lang in AppLanguage.entries) {
            val std = LocalizationManager.getString(StringKey.TIER_STANDARD, lang)
            val high = LocalizationManager.getString(StringKey.TIER_HIGH, lang)
            val strict = LocalizationManager.getString(StringKey.TIER_STRICT, lang)

            assertTrue(std.isNotBlank())
            assertTrue(high.isNotBlank())
            assertTrue(strict.isNotBlank())
        }
    }

    @Test
    fun testDirectDictionaryParityAcrossAllLanguages() {
        // Use reflection to inspect the private DICTIONARY map in LocalizationManager
        val dictField = LocalizationManager::class.java.getDeclaredField("DICTIONARY")
        dictField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val dict = dictField.get(LocalizationManager) as Map<AppLanguage, Map<StringKey, String>>

        assertEquals("Dictionary must contain entries for all 10 languages", 10, dict.size)
        val allKeys = StringKey.entries

        for (lang in AppLanguage.entries) {
            val langMap = dict[lang]
            assertNotNull("Dictionary for ${lang.displayName} must not be null", langMap)
            assertEquals(
                "Language ${lang.displayName} must have exact key parity with StringKey enum (${allKeys.size} keys)",
                allKeys.size,
                langMap?.size
            )
            for (key in allKeys) {
                assertTrue(
                    "Language ${lang.displayName} must explicitly contain key $key in its dictionary map without fallback",
                    langMap?.containsKey(key) == true
                )
                val str = langMap?.get(key)
                assertNotNull("String for $key in ${lang.displayName} must not be null", str)
                assertTrue("String for $key in ${lang.displayName} must not be blank", !str.isNullOrBlank())
            }
        }
    }

    @Test
    fun testDedicatedSettingsAndDedupKeysTranslations() {
        val testKeys = listOf(
            StringKey.DEDUP_STUDIO_SUBTITLE,
            StringKey.DEDUP_CLEAN_DESC,
            StringKey.SETTINGS_APPEARANCE_TITLE,
            StringKey.SETTINGS_APPEARANCE_SUBTITLE,
            StringKey.SETTINGS_THEME_TITLE,
            StringKey.SETTINGS_THEME_SUBTITLE,
            StringKey.SETTINGS_LANGUAGE_TITLE,
            StringKey.SETTINGS_LANGUAGE_SUBTITLE,
            StringKey.SETTINGS_SOUND_TITLE,
            StringKey.SETTINGS_SOUND_SUBTITLE,
            StringKey.SETTINGS_DATA_GOVERNANCE_TITLE,
            StringKey.SETTINGS_DATA_GOVERNANCE_SUBTITLE,
            StringKey.WIPE_LEDGER
        )

        for (lang in AppLanguage.entries) {
            for (key in testKeys) {
                val value = LocalizationManager.getString(key, lang)
                assertNotNull(value)
                assertTrue("Value for $key in ${lang.displayName} should not be blank", value.isNotBlank())
                assertNotEquals("Value for $key in ${lang.displayName} should not equal raw key name", key.name, value)
            }
        }
    }

    @Test
    fun testBiometricSoundboardLanguageSynchronization() {
        for (lang in AppLanguage.entries) {
            com.omniface.ai.audio.BiometricSoundboard.setLanguage(lang)
            assertEquals("BiometricSoundboard language must match set language", lang, com.omniface.ai.audio.BiometricSoundboard.currentLanguage)
        }
        // Reset to English
        com.omniface.ai.audio.BiometricSoundboard.setLanguage(AppLanguage.ENGLISH)
    }

    @Test
    fun testNativeNamesAreAccurate() {
        assertEquals("English", AppLanguage.ENGLISH.nativeName)
        assertEquals("हिन्दी", AppLanguage.HINDI.nativeName)
        assertEquals("ಕನ್ನಡ", AppLanguage.KANNADA.nativeName)
        assertEquals("தமிழ்", AppLanguage.TAMIL.nativeName)
        assertEquals("తెలుగు", AppLanguage.TELUGU.nativeName)
        assertEquals("മലയാളം", AppLanguage.MALAYALAM.nativeName)
        assertEquals("বাংলা", AppLanguage.BENGALI.nativeName)
        assertEquals("मराठी", AppLanguage.MARATHI.nativeName)
        assertEquals("ગુજરાતી", AppLanguage.GUJARATI.nativeName)
        assertEquals("ਪੰਜਾਬੀ", AppLanguage.PUNJABI.nativeName)
    }
}
