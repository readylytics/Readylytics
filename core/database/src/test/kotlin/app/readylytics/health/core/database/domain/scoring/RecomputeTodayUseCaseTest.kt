package app.readylytics.health.core.database.domain.scoring

import app.readylytics.health.core.database.data.local.HealthMutationCoordinatorImpl
import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.entity.HealthMutationStateEntity
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.repository.ScoringRepository
import app.readylytics.health.core.model.domain.sync.RecalcTrigger
import app.readylytics.health.core.model.workers.WorkerScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RecomputeTodayUseCaseTest {
    private val stateDao = mockk<HealthMutationStateDao>()
    private val coordinator = HealthMutationCoordinatorImpl(stateDao)
    private val scoring = mockk<ScoringRepository>(relaxed = true)
    private val scheduler = mockk<WorkerScheduler>(relaxed = true)
    private val day = LocalDate.of(2026, 9, 26)
    private val useCase = RecomputeTodayUseCase(coordinator, scoring, scheduler)

    init {
        coEvery { stateDao.getOrCreate() } returns HealthMutationStateEntity()
    }

    @Test
    fun recomputeWaitsUntilEntireMultiDayRunFinishes() = runTest {
        val events = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val run = async {
            coordinator.withMutation {
                events += "day1"
                release.await()
                events += "day2"
            }
        }
        runCurrent()
        coEvery { scoring.computeAndPersistDailySummary(day) } coAnswers { events += "today" }
        val today = async { useCase.execute(day) }
        runCurrent()
        assertEquals(listOf("day1"), events)
        release.complete(Unit)
        run.await()
        assertTrue(today.await().isSuccess)
        assertEquals(listOf("day1", "day2", "today"), events)
    }

    @Test
    fun maintenanceSchedulesExactlyOneBoundedDayWithoutScoring() = runTest {
        coEvery { stateDao.getOrCreate() } returns HealthMutationStateEntity(maintenanceOperationId = "restore")
        val result = useCase.execute(day)
        assertEquals("MAINTENANCE_PENDING", (result as Result.Failure).code)
        coVerify(exactly = 1) {
            scheduler.scheduleResyncWorker(true, day, day, RecalcTrigger.SETTINGS_CHANGE, null)
        }
        coVerify(exactly = 0) { scoring.computeAndPersistDailySummary(any()) }
    }

    @Test
    fun cancellationPropagatesWithoutScheduling() = runTest {
        coEvery { scoring.computeAndPersistDailySummary(day) } throws CancellationException("cancel")
        assertFailsWith<CancellationException> { useCase.execute(day) }
        coVerify(exactly = 0) { scheduler.scheduleResyncWorker(any(), any(), any(), any(), any()) }
    }

    @Test
    fun scoringFailureReturnsStableFailure() = runTest {
        coEvery { scoring.computeAndPersistDailySummary(day) } throws IllegalStateException("bad score")
        assertEquals("RECOMPUTE_TODAY_ERROR", (useCase.execute(day) as Result.Failure).code)
        coVerify(exactly = 0) { scheduler.scheduleResyncWorker(any(), any(), any(), any(), any()) }
    }

    @Test
    fun fallbackSchedulingFailureReturnsStableFailure() = runTest {
        coEvery { stateDao.getOrCreate() } returns HealthMutationStateEntity(maintenanceOperationId = "restore")
        coEvery {
            scheduler.scheduleResyncWorker(any(), any(), any(), any(), any())
        } throws IllegalStateException("enqueue")
        assertEquals("RECOMPUTE_TODAY_ERROR", (useCase.execute(day) as Result.Failure).code)
    }
}
