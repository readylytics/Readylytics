package app.readylytics.health.workers

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.readylytics.health.data.migration.V7DatabaseMigrator
import app.readylytics.health.domain.migration.DatabaseMigrationProgress
import app.readylytics.health.domain.migration.V7MigrationPhase
import app.readylytics.health.domain.migration.V7MigrationResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class DatabaseMigrationWorker
    @AssistedInject
    constructor(
        @Assisted private val appContext: Context,
        @Assisted params: WorkerParameters,
        private val migrator: V7DatabaseMigrator,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            setForeground(buildForegroundInfo(PREFLIGHT_PROGRESS))

            return try {
                when (
                    val result =
                        migrator.migrate { progress ->
                            setProgress(
                                workDataOf(
                                    KEY_PHASE to progress.phase.name,
                                    KEY_COPIED_ROWS to progress.copiedRows,
                                    KEY_TOTAL_ROWS to progress.totalRows,
                                ),
                            )
                            setForeground(buildForegroundInfo(progress))
                        }
                ) {
                    V7MigrationResult.Complete -> Result.success()
                    is V7MigrationResult.InsufficientSpace ->
                        Result.failure(
                            workDataOf(
                                KEY_REQUIRED_BYTES to result.requiredBytes,
                                KEY_AVAILABLE_BYTES to result.availableBytes,
                            ),
                        )
                    is V7MigrationResult.Failed -> Result.retry()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Result.retry()
            }
        }

        override suspend fun getForegroundInfo(): ForegroundInfo = buildForegroundInfo(PREFLIGHT_PROGRESS)

        private fun buildForegroundInfo(progress: DatabaseMigrationProgress): ForegroundInfo {
            SyncNotifications.ensureDatabaseMigrationChannel(appContext)
            val notification =
                SyncNotifications.buildDatabaseMigrationNotification(appContext, progress)
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                ForegroundInfo(
                    SyncNotifications.DATABASE_MIGRATION_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                ForegroundInfo(
                    SyncNotifications.DATABASE_MIGRATION_NOTIFICATION_ID,
                    notification,
                )
            }
        }

        companion object {
            const val KEY_PHASE = "phase"
            const val KEY_COPIED_ROWS = "copiedRows"
            const val KEY_TOTAL_ROWS = "totalRows"
            const val KEY_REQUIRED_BYTES = "requiredBytes"
            const val KEY_AVAILABLE_BYTES = "availableBytes"

            private val PREFLIGHT_PROGRESS =
                DatabaseMigrationProgress(V7MigrationPhase.PREFLIGHT, 0L, 0L)
        }
    }
