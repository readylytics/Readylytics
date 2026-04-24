package com.gregor.lauritz.healthdashboard.data.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class DriveFile(val id: String, val name: String)

private const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
private const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"

@Singleton
class GoogleDriveRepository
    @Inject
    constructor(
        private val client: OkHttpClient,
    ) {
        suspend fun listBackupFiles(accessToken: String): List<DriveFile> =
            withContext(Dispatchers.IO) {
                val request =
                    Request
                        .Builder()
                        .url("$DRIVE_FILES_URL?spaces=appDataFolder&fields=files(id,name)")
                        .addHeader("Authorization", "Bearer $accessToken")
                        .get()
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw DriveApiException("List failed: ${response.code}")
                    }
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val files = JSONObject(body).optJSONArray("files") ?: JSONArray()
                    List(files.length()) { i ->
                        val obj = files.getJSONObject(i)
                        DriveFile(id = obj.getString("id"), name = obj.getString("name"))
                    }
                }
            }

        suspend fun uploadBackup(
            accessToken: String,
            zipFile: File,
        ): String =
            withContext(Dispatchers.IO) {
                val metadata =
                    JSONObject()
                        .put("name", "health_backup.zip")
                        .put("parents", JSONArray().put("appDataFolder"))
                        .toString()

                val multipart =
                    MultipartBody
                        .Builder()
                        .setType(MultipartBody.FORM)
                        .addPart(
                            okhttp3.Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                            metadata.toRequestBody("application/json".toMediaType()),
                        ).addPart(
                            okhttp3.Headers.headersOf("Content-Type", "application/zip"),
                            zipFile.asRequestBody("application/zip".toMediaType()),
                        ).build()

                val request =
                    Request
                        .Builder()
                        .url("$DRIVE_UPLOAD_URL?uploadType=multipart")
                        .addHeader("Authorization", "Bearer $accessToken")
                        .post(multipart)
                        .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw DriveApiException("Upload failed: ${response.code}")
                    }
                    val body = response.body?.string() ?: throw DriveApiException("Empty upload response")
                    JSONObject(body).getString("id")
                }
            }

        suspend fun downloadBackup(
            accessToken: String,
            fileId: String,
            dest: File,
        ) = withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url("$DRIVE_FILES_URL/$fileId?alt=media")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DriveApiException("Download failed: ${response.code}")
                }
                val bytes = response.body?.bytes() ?: throw DriveApiException("Empty download response")
                dest.writeBytes(bytes)
            }
        }

        suspend fun deleteFile(
            accessToken: String,
            fileId: String,
        ) = withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url("$DRIVE_FILES_URL/$fileId")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .delete()
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 404) {
                    throw DriveApiException("Delete failed: ${response.code}")
                }
            }
        }
    }

class DriveApiException(message: String) : Exception(message)
