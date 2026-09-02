package app.readylytics.health.domain.sync

import app.readylytics.health.core.healthconnect.domain.sync.HealthSyncUseCase
import app.readylytics.health.core.healthconnect.domain.sync.SETTINGS_REFRESH_WINDOW_DAYS
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
import app.readylytics.health.core.model.workers.WorkerScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Task 4: [HealthDataRefreshAdapter.refreshTrainingReadiness] must enqueue the durable,
 * parameter-only projection recompute without touching [HealthSyncUseCase] (no Health Connect
 * I/O), and existing [HealthDataRefreshAdapter.refreshHistorical] callers must see no behavior
 * change.
 */
class HealthDataRefreshAdapterTest {
    private val healthSyncUseCase = mockk<HealthSyncUseCase>()
    private val workerScheduler = mockk<WorkerScheduler>(relaxed = true)
    private val adapter = HealthDataRefreshAdapter(healthSyncUseCase, workerScheduler)

    @Test
    fun `refreshAffectedWindow delegates to the settings refresh window`() =
        runTest {
            coEvery { healthSyncUseCase.sync(any(), any()) } returns Result.success(Unit)

            adapter.refreshAffectedWindow()

            coVerify(exactly = 1) { healthSyncUseCase.sync(windowDays = SETTINGS_REFRESH_WINDOW_DAYS) }
        }

    @Test
    fun `refreshHistorical still schedules a recompute-only resync unchanged`() =
        runTest {
            adapter.refreshHistorical()

            verify(exactly = 1) { workerScheduler.scheduleResyncWorker(recomputeOnly = true) }
            verify(exactly = 0) { workerScheduler.scheduleTrainingReadinessRecompute(any()) }
        }

    @Test
    fun `refreshTrainingReadiness schedules the projection recompute with the exact requested config`() =
        runTest {
            val requested = TrainingReadinessConfig.fromStored(100f, .9f)

            adapter.refreshTrainingReadiness(requested)

            val configSlot = slot<TrainingReadinessConfig>()
            verify(exactly = 1) { workerScheduler.scheduleTrainingReadinessRecompute(capture(configSlot)) }
            assertEquals(requested, configSlot.captured)
            verify(exactly = 0) { workerScheduler.scheduleResyncWorker(any(), any(), any()) }
        }

    @Test
    fun `refreshTrainingReadiness never touches Health Connect sync`() =
        runTest {
            adapter.refreshTrainingReadiness(TrainingReadinessConfig.fromStored(40f, .7f))

            coVerify(exactly = 0) { healthSyncUseCase.sync(any(), any()) }
        }
}
