package com.omniface.ai.ml

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * 1. Safely streams the 124MB AntelopeV2 FP16 ResNet100 model from private Hugging Face CDN.
 * 2. Provides continuous download telemetry (progress %, speed in KB/s, downloaded/total MB).
 * 3. Enforces TFL3 Magic Header validation & atomic file moves to prevent corrupted model states.
 * 4. Enables zero-downtime hot-swapping inside FaceRecognitionEngine.
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "OmniFaceModelDownloader"
        private const val MODELS_DIR = "models"
        const val TARGET_MODEL_FILENAME = "mobilefacenet_512d_fp16.tflite"
        private const val TMP_EXTENSION = ".download.tmp"
        private val TFLITE_MAGIC = byteArrayOf(0x1c.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 'T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte())

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

    private val _downloadState = MutableStateFlow<ModelDownloadState>(getInitialState())
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
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

    fun isAntelopeV2Installed(): Boolean {
        val file = getLocalModelFile()
        return verifyModelIntegrity(file)
    }

    fun getActiveModelDisplayName(): String {
        return if (isAntelopeV2Installed()) {
            "AntelopeV2 Glint360K (512-D Ultra HD)"
        } else {
            "MobileFaceNet NPU (Bundled Fallback)"
        }
    }

    private fun getInitialState(): ModelDownloadState {
        val file = getLocalModelFile()
        val exists = verifyModelIntegrity(file)
        return if (exists) {
            ModelDownloadState.Ready(
                activeModelName = "AntelopeV2 Glint360K (512-D Ultra HD)",
                modelSizeBytes = file.length()
            )
        } else {
            ModelDownloadState.Idle(
                modelExistsLocally = false,
                activeModelName = "MobileFaceNet NPU (Bundled Fallback)",
                modelSizeBytes = 0L
            )
        }
    }

    /**
     * Initiates asynchronous model download with progress reporting.
     */
    fun startDownload(onCompleted: (() -> Unit)? = null) {
        if (downloadJob?.isActive == true) {
            Log.d(TAG, "Download already in progress.")
            return
        }

        downloadJob = scope.launch {
            try {
                val token = HfSecureGateway.getAuthToken(context)
                val targetUrl = HfSecureGateway.buildResolveUrl(context, TARGET_MODEL_FILENAME)
                val repoId = HfSecureGateway.getRepoId(context)

                Log.i(TAG, "🚀 Initiating Hugging Face download from repo: $repoId (URL: $targetUrl)")

                val requestBuilder = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "OmniFace-AI-Android/1.0")

                if (!token.isNullOrBlank()) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }

                val request = requestBuilder.build()

                _downloadState.value = ModelDownloadState.Downloading(
                    progress = 0.0f,
                    speedKbps = 0L,
                    downloadedMb = 0.0f,
                    totalMb = 124.3f // Approximate expected size
                )

                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    val code = response.code
                    val errorMsg = when (code) {
                        401, 403 -> "🔒 Hugging Face Access Denied ($code). Please configure your private token in Settings."
                        404 -> "❌ Model not found ($code) in repository '$repoId'. Check repository ID in Settings."
                        else -> "⚠️ Download failed with HTTP status code $code: ${response.message}"
                    }
                    _downloadState.value = ModelDownloadState.Error(errorMsg, canRetry = true)
                    response.close()
                    return@launch
                }

                val body = response.body
                if (body == null) {
                    _downloadState.value = ModelDownloadState.Error("Empty response body from Hugging Face.", canRetry = true)
                    return@launch
                }

                val contentLength = body.contentLength()
                val totalMb = if (contentLength > 0) contentLength / (1024f * 1024f) else 124.3f

                val tmpFile = File(getModelsDirectory(), "$TARGET_MODEL_FILENAME$TMP_EXTENSION")
                if (tmpFile.exists()) {
                    tmpFile.delete()
                }

                val buffer = ByteArray(64 * 1024)
                var bytesDownloaded = 0L
                var lastTime = System.currentTimeMillis()
                var lastBytes = 0L
                var currentSpeedKbps = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
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
                val targetFile = getLocalModelFile()
                if (targetFile.exists()) {
                    targetFile.delete()
                }

                val renamed = tmpFile.renameTo(targetFile)
                if (renamed) {
                    Log.i(TAG, "✅ Model successfully installed: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                    _downloadState.value = ModelDownloadState.Ready(
                        activeModelName = "AntelopeV2 Glint360K (512-D Ultra HD)",
                        modelSizeBytes = targetFile.length()
                    )
                    withContext(Dispatchers.Main) {
                        onCompleted?.invoke()
                    }
                } else {
                    _downloadState.value = ModelDownloadState.Error("Failed to rename temporary model file.", canRetry = true)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Exception during model download", e)
                _downloadState.value = ModelDownloadState.Error(
                    message = "Network error: ${e.localizedMessage ?: e.message ?: "Unknown error"}",
                    canRetry = true
                )
            }
        }
    }

    /**
     * Cancels active download task.
     */
    fun cancelDownload() {
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
        _downloadState.value = ModelDownloadState.Idle(
            modelExistsLocally = false,
            activeModelName = "MobileFaceNet NPU (Bundled Fallback)",
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
                header.contentEquals(TFLITE_MAGIC)
            }
        } catch (e: Exception) {
            false
        }
    }
}
