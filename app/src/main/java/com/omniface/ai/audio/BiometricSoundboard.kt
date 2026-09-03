package com.omniface.ai.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.omniface.ai.i18n.AppLanguage
import java.util.Locale

enum class SoundEnvironmentMode(val displayName: String, val volumeLevel: Int) {
    NOISY_HALLWAY("Noisy Hallway (Max Volume)", 100),
    QUIET_CLASSROOM("Quiet Classroom (Discreet Chime)", 60),
    SILENT_VIBRATION("Silent (Haptic Only)", 0)
}

object BiometricSoundboard {

    private var toneGenerator: ToneGenerator? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ttsEngine: TextToSpeech? = null
    private var isTtsInitialized = false

    var currentLanguage: AppLanguage = AppLanguage.ENGLISH
    var currentSoundMode: SoundEnvironmentMode = SoundEnvironmentMode.NOISY_HALLWAY
    var isVoiceAnnounceEnabled: Boolean = true

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, currentSoundMode.volumeLevel)
        } catch (e: Exception) {
            toneGenerator = null
        }
    }

    private fun getLocaleForLanguage(lang: AppLanguage): Locale {
        return when (lang) {
            AppLanguage.ENGLISH -> Locale.US
            AppLanguage.HINDI -> Locale.forLanguageTag("hi-IN")
            AppLanguage.KANNADA -> Locale.forLanguageTag("kn-IN")
            AppLanguage.TAMIL -> Locale.forLanguageTag("ta-IN")
            AppLanguage.TELUGU -> Locale.forLanguageTag("te-IN")
            AppLanguage.MALAYALAM -> Locale.forLanguageTag("ml-IN")
            AppLanguage.BENGALI -> Locale.forLanguageTag("bn-IN")
            AppLanguage.MARATHI -> Locale.forLanguageTag("mr-IN")
            AppLanguage.GUJARATI -> Locale.forLanguageTag("gu-IN")
            AppLanguage.PUNJABI -> Locale.forLanguageTag("pa-IN")
        }
    }

    fun initTts(context: Context) {
        if (ttsEngine == null) {
            ttsEngine = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsEngine?.language = getLocaleForLanguage(currentLanguage)
                    isTtsInitialized = true
                }
            }
        }
    }

    fun setLanguage(lang: AppLanguage) {
        currentLanguage = lang
        ttsEngine?.language = getLocaleForLanguage(lang)
    }

    fun setSoundMode(mode: SoundEnvironmentMode) {
        currentSoundMode = mode
        try {
            toneGenerator?.release()
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, mode.volumeLevel)
        } catch (e: Exception) {
            // Audio setup fallback
        }
    }

    fun playMatchSuccess(studentName: String? = null) {
        if (currentSoundMode == SoundEnvironmentMode.SILENT_VIBRATION) return

        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
            mainHandler.postDelayed({
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
            }, 100)
        } catch (e: Exception) {
            // Tone fallback
        }

        if (isVoiceAnnounceEnabled && isTtsInitialized && !studentName.isNullOrBlank()) {
            val announcement = when (currentLanguage) {
                AppLanguage.ENGLISH -> "Welcome $studentName, attendance verified"
                AppLanguage.HINDI -> "स्वागत है $studentName, उपस्थिति सत्यापित हुई"
                AppLanguage.KANNADA -> "ಸ್ವಾಗತ $studentName, ಹಾಜರಾತಿ ದೃಢೀಕರಿಸಲಾಗಿದೆ"
                AppLanguage.TAMIL -> "வரவேற்கிறோம் $studentName, வருகை உறுதிப்படுத்தப்பட்டது"
                AppLanguage.TELUGU -> "స్వాగతం $studentName, హాజరు ధృవీకరించబడింది"
                AppLanguage.MALAYALAM -> "സ്വാഗതം $studentName, ഹാജർ രേഖപ്പെടുത്തി"
                AppLanguage.BENGALI -> "স্বাগতম $studentName, উপস্থিতি নিশ্চিত হয়েছে"
                AppLanguage.MARATHI -> "स्वागत आहे $studentName, उपस्थिती नोंदवली गेली"
                AppLanguage.GUJARATI -> "સ્વાગત છે $studentName, હાજરી ચકાસાયેલ છે"
                AppLanguage.PUNJABI -> "ਜੀ ਆਇਆਂ ਨੂੰ $studentName, ਹਾਜ਼ਰੀ ਦਰਜ ਕੀਤੀ ਗਈ"
            }
            ttsEngine?.speak(announcement, TextToSpeech.QUEUE_ADD, null, "MATCH_TTS")
        }
    }

    fun playDuplicatePunch() {
        if (currentSoundMode == SoundEnvironmentMode.SILENT_VIBRATION) return
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 140)
        } catch (e: Exception) {
            // Tone fallback
        }
    }

    fun playSpoofAlert() {
        if (currentSoundMode == SoundEnvironmentMode.SILENT_VIBRATION) return
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 250)
        } catch (e: Exception) {
            // Tone fallback
        }
    }

    fun playAngleCaptured() {
        if (currentSoundMode == SoundEnvironmentMode.SILENT_VIBRATION) return
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 70)
        } catch (e: Exception) {
            // Tone fallback
        }
    }

    fun release() {
        ttsEngine?.stop()
        ttsEngine?.shutdown()
        ttsEngine = null
        toneGenerator?.release()
        toneGenerator = null
    }
}
