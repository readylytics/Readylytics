package app.readylytics.health.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.readylytics.health.data.backup.LocalBackupManager
import app.readylytics.health.domain.util.logD
import app.readylytics.health.domain.util.logE
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
                    logD(TAG) { "Local backup created successfully" }
                    Result.success()
                }
                else -> {
                    val cause = result.exceptionOrNull()
                    logE(TAG, cause) { "Local backup failed" }
                    when (cause) {
                        is IOException, is InterruptedIOException -> {
                            Result.retry()
                        }
                        else -> {
                            Result.failure(workDataOf("error" to "Local backup failed"))
                        }
                    }
                }
            }
        }

        companion object {
            private const val TAG = "LocalBackupWorker"
        }
    }
