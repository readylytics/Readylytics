package app.readylytics.health.domain.migration

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.readylytics.health.domain.migration.DatabaseMigrationProgress
import app.readylytics.health.domain.migration.DatabaseReadiness
import app.readylytics.health.domain.migration.V7MigrationPhase
import app.readylytics.health.workers.DatabaseMigrationWorker
import app.readylytics.health.workers.WorkerScheduler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DatabaseMigrationControllerTest {
    private val workInfos = MutableStateFlow<List<WorkInfo>>(emptyList())
    private val scheduler = mockk<WorkerScheduler>(relaxed = true)
    private val gate = mockk<DatabaseReadinessInspector>()
    private val workManager =
        mockk<WorkManager> {
            every {
                getWorkInfosForUniqueWorkFlow(WorkerScheduler.DATABASE_MIGRATION_WORK_NAME)
            } returns workInfos
        }
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @Test
    fun `initial state comes from readiness gate`() {
        every { gate.inspect() } returns DatabaseReadiness.MigrationRequired(6)

        val controller = controller()

        assertEquals(
            DatabaseMigrationUiState(DatabaseReadiness.MigrationRequired(6)),
            controller.state.value,
        )
    }

    @Test
    fun `key corruption maps to KeyCorrupted state`() {
        every { gate.inspect() } returns DatabaseReadiness.KeyCorrupted

        val controller = controller()

        assertEquals(
            DatabaseMigrationUiState(DatabaseReadiness.KeyCorrupted),
            controller.state.value,
        )
    }

    @Test
    fun `running work maps exact migration progress keys`() {
        every { gate.inspect() } returns DatabaseReadiness.MigrationRequired(6)
        val controller = controller()
        workInfos.value =
            listOf(
                workInfo(
                    state = WorkInfo.State.RUNNING,
                    progress =
                        Data
                            .Builder()
                            .putString(DatabaseMigrationWorker.KEY_PHASE, V7MigrationPhase.COPY_HRV.name)
                            .putLong(DatabaseMigrationWorker.KEY_COPIED_ROWS, 42L)
                            .putLong(DatabaseMigrationWorker.KEY_TOTAL_ROWS, 100L)
                            .build(),
                ),
            )

        scope.advanceUntilIdle()

        assertEquals(
            DatabaseMigrationUiState(
                readiness = DatabaseReadiness.MigrationRequired(6),
                progress =
                    DatabaseMigrationProgress(
                        phase = V7MigrationPhase.COPY_HRV,
                        copiedRows = 42L,
                        totalRows = 100L,
                    ),
            ),
            controller.state.value,
        )
    }

    @Test
    fun `successful work refreshes readiness`() {
        every { gate.inspect() } returnsMany
            listOf(DatabaseReadiness.MigrationRequired(6), DatabaseReadiness.Ready)
        val controller = controller()

        workInfos.value = listOf(workInfo(WorkInfo.State.SUCCEEDED))
        scope.advanceUntilIdle()

        assertEquals(DatabaseMigrationUiState(DatabaseReadiness.Ready), controller.state.value)
    }

    @Test
    fun `insufficient space failure maps output bytes`() {
        every { gate.inspect() } returns DatabaseReadiness.MigrationRequired(6)
        val controller = controller()
        workInfos.value =
            listOf(
                workInfo(
                    state = WorkInfo.State.FAILED,
                    output =
                        Data
                            .Builder()
                            .putLong(DatabaseMigrationWorker.KEY_REQUIRED_BYTES, 900L)
                            .putLong(DatabaseMigrationWorker.KEY_AVAILABLE_BYTES, 400L)
                            .build(),
                ),
            )

        scope.advanceUntilIdle()

        assertEquals(
            DatabaseMigrationUiState(DatabaseReadiness.InsufficientSpace(900L, 400L)),
            controller.state.value,
        )
    }

    @Test
    fun `start or resume schedules unique migration work`() {
        every { gate.inspect() } returns DatabaseReadiness.MigrationRequired(5)
        val controller = controller()

        controller.startOrResume()

        verify(exactly = 1) { scheduler.scheduleDatabaseMigration() }
    }

    private fun controller() =
        DatabaseMigrationControllerImpl(
            workerScheduler = scheduler,
            workManager = workManager,
            databaseReadinessInspector = gate,
            appScope = scope,
        )

    private fun workInfo(
        state: WorkInfo.State,
        progress: Data = Data.EMPTY,
        output: Data = Data.EMPTY,
    ): WorkInfo =
        mockk {
            every { this@mockk.state } returns state
            every { this@mockk.progress } returns progress
            every { outputData } returns output
            every { id } returns UUID.randomUUID()
        }
}
