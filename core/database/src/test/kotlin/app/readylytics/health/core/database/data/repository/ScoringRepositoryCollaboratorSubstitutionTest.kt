package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.database.testutil.ScoringTestLoaders
import app.readylytics.health.core.database.testutil.ScoringTestUseCases
import app.readylytics.health.core.database.testutil.createScoringRepositoryForTest
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.scoring.DayAssembly
import app.readylytics.health.core.model.domain.scoring.DayAssemblyUnavailableReason
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Proves the DI goal of `ScoringRepositoryImpl`'s constructor: its day-assembly collaborator is
 * replaceable, and the repository's own contract (commit walk-forward contexts only after a
 * genuine candidate / successful persist; release the mutex on cancellation) holds around
 * whatever [DayAssembler] it is given.
 */
class ScoringRepositoryCollaboratorSubstitutionTest {
    private val dataLoader = mockk<ScoringDayDataLoader>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>()
    private val dayAssembler = mockk<DayAssembler>()
    private val commitCount = AtomicInteger(0)
    private val date = LocalDate.of(2026, 1, 15)

    @Before
    fun setup() {
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
    }

    private fun createRepo(): ScoringRepositoryImpl =
        createScoringRepositoryForTest(
            loaders =
                ScoringTestLoaders(
                    dataLoader = dataLoader,
                    bodyMetricsDataLoader = mockk(relaxed = true),
                    seriesLoader = mockk(relaxed = true),
                    heartRateDataLoader = mockk(relaxed = true),
                ),
            useCases =
                ScoringTestUseCases(
                    computeDailyTrimp = mockk(relaxed = true),
                    resolveDailyBaselines = mockk(relaxed = true),
                    computeTrainingReadiness = mockk(relaxed = true),
                ),
            settingsRepo = settingsRepo,
            baselineComputer = mockk(relaxed = true),
            scoringConfigFactory = mockk(relaxed = true),
            scoringHistoryRepository = mockk(relaxed = true),
            readinessSummaryCoordinator = mockk(relaxed = true),
            dayAssembler = dayAssembler,
        )

    private fun computedDay(assembly: DayAssembly): ComputedDay =
        ComputedDay(
            assembly = assembly,
            workouts = emptyList(),
            workoutUpdates = emptyList(),
            commitWalkForwardContexts = { commitCount.incrementAndGet() },
        )

    private fun genuineAssembly(): DayAssembly = DayAssembly.Computed(mockk<DailySummary>(relaxed = true))

    @Test
    fun computeDailySummary_unavailableAssembly_throwsAndNeverCommits() =
        runTest {
            coEvery { dayAssembler.computeDay(date, any(), any(), any()) } returns
                computedDay(DayAssembly.Unavailable(DayAssemblyUnavailableReason.WORKOUT_LOAD_UNAVAILABLE))

            assertFailsWith<DayAssemblyUnavailableException> { createRepo().computeDailySummary(date) }

            assertEquals(0, commitCount.get())
        }

    @Test
    fun computeAndPersistDailySummary_persistRejected_throwsAndNeverCommits() =
        runTest {
            coEvery { dayAssembler.computeDay(date, any(), any(), any()) } returns computedDay(genuineAssembly())
            coEvery { dataLoader.persistDayAssembly(any(), any(), any(), any(), any()) } returns false

            assertFailsWith<DayAssemblyUnavailableException> { createRepo().computeAndPersistDailySummary(date) }

            assertEquals(0, commitCount.get())
        }

    @Test
    fun computeAndPersistDailySummary_persistAccepted_commitsOnce() =
        runTest {
            coEvery { dayAssembler.computeDay(date, any(), any(), any()) } returns computedDay(genuineAssembly())
            coEvery { dataLoader.persistDayAssembly(any(), any(), any(), any(), any()) } returns true

            createRepo().computeAndPersistDailySummary(date)

            assertEquals(1, commitCount.get())
        }

    @Test
    fun computeAndPersistDailySummary_propagatesCancellation() =
        runTest {
            coEvery { dayAssembler.computeDay(date, any(), any(), any()) } throws
                CancellationException("cancelled") andThen computedDay(genuineAssembly())
            coEvery { dataLoader.persistDayAssembly(any(), any(), any(), any(), any()) } returns true
            val repo = createRepo()

            assertFailsWith<CancellationException> { repo.computeAndPersistDailySummary(date) }
            assertEquals(0, commitCount.get())

            // The mutex was released by the cancelled call: a second call acquires it and completes.
            repo.computeAndPersistDailySummary(date)
            assertEquals(1, commitCount.get())
        }
}
