package com.omniface.ai.sync

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class DriveFileMeta(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedTime: String
)

/**
 * Google Drive REST API v3 Transport for User-Owned `appDataFolder`.
 *
 * Backs up and restores encrypted biometric snapshots directly to the user's
 * private Google Drive application sandbox without third-party server mediation.
 */
object GoogleDriveAppDataService {

    private const val TAG = "GoogleDriveService"
    private const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
    private const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Uploads the encrypted backup archive into the user's hidden appDataFolder.
     */
    suspend fun uploadBackup(
        accessToken: String,
        encryptedBytes: ByteArray,
        fileName: String = "omniface_backup_${System.currentTimeMillis()}.enc"
    ): Result<DriveFileMeta> = withContext(Dispatchers.IO) {
        try {
            val metadataJson = JSONObject().apply {
                put("name", fileName)
                put("parents", org.json.JSONArray().apply { put("appDataFolder") })
            }.toString()

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addPart(
                    metadataJson.toRequestBody("application/json; charset=UTF-8".toMediaType())
                )
                .addPart(
                    encryptedBytes.toRequestBody("application/octet-stream".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(DRIVE_UPLOAD_URL)
                .addHeader("Authorization", "Bearer $accessToken")
                .post(multipartBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.w(TAG, "Drive upload failed (HTTP ${response.code}): $body")
                    return@withContext Result.failure(Exception("Google Drive upload error: HTTP ${response.code}"))
                }

                val obj = JSONObject(body)
                val id = obj.getString("id")
                val name = obj.optString("name", fileName)
                Log.i(TAG, "✅ Successfully uploaded backup to Drive appDataFolder: $id")

                // Automatically retain only latest 3 backups to preserve user's Google Drive quota
                pruneOldBackups(accessToken, keepCount = 3)

                Result.success(
                    DriveFileMeta(
                        id = id,
                        name = name,
                        sizeBytes = encryptedBytes.size.toLong(),
                        modifiedTime = System.currentTimeMillis().toString()
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Lists existing backups from the user's hidden appDataFolder.
     */
    suspend fun listBackups(accessToken: String): Result<List<DriveFileMeta>> = withContext(Dispatchers.IO) {
        try {
            val url = "$DRIVE_FILES_URL?spaces=appDataFolder&fields=files(id,name,size,modifiedTime)&orderBy=modifiedTime%20desc"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Drive list error: HTTP ${response.code}"))
                }

                val json = JSONObject(body)
                val filesArr = json.optJSONArray("files") ?: org.json.JSONArray()
                val list = mutableListOf<DriveFileMeta>()
                for (i in 0 until filesArr.length()) {
                    val f = filesArr.getJSONObject(i)
                    list.add(
                        DriveFileMeta(
                            id = f.getString("id"),
                            name = f.getString("name"),
                            sizeBytes = f.optLong("size", 0L),
                            modifiedTime = f.optString("modifiedTime", "")
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Downloads an encrypted backup file from Google Drive.
     */
    suspend fun downloadBackup(
        accessToken: String,
        fileId: String
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val url = "$DRIVE_FILES_URL/$fileId?alt=media"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Drive download error: HTTP ${response.code}"))
                }
                val bytes = response.body?.bytes() ?: ByteArray(0)
                Result.success(bytes)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Retains the latest N backups and deletes older revisions to avoid wasting Google Drive quota.
     */
    private suspend fun pruneOldBackups(accessToken: String, keepCount: Int = 3) {
        try {
            val backups = listBackups(accessToken).getOrNull() ?: return
            if (backups.size > keepCount) {
                val toDelete = backups.drop(keepCount)
                for (b in toDelete) {
                    val delRequest = Request.Builder()
                        .url("$DRIVE_FILES_URL/${b.id}")
                        .addHeader("Authorization", "Bearer $accessToken")
                        .delete()
                        .build()
                    httpClient.newCall(delRequest).execute().close()
                    Log.i(TAG, "Pruned old backup: ${b.id} (${b.name})")
                }
            }
        } catch (_: Throwable) {}
    }
}
