package com.omniface.ai.ml

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------
// Model manifest entry
// ---------------------------------------------------------------------------

data class QualcommModelEntry(
    val id: String,
    val displayName: String,
    val fileSizeMb: Float,
    /** Relative sub-path inside the qualcomm_suite dir, e.g. "eyegaze/eyegaze-tflite-float" */
    val relativeDir: String,
    val filename: String,
)

sealed class QualcommModelState {
    object Unavailable : QualcommModelState()
    object Installed   : QualcommModelState()
    object Idle        : QualcommModelState()
    data class Downloading(
        val progress: Float,
        val downloadedMb: Float,
        val totalMb: Float,
        val speedKbps: Long
    ) : QualcommModelState()
    data class Error(val message: String) : QualcommModelState()
}

// ---------------------------------------------------------------------------
// Manager (context-aware — writes to app-owned external files dir)
// ---------------------------------------------------------------------------

class QualcommSuiteDownloadManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "QualcommSuiteDL"

        /**
         * Cloudflare R2 Worker endpoint. Populated by HfSecureGateway or hardcoded here.
         * Override in Settings → Zero-Trust Gateway config.
         */
        const val DEFAULT_CDN_URL = "https://omniface-model-cdn.preetham-dev.workers.dev"

        /**
         * Shared secret header value. Set as a wrangler secret on the Worker.
         * The app reads this from HfSecureGateway's stored token slot.
         */
        const val DEFAULT_APP_SECRET = ""

        /** Hardcoded public fallback: Qualcomm AI Hub S3 (no auth required) */
        private const val S3_BASE =
            "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models"
        private const val S3_RELEASE = "v0.60.0"

        val SUITE_MODELS: List<QualcommModelEntry> = listOf(
            QualcommModelEntry(
                id = "facemap_3dmm",
                displayName = "FaceMap 3DMM (3D Anti-Spoof)",
                fileSizeMb = 21f,
                relativeDir = "facemap_3dmm/facemap_3dmm-tflite-float",
                filename = "facemap_3dmm.tflite"
            ),
            QualcommModelEntry(
                id = "face_attrib_net",
                displayName = "FaceAttribNet (Expression)",
                fileSizeMb = 42f,
                relativeDir = "face_attrib_net/face_attrib_net-tflite-float",
                filename = "face_attrib_net.tflite"
            ),
            QualcommModelEntry(
                id = "eyegaze",
                displayName = "EyeGaze (Pupil Attention)",
                fileSizeMb = 9.7f,
                relativeDir = "eyegaze/eyegaze-tflite-float",
                filename = "eyegaze.tflite"
            ),
            QualcommModelEntry(
                id = "hrnet_face",
                displayName = "HRNetFace (HR Landmarks)",
                fileSizeMb = 37f,
                relativeDir = "hrnet_face/hrnet_face-tflite-float",
                filename = "hrnet_face.tflite"
            ),
            QualcommModelEntry(
                id = "mediapipe_face",
                displayName = "MediaPipe Face Mesh (468 pts)",
                fileSizeMb = 2.9f,
                relativeDir = "mediapipe_face/mediapipe_face-tflite-float",
                filename = "face_landmark_detector.tflite"
            ),
            QualcommModelEntry(
                id = "cavaface",
                displayName = "CavaFace (512-D ArcFace HD)",
                fileSizeMb = 250f,
                relativeDir = "cavaface/cavaface-tflite-float",
                filename = "cavaface.tflite"
            )
        )

        @Volatile private var INSTANCE: QualcommSuiteDownloadManager? = null

        fun getInstance(context: Context): QualcommSuiteDownloadManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: QualcommSuiteDownloadManager(context.applicationContext).also { INSTANCE = it }
            }

        /**
         * App-writable root for Qualcomm suite models.
         * Uses getExternalFilesDir to avoid EPERM on /storage/emulated/0.
         */
        fun suiteRoot(context: Context): File =
            File(context.getExternalFilesDir(null), "models/qualcomm_suite").also { it.mkdirs() }

        /**
         * Resolves the .tflite file for a given model entry, checking:
         * 1. App-owned external files dir (downloaded via this manager)
         * 2. Canonical /storage/emulated/0/AI-HUB/FR/models paths
         * 3. S3 unpacked nested dirs
         */
        fun resolveModelFile(context: Context, entry: QualcommModelEntry): File? {
            val candidates = listOf(
                File(suiteRoot(context), "${entry.relativeDir}/${entry.filename}"),
                File(suiteRoot(context), "${entry.id}/${entry.id}-tflite-float/${entry.filename}"),
                File(suiteRoot(context), "${entry.id}/${entry.filename}"),
                File(suiteRoot(context), entry.filename),
                File("/storage/emulated/0/AI-HUB/FR/models/qualcomm_suite/${entry.relativeDir}/${entry.filename}"),
                File("/storage/emulated/0/AI-HUB/FR/models/qualcomm_suite/${entry.id}/${entry.id}-tflite-float/${entry.filename}"),
                File("/storage/emulated/0/AI-HUB/FR/models/qualcomm_suite/${entry.id}/${entry.filename}"),
                File("/storage/emulated/0/AI-HUB/FR/models/qualcomm_suite/${entry.filename}"),
                File("/storage/emulated/0/AI-HUB/FR/models/qualcomm_${entry.id}/${entry.id}-tflite-float/${entry.filename}"),
                File("/storage/emulated/0/AI-HUB/FR/models/qualcomm_${entry.id}/${entry.filename}")
            )

            for (candidate in candidates) {
                if (candidate.exists() && candidate.canRead() && candidate.length() > 1024L) {
                    return candidate
                }
            }

            return null
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val activeCalls = java.util.concurrent.ConcurrentHashMap<String, okhttp3.Call>()

    private val _states = MutableStateFlow<Map<String, QualcommModelState>>(
        SUITE_MODELS.associate { it.id to getInitialState(it) }
    )
    val states: StateFlow<Map<String, QualcommModelState>> = _states.asStateFlow()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun isInstalled(modelId: String): Boolean {
        val entry = SUITE_MODELS.find { it.id == modelId } ?: return false
        return resolveModelFile(context, entry) != null
    }

    /**
     * Downloads a model, trying the CF R2 Worker first, then falling back
     * to the public Qualcomm AI Hub S3 bucket.
     */
    fun downloadModel(modelId: String, cdnUrl: String = DEFAULT_CDN_URL, secret: String = DEFAULT_APP_SECRET) {
        val entry = SUITE_MODELS.find { it.id == modelId } ?: return
        if (jobs[modelId]?.isActive == true) return

        jobs[modelId] = scope.launch {
            val destDir = File(suiteRoot(context), entry.relativeDir).also { it.mkdirs() }
            val destFile = File(destDir, entry.filename)
            val tmpFile  = File(destDir, "${entry.filename}.tmp")

            try {
                emit(modelId, QualcommModelState.Downloading(0f, 0f, entry.fileSizeMb, 0L))
                if (tmpFile.exists()) tmpFile.delete()

                val downloadUrl = if (cdnUrl.isNotBlank()) {
                    "$cdnUrl/download/$modelId"
                } else {
                    "$S3_BASE/$modelId/releases/$S3_RELEASE/$modelId-tflite-float.zip"
                }

                val useCdn = cdnUrl.isNotBlank()
                Log.i(TAG, "⬇️ [$modelId] Downloading from ${if (useCdn) "CF R2 CDN" else "S3"}: $downloadUrl")

                val reqBuilder = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "OmniFace-AI-Android/1.0")
                    .header("X-App-Version", "1")
                if (useCdn && secret.isNotBlank()) reqBuilder.header("X-OmniFace-Secret", secret)

                val call = http.newCall(reqBuilder.build())
                activeCalls[modelId] = call
                val response = call.execute()

                if (!response.isSuccessful) {
                    activeCalls.remove(modelId)
                    if (useCdn) {
                        Log.w(TAG, "[$modelId] CDN returned ${response.code}, falling back to S3")
                        downloadFromS3(entry, tmpFile, destFile)
                        return@launch
                    }
                    emit(modelId, QualcommModelState.Error("HTTP ${response.code}: ${response.message}"))
                    return@launch
                }

                val body = response.body ?: run {
                    activeCalls.remove(modelId)
                    emit(modelId, QualcommModelState.Error("Empty response")); return@launch
                }

                val contentLength = body.contentLength()
                val totalMb = if (contentLength > 0) contentLength / (1024f * 1024f) else entry.fileSizeMb

                var bytesRead = 0L
                var lastTime = System.currentTimeMillis()
                var lastBytes = 0L
                val buf = ByteArray(64 * 1024)

                body.byteStream().use { input ->
                    FileOutputStream(tmpFile).use { out ->
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            if (!coroutineContext.isActive) {
                                break
                            }
                            out.write(buf, 0, n)
                            bytesRead += n
                            val now = System.currentTimeMillis()
                            val elapsed = now - lastTime
                            if (elapsed >= 400) {
                                val speedKbps = ((bytesRead - lastBytes) * 1000L / elapsed) / 1024L
                                lastBytes = bytesRead
                                lastTime = now
                                val progress = if (contentLength > 0) (bytesRead.toFloat() / contentLength).coerceIn(0f, 1f) else 0.5f
                                emit(modelId, QualcommModelState.Downloading(progress, bytesRead / 1_048_576f, totalMb, speedKbps))
                            }
                        }
                        out.flush()
                    }
                }

                if (!coroutineContext.isActive) {
                    tmpFile.delete()
                    return@launch
                }

                // CDN serves raw .tflite; S3 serves .zip — handle both
                if (useCdn) {
                    if (tmpFile.renameTo(destFile)) {
                        Log.i(TAG, "✅ [$modelId] Installed at ${destFile.absolutePath} (${destFile.length() / 1024}KB)")
                        emit(modelId, QualcommModelState.Installed)
                    } else {
                        emit(modelId, QualcommModelState.Error("Failed to rename tmp file"))
                    }
                } else {
                    // S3 zip path
                    unzip(tmpFile, File(suiteRoot(context), entry.id))
                    tmpFile.delete()
                    if (resolveModelFile(context, entry) != null) {
                        emit(modelId, QualcommModelState.Installed)
                    } else {
                        emit(modelId, QualcommModelState.Error("Extraction failed"))
                    }
                }
            } catch (e: CancellationException) {
                tmpFile.delete()
                emit(modelId, if (resolveModelFile(context, entry) != null) QualcommModelState.Installed else QualcommModelState.Idle)
            } catch (e: Exception) {
                tmpFile.delete()
                if (jobs[modelId]?.isCancelled == true) {
                    emit(modelId, if (resolveModelFile(context, entry) != null) QualcommModelState.Installed else QualcommModelState.Idle)
                } else {
                    Log.e(TAG, "Download error for $modelId", e)
                    emit(modelId, QualcommModelState.Error(e.localizedMessage ?: "Unknown error"))
                }
            } finally {
                activeCalls.remove(modelId)
                jobs.remove(modelId)
            }
        }
    }

    private suspend fun downloadFromS3(entry: QualcommModelEntry, tmpFile: File, destFile: File) {
        val s3Url = "$S3_BASE/${entry.id}/releases/$S3_RELEASE/${entry.id}-tflite-float.zip"
        val call = http.newCall(
            Request.Builder().url(s3Url).header("User-Agent", "OmniFace-AI-Android/1.0").build()
        )
        activeCalls[entry.id] = call
        val response = call.execute()

        if (!response.isSuccessful) {
            emit(entry.id, QualcommModelState.Error("S3 HTTP ${response.code}"))
            return
        }

        val body = response.body ?: run { emit(entry.id, QualcommModelState.Error("S3 empty body")); return }
        val contentLength = body.contentLength()
        var bytesRead = 0L; var lastTime = System.currentTimeMillis(); var lastBytes = 0L
        val buf = ByteArray(64 * 1024)
        body.byteStream().use { input ->
            FileOutputStream(tmpFile).use { out ->
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    if (!kotlin.coroutines.coroutineContext.isActive) {
                        break
                    }
                    out.write(buf, 0, n); bytesRead += n
                    val now = System.currentTimeMillis(); val elapsed = now - lastTime
                    if (elapsed >= 400) {
                        val speedKbps = ((bytesRead - lastBytes) * 1000L / elapsed) / 1024L
                        lastBytes = bytesRead; lastTime = now
                        val progress = if (contentLength > 0) (bytesRead.toFloat() / contentLength).coerceIn(0f, 1f) else 0.5f
                        emit(entry.id, QualcommModelState.Downloading(progress, bytesRead / 1_048_576f, entry.fileSizeMb, speedKbps))
                    }
                }
                out.flush()
            }
        }
        if (!kotlin.coroutines.coroutineContext.isActive) {
            tmpFile.delete()
            return
        }
        unzip(tmpFile, File(suiteRoot(context), entry.id))
        tmpFile.delete()
        if (resolveModelFile(context, entry) != null) emit(entry.id, QualcommModelState.Installed)
        else emit(entry.id, QualcommModelState.Error("S3 extraction failed"))
    }

    fun cancelDownload(modelId: String) {
        try {
            activeCalls[modelId]?.cancel()
            activeCalls.remove(modelId)
        } catch (_: Throwable) {}
        jobs[modelId]?.cancel()
        jobs.remove(modelId)
        val entry = SUITE_MODELS.find { it.id == modelId } ?: return
        val tmp = File(File(suiteRoot(context), entry.relativeDir), "${entry.filename}.tmp")
        if (tmp.exists()) tmp.delete()
        emit(modelId, if (resolveModelFile(context, entry) != null) QualcommModelState.Installed else QualcommModelState.Idle)
    }

    fun deleteModel(modelId: String) {
        cancelDownload(modelId)
        val entry = SUITE_MODELS.find { it.id == modelId } ?: return
        resolveModelFile(context, entry)?.delete()
        emit(modelId, QualcommModelState.Idle)
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun getInitialState(entry: QualcommModelEntry): QualcommModelState =
        if (resolveModelFile(context, entry) != null) QualcommModelState.Installed else QualcommModelState.Idle

    private fun emit(modelId: String, state: QualcommModelState) {
        _states.value = _states.value.toMutableMap().apply { put(modelId, state) }
    }

    private fun unzip(zipFile: File, destDir: File) {
        destDir.mkdirs()
        java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) outFile.mkdirs()
                else { outFile.parentFile?.mkdirs(); FileOutputStream(outFile).use { zis.copyTo(it) } }
                zis.closeEntry(); entry = zis.nextEntry
            }
        }
    }
}
