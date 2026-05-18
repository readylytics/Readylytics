package com.gregor.lauritz.healthdashboard.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gregor.lauritz.healthdashboard.BuildConfig
import com.gregor.lauritz.healthdashboard.data.backup.LocalBackupManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.io.InterruptedIOException

@HiltWorker
class LocalBackupWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val localBackupManager: LocalBackupManager,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val result = localBackupManager.createBackup()
            return when {
                result.isSuccess -> {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Local backup created successfully")
                    Result.success()
                }
                else -> {
                    val cause = result.exceptionOrNull()
                    if (BuildConfig.DEBUG) Log.e(TAG, "Local backup failed", cause)
                    when (cause) {
                        is IOException, is InterruptedIOException -> {
                            Result.retry()
                        }
                        else -> {
                            Result.failure(workDataOf("error" to (cause?.message ?: "Unknown error")))
                        }
                    }
                }
            }
        }

        companion object {
            private const val TAG = "LocalBackupWorker"
        }
    }
