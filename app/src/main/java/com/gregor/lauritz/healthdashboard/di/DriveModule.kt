package com.gregor.lauritz.healthdashboard.di

import android.app.Application
import androidx.work.WorkManager
import com.gregor.lauritz.healthdashboard.data.drive.DriveApiService
import com.gregor.lauritz.healthdashboard.data.drive.GoogleDriveRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
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
            .build()

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
    fun provideWorkManager(app: Application): WorkManager {
        return WorkManager.getInstance(app)
    }
}
