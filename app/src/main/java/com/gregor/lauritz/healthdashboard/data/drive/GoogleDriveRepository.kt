package com.gregor.lauritz.healthdashboard.data.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class DriveFile(
    val id: String,
    val name: String,
)

@Singleton
class GoogleDriveRepository
    @Inject
    constructor(
        private val apiService: DriveApiService,
    ) {
        suspend fun listBackupFiles(accessToken: String): List<DriveFile> =
            withContext(Dispatchers.IO) {
                try {
                    val response = apiService.listFiles("Bearer $accessToken")
                    response.files.map { DriveFile(it.id, it.name) }
                } catch (e: Exception) {
                    throw DriveApiException("List failed: ${e.message}")
                }
            }

        suspend fun uploadBackup(
            accessToken: String,
            zipFile: File,
        ): String =
            withContext(Dispatchers.IO) {
                val metadataJson =
                    JSONObject()
                        .put("name", "health_backup.zip")
                        .put("parents", JSONArray().put("appDataFolder"))
                        .toString()

                val metadataPart = metadataJson.toRequestBody("application/json".toMediaType())
                val filePart =
                    MultipartBody.Part.createFormData(
                        "file",
                        zipFile.name,
                        zipFile.asRequestBody("application/zip".toMediaType()),
                    )

                try {
                    val response = apiService.uploadFile("Bearer $accessToken", metadataPart, filePart)
                    response.id
                } catch (e: Exception) {
                    throw DriveApiException("Upload failed: ${e.message}")
                }
            }

        suspend fun downloadBackup(
            accessToken: String,
            fileId: String,
            dest: File,
        ) = withContext(Dispatchers.IO) {
            try {
                val response = apiService.downloadFile("Bearer $accessToken", fileId)
                if (response.isSuccessful) {
                    response.body()?.use { body ->
                        body.byteStream().use { input ->
                            dest.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } ?: throw DriveApiException("Empty download response")
                } else {
                    throw DriveApiException("Download failed: ${response.code()}")
                }
            } catch (e: Exception) {
                throw DriveApiException("Download failed: ${e.message}")
            }
        }

        suspend fun deleteFile(
            accessToken: String,
            fileId: String,
        ) = withContext(Dispatchers.IO) {
            try {
                val response = apiService.deleteFile("Bearer $accessToken", fileId)
                if (!response.isSuccessful && response.code() != 404) {
                    throw DriveApiException("Delete failed: ${response.code()}")
                }
            } catch (e: Exception) {
                throw DriveApiException("Delete failed: ${e.message}")
            }
        }
    }

class DriveApiException(
    message: String,
) : Exception(message)
