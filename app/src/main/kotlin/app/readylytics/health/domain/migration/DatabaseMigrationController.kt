package app.readylytics.health.domain.migration

import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.readylytics.health.core.model.di.ApplicationScope
import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.core.model.domain.migration.DatabaseMigrationProgress
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.migration.DatabaseReadinessInspector
import app.readylytics.health.core.model.domain.migration.V7MigrationPhase
import app.readylytics.health.core.model.workers.WorkerScheduler
import app.readylytics.health.workers.DatabaseMigrationWorker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class DatabaseMigrationUiState(
    val readiness: DatabaseReadiness,
    val progress: DatabaseMigrationProgress? = null,
)

interface DatabaseMigrationController {
    val state: StateFlow<DatabaseMigrationUiState>

    fun startOrResume()
}

@Singleton
class DatabaseMigrationControllerImpl
    @Inject
    constructor(
        private val workerScheduler: WorkerScheduler,
        workManager: WorkManager,
        private val databaseReadinessInspector: DatabaseReadinessInspector,
        @ApplicationScope appScope: CoroutineScope,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : DatabaseMigrationController {
        private val initialState = DatabaseMigrationUiState(DatabaseReadiness.Checking)

        override val state: StateFlow<DatabaseMigrationUiState> =
            workManager
                .getWorkInfosForUniqueWorkFlow(WorkerScheduler.DATABASE_MIGRATION_WORK_NAME)
                .map { workInfos -> withContext(ioDispatcher) { mapState(workInfos) } }
                .stateIn(
                    scope = appScope,
                    started = SharingStarted.Eagerly,
                    initialValue = initialState,
                )

        override fun startOrResume() {
            workerScheduler.scheduleDatabaseMigration()
        }

        private fun mapState(workInfos: List<WorkInfo>): DatabaseMigrationUiState {
            val readiness = databaseReadinessInspector.inspect()
            val workInfo = workInfos.firstOrNull()
            return when {
                readiness == DatabaseReadiness.Ready -> DatabaseMigrationUiState(readiness)
                workInfo?.state == WorkInfo.State.FAILED -> DatabaseMigrationUiState(failureReadiness(workInfo))
                else -> DatabaseMigrationUiState(readiness, activeProgress(workInfo))
            }
        }

        private fun failureReadiness(workInfo: WorkInfo): DatabaseReadiness {
            val required = workInfo.outputData.getLong(DatabaseMigrationWorker.KEY_REQUIRED_BYTES, MISSING_BYTES)
            val available = workInfo.outputData.getLong(DatabaseMigrationWorker.KEY_AVAILABLE_BYTES, MISSING_BYTES)
            return if (required != MISSING_BYTES && available != MISSING_BYTES) {
                DatabaseReadiness.InsufficientSpace(required, available)
            } else {
                DatabaseReadiness.Failed("Database migration failed")
            }
        }

        private fun activeProgress(workInfo: WorkInfo?): DatabaseMigrationProgress? {
            if (workInfo?.state != WorkInfo.State.RUNNING && workInfo?.state != WorkInfo.State.ENQUEUED) return null
            return workInfo.progress
                .getString(DatabaseMigrationWorker.KEY_PHASE)
                ?.let { runCatching { V7MigrationPhase.valueOf(it) }.getOrNull() }
                ?.let { phase ->
                    DatabaseMigrationProgress(
                        phase,
                        workInfo.progress.getLong(DatabaseMigrationWorker.KEY_COPIED_ROWS, 0L),
                        workInfo.progress.getLong(DatabaseMigrationWorker.KEY_TOTAL_ROWS, 0L),
                    )
                }
        }

        private companion object {
            const val MISSING_BYTES = Long.MIN_VALUE
        }
    }
