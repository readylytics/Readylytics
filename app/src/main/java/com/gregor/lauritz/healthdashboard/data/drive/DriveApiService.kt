package com.gregor.lauritz.healthdashboard.data.drive

import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

@Serializable
data class DriveFileListResponse(
    val files: List<DriveFileResponse>,
)

@Serializable
data class DriveFileResponse(
    val id: String,
    val name: String,
)

@Serializable
data class DriveUploadResponse(
    val id: String,
)

interface DriveApiService {
    @GET("files")
    suspend fun listFiles(
        @Header("Authorization") authHeader: String,
        @Query("spaces") spaces: String = "appDataFolder",
        @Query("fields") fields: String = "files(id,name)",
    ): DriveFileListResponse

    @Multipart
    @POST("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
    suspend fun uploadFile(
        @Header("Authorization") authHeader: String,
        @Part("metadata") metadata: RequestBody,
        @Part file: MultipartBody.Part,
    ): DriveUploadResponse

    @Streaming
    @GET("files/{fileId}")
    suspend fun downloadFile(
        @Header("Authorization") authHeader: String,
        @Path("fileId") fileId: String,
        @Query("alt") alt: String = "media",
    ): Response<ResponseBody>

    @DELETE("files/{fileId}")
    suspend fun deleteFile(
        @Header("Authorization") authHeader: String,
        @Path("fileId") fileId: String,
    ): Response<Unit>
}
