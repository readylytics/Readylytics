package com.gregor.lauritz.healthdashboard.di

import android.app.Application
import androidx.work.WorkManager
import com.gregor.lauritz.healthdashboard.data.drive.DriveApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DriveModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .certificatePinner(
                CertificatePinner
                    .Builder()
                    .add("www.googleapis.com", "sha256/hxqRlPTu1bMS/0DITB1S6VWwS99nSND99Z2RSEtXyY0=") // GTS Root R1
                    .add("www.googleapis.com", "sha256/Vjs8r4z+80wjNcr1YKepqvboSIRi6WxdXXfS+e8G3p8=") // GTS Root R2
                    .build(),
            ).build()

    @Provides
    @Singleton
    fun provideJson(): Json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    @Provides
    @Singleton
    fun provideDriveApiService(
        client: OkHttpClient,
        json: Json,
    ): DriveApiService =
        Retrofit
            .Builder()
            .baseUrl("https://www.googleapis.com/drive/v3/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DriveApiService::class.java)

    @Provides
    @Singleton
    fun provideWorkManager(app: Application): WorkManager = WorkManager.getInstance(app)
}
