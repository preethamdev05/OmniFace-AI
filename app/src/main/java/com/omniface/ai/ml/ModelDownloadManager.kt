package com.omniface.ai.ml

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed class ModelDownloadState {
    data class Idle(
        val modelExistsLocally: Boolean,
        val activeModelName: String,
        val modelSizeBytes: Long = 0L
    ) : ModelDownloadState()

    data class Downloading(
        val progress: Float,
        val speedKbps: Long,
        val downloadedMb: Float,
        val totalMb: Float
    ) : ModelDownloadState()

    data class Verifying(val progress: Float) : ModelDownloadState()

    data class Ready(
        val activeModelName: String,
        val modelSizeBytes: Long
    ) : ModelDownloadState()

    data class Error(
        val message: String,
        val canRetry: Boolean = true
    ) : ModelDownloadState()
}

/**
 * 📦 ModelDownloadManager: Autonomous On-Demand Private Hugging Face Downloader & Integrity Gate.
 *
 * Responsibilities:
 * 1. Safely streams the sovereign OmniFace Neural Engine model package from private CDN.
 * 2. Provides continuous download telemetry (progress %, speed in KB/s, downloaded/total MB).
 * 3. Enforces TFL3 Magic Header validation & atomic file moves to prevent corrupted model states.
 * 4. Enables zero-downtime hot-swapping inside FaceRecognitionEngine.
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "OmniFaceModelDownloader"
        private const val MODELS_DIR = "models"
        const val TARGET_MODEL_FILENAME = "unified_omniface.tflite"
        private const val TMP_EXTENSION = ".download.tmp"
        private val TFLITE_IDENTIFIER = byteArrayOf('T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte())

        @Volatile
        private var INSTANCE: ModelDownloadManager? = null

        fun getInstance(context: Context): ModelDownloadManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ModelDownloadManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var downloadJob: Job? = null
    @Volatile private var activeCall: okhttp3.Call? = null

    private val _downloadState = MutableStateFlow<ModelDownloadState>(getInitialState())
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun getModelsDirectory(): File {
        val dir = File(context.filesDir, MODELS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getLocalModelFile(): File {
        return File(getModelsDirectory(), TARGET_MODEL_FILENAME)
    }

    fun isModelAvailable(): Boolean {
        val file = getLocalModelFile()
        return verifyModelIntegrity(file)
    }

    fun isNeuralModelInstalled(): Boolean {
        return isModelAvailable()
    }

    fun getActiveModelDisplayName(): String {
        val unified = UnifiedFaceIntelligenceEngine.getInstance(context)
        if (unified.isModelLoaded || isModelAvailable()) {
            return "OmniFace Deep AI Engine"
        }
        return "AI Recognition Pack (Not Installed)"
    }

    private fun getInitialState(): ModelDownloadState {
        val unified = UnifiedFaceIntelligenceEngine.getInstance(context)
        val file = getLocalModelFile()
        if (unified.isModelLoaded || verifyModelIntegrity(file)) {
            return ModelDownloadState.Ready(
                activeModelName = "OmniFace Deep AI Engine",
                modelSizeBytes = if (file.exists()) file.length() else 380182456L
            )
        }
        return ModelDownloadState.Idle(
            modelExistsLocally = false,
            activeModelName = "AI Recognition Pack (Not Installed)",
            modelSizeBytes = 0L
        )
    }

    /**
     * Initiates asynchronous model download with progress reporting.
     */
    fun startDownload(onCompleted: (() -> Unit)? = null) {
        if (downloadJob?.isActive == true) {
            Log.d(TAG, "Download already in progress.")
            return
        }

        val targetFile = getLocalModelFile()
        val tmpFile = File(getModelsDirectory(), "$TARGET_MODEL_FILENAME$TMP_EXTENSION")

        downloadJob = scope.launch {
            try {
                val token = HfSecureGateway.getAuthToken(context)
                val targetUrl = HfSecureGateway.buildResolveUrl(context, TARGET_MODEL_FILENAME)
                val repoId = HfSecureGateway.getRepoId(context)

                Log.i(TAG, "🚀 Initiating Unified Model download from CDN: (URL: $targetUrl)")

                val requestBuilder = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "OmniFace-AI-Android/1.0")
                    .header("X-OmniFace-Secret", "omniface-secure-2025")
                    .header("X-App-Version", "1")

                if (!token.isNullOrBlank()) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }

                val request = requestBuilder.build()

                _downloadState.value = ModelDownloadState.Downloading(
                    progress = 0.0f,
                    speedKbps = 0L,
                    downloadedMb = 0.0f,
                    totalMb = 362.6f // 380MB unified model
                )

                val call = okHttpClient.newCall(request)
                activeCall = call
                val response = call.execute()
                if (!response.isSuccessful) {
                    val code = response.code
                    val errorMsg = when (code) {
                        401, 403 -> "🔒 CDN Access Denied ($code). Verify authorization secret."
                        404 -> "❌ Unified model not found ($code) on CDN. Please upload model to R2."
                        else -> "⚠️ Download failed with HTTP status code $code: ${response.message}"
                    }
                    _downloadState.value = ModelDownloadState.Error(errorMsg, canRetry = true)
                    response.close()
                    return@launch
                }

                val body = response.body
                if (body == null) {
                    _downloadState.value = ModelDownloadState.Error("Empty response body from Model CDN.", canRetry = true)
                    return@launch
                }

                val contentLength = body.contentLength()
                val totalMb = if (contentLength > 0) contentLength / (1024f * 1024f) else 362.6f

                if (tmpFile.exists()) {
                    tmpFile.delete()
                }

                val buffer = ByteArray(64 * 1024)
                var bytesDownloaded = 0L
                var lastTime = System.currentTimeMillis()
                var lastBytes = 0L
                @Suppress("VARIABLE_WITH_REDUNDANT_INITIALIZER")
                var currentSpeedKbps = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            if (!kotlin.coroutines.coroutineContext.isActive) {
                                break
                            }
                            output.write(buffer, 0, read)
                            bytesDownloaded += read

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastTime
                            if (elapsed >= 400) {
                                val bytesInInterval = bytesDownloaded - lastBytes
                                currentSpeedKbps = if (elapsed > 0) (bytesInInterval * 1000L / elapsed) / 1024L else 0L
                                lastBytes = bytesDownloaded
                                lastTime = now

                                val downloadedMb = bytesDownloaded / (1024f * 1024f)
                                val progress = if (contentLength > 0) (bytesDownloaded.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f) else 0.5f

                                _downloadState.value = ModelDownloadState.Downloading(
                                    progress = progress,
                                    speedKbps = currentSpeedKbps,
                                    downloadedMb = downloadedMb,
                                    totalMb = totalMb
                                )
                            }
                        }
                        output.flush()
                    }
                }

                if (!kotlin.coroutines.coroutineContext.isActive) {
                    tmpFile.delete()
                    return@launch
                }

                // Stage 2: Integrity & Magic Header Verification
                _downloadState.value = ModelDownloadState.Verifying(progress = 0.95f)

                if (!verifyModelIntegrity(tmpFile)) {
                    tmpFile.delete()
                    _downloadState.value = ModelDownloadState.Error(
                        "Downloaded model failed TFLite flatbuffer integrity check. The file may be corrupt or gated.",
                        canRetry = true
                    )
                    return@launch
                }

                // Stage 3: Atomic Promotion to Live Model File
                val installedModelFile = getLocalModelFile()
                if (installedModelFile.exists()) {
                    installedModelFile.delete()
                }

                val renamed = tmpFile.renameTo(installedModelFile)
                if (renamed) {
                    Log.i(TAG, "✅ Model successfully installed: ${installedModelFile.absolutePath} (${installedModelFile.length()} bytes)")
                    UnifiedFaceIntelligenceEngine.getInstance(context).reloadModel()
                    _downloadState.value = ModelDownloadState.Ready(
                        activeModelName = "Unified OmniFace AI (Qualcomm CavaFace + 6 Auxiliary Heads)",
                        modelSizeBytes = installedModelFile.length()
                    )
                    withContext(Dispatchers.Main) {
                        onCompleted?.invoke()
                    }
                } else {
                    _downloadState.value = ModelDownloadState.Error("Failed to rename temporary model file.", canRetry = true)
                }

            } catch (e: Exception) {
                tmpFile.delete()
                if (downloadJob?.isCancelled == true) {
                    _downloadState.value = getInitialState()
                } else {
                    Log.e(TAG, "Exception during model download", e)
                    _downloadState.value = ModelDownloadState.Error(
                        message = "Network error: ${e.localizedMessage ?: e.message ?: "Unknown error"}",
                        canRetry = true
                    )
                }
            } finally {
                activeCall = null
            }
        }
    }

    /**
     * Cancels active download task.
     */
    fun cancelDownload() {
        try {
            activeCall?.cancel()
            activeCall = null
        } catch (_: Throwable) {}
        downloadJob?.cancel()
        downloadJob = null
        val tmpFile = File(getModelsDirectory(), "$TARGET_MODEL_FILENAME$TMP_EXTENSION")
        if (tmpFile.exists()) {
            tmpFile.delete()
        }
        _downloadState.value = getInitialState()
    }

    /**
     * Deletes the downloaded model and resets to bundled fallback.
     */
    fun deleteDownloadedModel(): Boolean {
        cancelDownload()
        val file = getLocalModelFile()
        val deleted = if (file.exists()) file.delete() else true
        val alt = File("/storage/emulated/0/AI-HUB/FR/models/$TARGET_MODEL_FILENAME")
        if (alt.exists()) {
            runCatching { alt.delete() }
        }
        val unified = UnifiedFaceIntelligenceEngine.getInstance(context)
        if (unified.isModelLoaded) {
            unified.unloadUnifiedModel()
        }
        _downloadState.value = ModelDownloadState.Idle(
            modelExistsLocally = false,
            activeModelName = "AI Recognition Pack (Not Installed)",
            modelSizeBytes = 0L
        )
        return deleted
    }

    /**
     * Verifies whether the given file is a valid non-empty TFLite model.
     */
    fun verifyModelIntegrity(file: File): Boolean {
        if (!file.exists() || file.length() < 1024 * 1024) { // Minimum 1MB for valid flatbuffer
            return false
        }

        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(8)
                val read = input.read(header)
                if (read < 8) return false
                // In FlatBuffers, offset 0..3 is root table offset, and offset 4..7 is the 4-char file identifier
                header[4] == TFLITE_IDENTIFIER[0] &&
                header[5] == TFLITE_IDENTIFIER[1] &&
                header[6] == TFLITE_IDENTIFIER[2] &&
                (header[7] == TFLITE_IDENTIFIER[3] || header[7] == '2'.code.toByte() || header[7] == '1'.code.toByte())
            }
        } catch (e: Exception) {
            false
        }
    }
}
