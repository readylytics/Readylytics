package com.gregor.lauritz.healthdashboard.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gregor.lauritz.healthdashboard.domain.backup.BackupUseCase
import com.gregor.lauritz.healthdashboard.data.drive.DriveApiException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class BackupWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val backupUseCase: BackupUseCase,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val result = backupUseCase.execute()
            return when {
                result.isSuccess -> Result.success()
                result.exceptionOrNull() is DriveApiException -> Result.retry()
                else -> Result.failure()
            }
        }
    }
