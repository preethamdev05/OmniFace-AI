package com.omniface.ai.ml.core

import java.io.Closeable

/**
 * Generic decoupled TFLite Model runner abstraction.
 *
 * Each neural subsystem (Detector, Landmarks, Attributes, AntiSpoof, Embedding)
 * implements this contract, isolating tensor dimensions, I/O memory allocation,
 * hardware delegate selection, and latency benchmarking away from UI viewports.
 */
interface TfliteModel<Input, Output> : Closeable {
    val modelId: String
    val displayName: String
    val version: String
    val activeBackend: InferenceBackend
    val isReady: Boolean

    /** Executes asynchronous inference on the provided input tensor data. */
    suspend fun run(input: Input): Output

    /** Measures warm execution latency in milliseconds. */
    fun benchmarkLatency(): Long
}
