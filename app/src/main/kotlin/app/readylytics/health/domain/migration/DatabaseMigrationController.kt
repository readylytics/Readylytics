package app.readylytics.health.domain.migration

import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.readylytics.health.di.ApplicationScope
import app.readylytics.health.workers.DatabaseMigrationWorker
import app.readylytics.health.workers.WorkerScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    ) : DatabaseMigrationController {
        private val initialState = DatabaseMigrationUiState(databaseReadinessInspector.inspect())

        override val state: StateFlow<DatabaseMigrationUiState> =
            workManager
                .getWorkInfosForUniqueWorkFlow(WorkerScheduler.DATABASE_MIGRATION_WORK_NAME)
                .map(::mapState)
                .stateIn(
                    scope = appScope,
                    started = SharingStarted.Eagerly,
                    initialValue = initialState,
                )

        override fun startOrResume() {
            workerScheduler.scheduleDatabaseMigration()
        }

        private fun mapState(workInfos: List<WorkInfo>): DatabaseMigrationUiState {
            val workInfo =
                workInfos.firstOrNull()
                    ?: return DatabaseMigrationUiState(databaseReadinessInspector.inspect())

            // WorkManager keeps terminal WorkInfo around across process restarts, so a failure from
            // an earlier run is still the first thing this flow emits on the next cold start.
            // The database itself is the authority on whether that failure still matters: once it
            // reports Ready, a stale FAILED record must not pin the user to the retry screen.
            val readiness = databaseReadinessInspector.inspect()
            if (workInfo.state == WorkInfo.State.FAILED && readiness != DatabaseReadiness.Ready) {
                val requiredBytes =
                    workInfo.outputData.getLong(DatabaseMigrationWorker.KEY_REQUIRED_BYTES, MISSING_BYTES)
                val availableBytes =
                    workInfo.outputData.getLong(DatabaseMigrationWorker.KEY_AVAILABLE_BYTES, MISSING_BYTES)
                return if (requiredBytes != MISSING_BYTES && availableBytes != MISSING_BYTES) {
                    DatabaseMigrationUiState(
                        DatabaseReadiness.InsufficientSpace(requiredBytes, availableBytes),
                    )
                } else {
                    DatabaseMigrationUiState(DatabaseReadiness.Failed("Database migration failed"))
                }
            }

            val progress =
                workInfo.progress
                    .getString(DatabaseMigrationWorker.KEY_PHASE)
                    ?.let { phaseName -> runCatching { V7MigrationPhase.valueOf(phaseName) }.getOrNull() }
                    ?.let { phase ->
                        DatabaseMigrationProgress(
                            phase = phase,
                            copiedRows =
                                workInfo.progress.getLong(DatabaseMigrationWorker.KEY_COPIED_ROWS, 0L),
                            totalRows =
                                workInfo.progress.getLong(DatabaseMigrationWorker.KEY_TOTAL_ROWS, 0L),
                        )
                    }
            return DatabaseMigrationUiState(
                readiness = readiness,
                progress =
                    if (workInfo.state == WorkInfo.State.RUNNING || workInfo.state == WorkInfo.State.ENQUEUED) {
                        progress
                    } else {
                        null
                    },
            )
        }

        private companion object {
            const val MISSING_BYTES = Long.MIN_VALUE
        }
    }
