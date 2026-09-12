package com.omniface.ai.ml.verification.domain

import com.omniface.ai.hardware.ThermalState

/**
 * Silicon Neural Hardware Backend Acceleration Tier.
 */
enum class HardwareBackend(val label: String) {
    NPU_NNAPI("NPU (Neural Processing Unit INT8)"),
    GPU_DELEGATE("Mobile GPU Delegate (FP16 High Precision)"),
    CPU_XNNPACK("Multi-Core CPU (XNNPACK FP32 Reference)")
}

/**
 * Strongly Typed Silicon Telemetry for OmniFace Edge Terminals.
 */
data class HardwareTelemetry(
    val backend: HardwareBackend = HardwareBackend.NPU_NNAPI,
    val resolvedBackendLabel: String = "Qualcomm Hexagon NPU (INT8)",
    val thermalState: ThermalState = ThermalState.NOMINAL,
    val deviceTemperature: Float = 32.0f,
    val latencyMs: Long = 4L,
    val activeModelName: String = "MobileFaceNet ArcFace 512-D",
    val isReady: Boolean = true
)
