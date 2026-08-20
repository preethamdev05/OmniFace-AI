package com.omniface.ai.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

enum class AppLanguage(val code: String, val displayName: String, val locale: Locale) {
    ENGLISH("en", "English", Locale.US),
    KANNADA("kn", "ಕನ್ನಡ (Kannada)", Locale("kn", "IN")),
    HINDI("hi", "हिंदी (Hindi)", Locale("hi", "IN"))
}

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

    fun initTts(context: Context) {
        if (ttsEngine == null) {
            ttsEngine = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsEngine?.language = currentLanguage.locale
                    isTtsInitialized = true
                }
            }
        }
    }

    fun setLanguage(lang: AppLanguage) {
        currentLanguage = lang
        ttsEngine?.language = lang.locale
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
                AppLanguage.KANNADA -> "ಸ್ವಾಗತ $studentName, ಹಾಜರಾತಿ ದೃಢೀಕರಿಸಲಾಗಿದೆ"
                AppLanguage.HINDI -> "स्वागत है $studentName, उपस्थिति सत्यापित हुई"
                AppLanguage.ENGLISH -> "Welcome $studentName, verified"
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
