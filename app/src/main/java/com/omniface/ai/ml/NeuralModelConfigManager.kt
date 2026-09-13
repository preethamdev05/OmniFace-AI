package com.omniface.ai.ml

import android.content.Context
import android.content.SharedPreferences
import com.omniface.ai.OmniFaceApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Model Configuration state holding user-controlled toggle switches for the
 * verified production neural and algorithmic biometric subsystems in OmniFace-AI.
 *
 * Core models (ML Kit Face Detector + ArcFace 512-D MobileFaceNet) remain permanently active
 * as the foundational recognition substrate.
 */
data class NeuralModelConfig(
    // 1. Anti-Spoofing & Liveness Subsystems
    val isPassivePadEnabled: Boolean = true,                 // MiniFASNetV2 Passive RGB PAD (Screen / Photo spoof rejection)
    val isMultiStageLivenessEnabled: Boolean = true,         // Specular glare, Moiré analysis & Texture entropy
    val isTemporalLivenessEnabled: Boolean = true,           // Multi-frame micro-motion & Blink continuity analysis

    // 2. Biometric Matcher Subsystems
    val isDynamicCentroidAdaptationEnabled: Boolean = true,  // Continuous EMA template adaptation
    val isFaissHnswIndexEnabled: Boolean = true              // FAISS / HNSW sub-millisecond vector indexing
)

/**
 * Singleton Neural Model Configuration Manager.
 * Persists operational subsystem toggles in SharedPreferences and provides reactive StateFlow
 * for real-time pipeline adaptation without requiring app restart.
 */
object NeuralModelConfigManager {

    private const val PREFS_NAME = "omniface_model_config_prefs"

    private const val KEY_PASSIVE_PAD = "config_passive_pad"
    private const val KEY_MULTISTAGE_LIVENESS = "config_multistage_liveness"
    private const val KEY_TEMPORAL_LIVENESS = "config_temporal_liveness"
    private const val KEY_DYNAMIC_CENTROID = "config_dynamic_centroid"
    private const val KEY_FAISS_HNSW = "config_faiss_hnsw"

    private val prefs: SharedPreferences? by lazy {
        try { OmniFaceApplication.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) } catch (_: Throwable) { null }
    }

    private val _configState = MutableStateFlow(try { loadConfig() } catch (_: Throwable) { NeuralModelConfig() })
    val configState: StateFlow<NeuralModelConfig> = _configState.asStateFlow()

    private fun loadConfig(): NeuralModelConfig {
        val p = prefs ?: return NeuralModelConfig()
        return try {
            NeuralModelConfig(
                isPassivePadEnabled = p.getBoolean(KEY_PASSIVE_PAD, true),
                isMultiStageLivenessEnabled = p.getBoolean(KEY_MULTISTAGE_LIVENESS, true),
                isTemporalLivenessEnabled = p.getBoolean(KEY_TEMPORAL_LIVENESS, true),
                isDynamicCentroidAdaptationEnabled = p.getBoolean(KEY_DYNAMIC_CENTROID, true),
                isFaissHnswIndexEnabled = p.getBoolean(KEY_FAISS_HNSW, true)
            )
        } catch (_: Throwable) { NeuralModelConfig() }
    }

    fun setPassivePadEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_PASSIVE_PAD, enabled)?.apply()
        _configState.update { it.copy(isPassivePadEnabled = enabled) }
    }

    fun setMultiStageLivenessEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_MULTISTAGE_LIVENESS, enabled)?.apply()
        _configState.update { it.copy(isMultiStageLivenessEnabled = enabled) }
    }

    fun setTemporalLivenessEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_TEMPORAL_LIVENESS, enabled)?.apply()
        _configState.update { it.copy(isTemporalLivenessEnabled = enabled) }
    }

    fun setDynamicCentroidAdaptationEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_DYNAMIC_CENTROID, enabled)?.apply()
        _configState.update { it.copy(isDynamicCentroidAdaptationEnabled = enabled) }
    }

    fun setFaissHnswIndexEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_FAISS_HNSW, enabled)?.apply()
        _configState.update { it.copy(isFaissHnswIndexEnabled = enabled) }
    }

    fun resetToDefaults() {
        prefs?.edit()?.clear()?.apply()
        _configState.value = loadConfig()
    }
}
