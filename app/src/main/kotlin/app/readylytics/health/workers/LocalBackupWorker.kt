package app.readylytics.health.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.migration.DatabaseReadinessInspector
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.data.backup.LocalBackupManager
import dagger.Lazy
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
        private val localBackupManager: Lazy<LocalBackupManager>,
        private val databaseReadinessGate: DatabaseReadinessInspector,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (databaseReadinessGate.inspect() != DatabaseReadiness.Ready) {
                return Result.retry()
            }
            val result = localBackupManager.get().createBackup()
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
