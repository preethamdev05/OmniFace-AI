package com.omniface.ai.ml

import android.content.Context
import android.content.SharedPreferences
import com.omniface.ai.OmniFaceApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Model Configuration state holding user-controlled toggle switches for every single
 * auxiliary neural and algorithmic pipeline stage in OmniFace-AI.
 *
 * Baseline Core models (ML Kit Face Detector + ArcFace Recognition Engine) are always enabled
 * to ensure fundamental detection & recognition functionality.
 */
data class NeuralModelConfig(
    // 1. Anti-Spoofing & Liveness Models
    val isPassivePadEnabled: Boolean = true,          // MiniFASNetV2 Passive RGB PAD (Screen / Photo spoof rejection)
    val isMultiStageLivenessEnabled: Boolean = true,   // Specular glare, Moiré analysis & Texture entropy
    val isTemporalLivenessEnabled: Boolean = true,     // Multi-frame micro-motion & Blink continuity analysis
    
    // 2. Qualcomm Face Intelligence Suite (Auxiliary Neural Models)
    val isFaceMap3DMMEnabled: Boolean = true,          // FaceMap 3D Morphable Model (265-D depth topology)
    val isEyeGazeEnabled: Boolean = true,              // EyeGazeNet Pupil vector & 34-point iris tracking
    val isFaceAttribEnabled: Boolean = false,          // FaceAttribNet (Disabled by default for 0-latency attendance)
    val isMediaPipeMeshEnabled: Boolean = true,       // MediaPipe 468-point 3D dense facial mesh
    val isHrnetLandmarksEnabled: Boolean = false,      // HRNet 29-point (Disabled by default: superseded by MediaPipe 468 mesh)

    // 3. Biometric Matcher Subsystems
    val isDynamicCentroidAdaptationEnabled: Boolean = true, // Continuous EMA template adaptation
    val isFaissHnswIndexEnabled: Boolean = true,           // FAISS / HNSW sub-millisecond vector indexing

    // 4. Visual Diagnostics / Overlays
    val is3DMMOverlayEnabled: Boolean = false,
    val isMeshOverlayEnabled: Boolean = false,
    val isPoseAxesOverlayEnabled: Boolean = false,
    val isGazeRaysOverlayEnabled: Boolean = false,
    val isCyberneticHudOverlayEnabled: Boolean = false
)

/**
 * Singleton Neural Model Configuration Manager.
 * Persists granular model toggles in SharedPreferences and provides reactive StateFlow
 * for real-time pipeline adaptation without requiring app restart.
 */
object NeuralModelConfigManager {

    private const val PREFS_NAME = "omniface_model_config_prefs"

    private const val KEY_PASSIVE_PAD = "config_passive_pad"
    private const val KEY_MULTISTAGE_LIVENESS = "config_multistage_liveness"
    private const val KEY_TEMPORAL_LIVENESS = "config_temporal_liveness"
    private const val KEY_FACEMAP_3DMM = "config_facemap_3dmm"
    private const val KEY_EYE_GAZE = "config_eye_gaze"
    private const val KEY_FACE_ATTRIB = "config_face_attrib"
    private const val KEY_MEDIAPIPE_MESH = "config_mediapipe_mesh"
    private const val KEY_HRNET_LANDMARKS = "config_hrnet_landmarks"
    private const val KEY_DYNAMIC_CENTROID = "config_dynamic_centroid"
    private const val KEY_FAISS_HNSW = "config_faiss_hnsw"
    private const val KEY_OVERLAY_3DMM = "config_overlay_3dmm"
    private const val KEY_OVERLAY_MESH = "config_overlay_mesh"
    private const val KEY_OVERLAY_POSE = "config_overlay_pose"
    private const val KEY_OVERLAY_GAZE = "config_overlay_gaze"
    private const val KEY_OVERLAY_CYBERHUD = "config_overlay_cyberhud"

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
                isFaceMap3DMMEnabled = p.getBoolean(KEY_FACEMAP_3DMM, true),
                isEyeGazeEnabled = p.getBoolean(KEY_EYE_GAZE, true),
                isFaceAttribEnabled = p.getBoolean(KEY_FACE_ATTRIB, false),
                isMediaPipeMeshEnabled = p.getBoolean(KEY_MEDIAPIPE_MESH, true),
                isHrnetLandmarksEnabled = p.getBoolean(KEY_HRNET_LANDMARKS, false),
                isDynamicCentroidAdaptationEnabled = p.getBoolean(KEY_DYNAMIC_CENTROID, true),
                isFaissHnswIndexEnabled = p.getBoolean(KEY_FAISS_HNSW, true),
                is3DMMOverlayEnabled = p.getBoolean(KEY_OVERLAY_3DMM, false),
                isMeshOverlayEnabled = p.getBoolean(KEY_OVERLAY_MESH, false),
                isPoseAxesOverlayEnabled = p.getBoolean(KEY_OVERLAY_POSE, false),
                isGazeRaysOverlayEnabled = p.getBoolean(KEY_OVERLAY_GAZE, false),
                isCyberneticHudOverlayEnabled = p.getBoolean(KEY_OVERLAY_CYBERHUD, false)
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

    fun setFaceMap3DMMEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_FACEMAP_3DMM, enabled)?.apply()
        _configState.update { it.copy(isFaceMap3DMMEnabled = enabled) }
    }

    fun setEyeGazeEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_EYE_GAZE, enabled)?.apply()
        _configState.update { it.copy(isEyeGazeEnabled = enabled) }
    }

    fun setFaceAttribEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_FACE_ATTRIB, enabled)?.apply()
        _configState.update { it.copy(isFaceAttribEnabled = enabled) }
    }

    fun setMediaPipeMeshEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_MEDIAPIPE_MESH, enabled)?.apply()
        _configState.update { it.copy(isMediaPipeMeshEnabled = enabled) }
    }

    fun setHrnetLandmarksEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_HRNET_LANDMARKS, enabled)?.apply()
        _configState.update { it.copy(isHrnetLandmarksEnabled = enabled) }
    }

    fun setDynamicCentroidAdaptationEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_DYNAMIC_CENTROID, enabled)?.apply()
        _configState.update { it.copy(isDynamicCentroidAdaptationEnabled = enabled) }
    }

    fun setFaissHnswIndexEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_FAISS_HNSW, enabled)?.apply()
        _configState.update { it.copy(isFaissHnswIndexEnabled = enabled) }
    }

    fun set3DMMOverlayEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_OVERLAY_3DMM, enabled)?.apply()
        _configState.update { it.copy(is3DMMOverlayEnabled = enabled) }
    }

    fun setMeshOverlayEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_OVERLAY_MESH, enabled)?.apply()
        _configState.update { it.copy(isMeshOverlayEnabled = enabled) }
    }

    fun setPoseAxesOverlayEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_OVERLAY_POSE, enabled)?.apply()
        _configState.update { it.copy(isPoseAxesOverlayEnabled = enabled) }
    }

    fun setGazeRaysOverlayEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_OVERLAY_GAZE, enabled)?.apply()
        _configState.update { it.copy(isGazeRaysOverlayEnabled = enabled) }
    }

    fun setCyberneticHudOverlayEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_OVERLAY_CYBERHUD, enabled)?.apply()
        _configState.update { it.copy(isCyberneticHudOverlayEnabled = enabled) }
    }

    fun resetToDefaults() {
        prefs?.edit()?.clear()?.apply()
        _configState.value = loadConfig()
    }
}
