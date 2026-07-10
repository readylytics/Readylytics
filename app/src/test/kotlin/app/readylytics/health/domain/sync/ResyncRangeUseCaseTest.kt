package app.readylytics.health.domain.sync

import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.dao.WorkoutDao
import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.sync.link.SessionLinkReconciler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Behavioral characterization of the full historical resync flow. As in [DailySyncUseCaseTest], the
 * data-touching collaborators are real over low-level mocks, preserving the chunk-boundary reach-
 * back, prune→reconcile→walk-forward ordering, step-device, and progress assertions post-extraction.
 */
class ResyncRangeUseCaseTest {
    private val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
    private val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringRepository = mockk<ScoringRepository>(relaxed = true)
    private val sessionLinkReconciler = mockk<SessionLinkReconciler>(relaxed = true)
    private val changeSynchronizer = mockk<HealthChangeSynchronizer>(relaxed = true)
    private val selectedSourcePruner = mockk<SelectedSourcePruner>(relaxed = true)
    private val checkpointStore = mockk<ResyncCheckpointStore>(relaxed = true)
    private val heartRateDao = mockk<HeartRateDao>(relaxed = true)
    private val hrvDao = mockk<HrvDao>(relaxed = true)
    private val sleepSessionDao = mockk<SleepSessionDao>(relaxed = true)
    private val workoutDao = mockk<WorkoutDao>(relaxed = true)

    private lateinit var useCase: ResyncRangeUseCase

    @Before
    fun setup() {
        coEvery { changeSynchronizer.applyPendingChanges() } returns HealthChangeSyncOutcome(emptySet(), false)
        every { checkpointStore.checkpoint } returns flowOf(null)
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())

        useCase =
            ResyncRangeUseCase(
                settingsRepo = settingsRepo,
                sessionLinkReconciler = sessionLinkReconciler,
                changeSynchronizer = changeSynchronizer,
                selectedSourcePruner = selectedSourcePruner,
                checkpointStore = checkpointStore,
                healthIngestionStore = healthIngestionStore,
                ingestionCoordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore),
                stepCountFetcher = StepCountFetcher(hcRepo),
                recomputeSupport = DailyRecomputeSupport(scoringRepository, settingsRepo),
                heartRateDao = heartRateDao,
                hrvDao = hrvDao,
                sleepSessionDao = sleepSessionDao,
                workoutDao = workoutDao,
                ioDispatcher = Dispatchers.Unconfined,
            )
    }

    @Test
    fun `resyncRange first chunk fetches samples from startDate minus 1 to cover cross-midnight sleep`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 2)

            val sleepFromSlot = slot<Instant>()
            val hrvFromSlot = slot<Instant>()
            val hrFromSlot = slot<Instant>()
            coEvery { hcRepo.readSleepSessions(capture(sleepFromSlot), any()) } returns emptyList()
            coEvery { hcRepo.readHrvSamples(capture(hrvFromSlot), any()) } returns emptyList()
            coEvery { hcRepo.readHeartRateSamples(capture(hrFromSlot), any()) } returns emptyList()
            useCase.run(startDate, endDate, chunkDays = 30, onProgress = null)

            // The first chunk of resyncRange must reach back one extra day to capture
            // overnight sleep sessions that began the previous evening.
            val reachBackMidnight =
                startDate
                    .minusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
            assertEquals(reachBackMidnight, sleepFromSlot.captured)
            assertEquals(reachBackMidnight, hrvFromSlot.captured)
            assertEquals(reachBackMidnight, hrFromSlot.captured)
        }

    @Test
    fun `resyncRange each chunk fetches samples from previous day to cover cross-midnight sleep`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 7, 2)
            val chunkDays = 30

            val hrvFromInstants = mutableListOf<Instant>()
            val hrFromInstants = mutableListOf<Instant>()
            coEvery { hcRepo.readHrvSamples(capture(hrvFromInstants), any()) } returns emptyList()
            coEvery { hcRepo.readHeartRateSamples(capture(hrFromInstants), any()) } returns emptyList()
            useCase.run(startDate, endDate, chunkDays = chunkDays, onProgress = null)

            val secondChunkStart = startDate.plusDays(chunkDays.toLong())
            val expectedSecondChunkReadStart =
                secondChunkStart
                    .minusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
            assertEquals(expectedSecondChunkReadStart, hrvFromInstants[1])
            assertEquals(expectedSecondChunkReadStart, hrFromInstants[1])
        }

    @Test
    fun `resyncRange caps sleep and workout interval reads to chunk end`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val startDate = LocalDate.of(2024, 6, 1)
            val chunkDays = 30
            val firstChunkEnd =
                startDate
                    .plusDays(chunkDays.toLong())
                    .atStartOfDay(zoneId)
                    .toInstant()

            val sleepToInstants = mutableListOf<Instant>()
            val workoutToInstants = mutableListOf<Instant>()
            val hrvToInstants = mutableListOf<Instant>()
            val hrToInstants = mutableListOf<Instant>()
            coEvery { hcRepo.readSleepSessions(any(), capture(sleepToInstants)) } returns emptyList()
            coEvery { hcRepo.readExerciseSessions(any(), capture(workoutToInstants)) } returns emptyList()
            coEvery { hcRepo.readHrvSamples(any(), capture(hrvToInstants)) } returns emptyList()
            coEvery { hcRepo.readHeartRateSamples(any(), capture(hrToInstants)) } returns emptyList()
            useCase.run(
                startDate = startDate,
                endDate = LocalDate.of(2024, 7, 2),
                chunkDays = chunkDays,
                onProgress = null,
            )

            assertEquals(firstChunkEnd, sleepToInstants[0])
            assertEquals(firstChunkEnd, workoutToInstants[0])
            assertEquals(firstChunkEnd, hrvToInstants[0])
            assertEquals(firstChunkEnd, hrToInstants[0])
        }

    @Test
    fun `resyncRange filters selected step device using raw step records`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(
                    UserPreferences(
                        deviceByDataType = mapOf(HealthDataType.STEPS.name to "Watch"),
                    ),
                )
            useCase.run(
                startDate = LocalDate.of(2024, 6, 1),
                endDate = LocalDate.of(2024, 6, 1),
                chunkDays = 30,
                onProgress = null,
            )

            coVerify(exactly = 1) { hcRepo.readStepsRecords(any(), any()) }
            coVerify(exactly = 0) { hcRepo.readSteps(any(), any()) }
        }

    @Test
    fun `resyncRange retries selected step device raw record fetch`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(
                    UserPreferences(
                        deviceByDataType = mapOf(HealthDataType.STEPS.name to "Watch"),
                    ),
                )
            coEvery { hcRepo.readStepsRecords(any(), any()) } throws RuntimeException("rate limited") andThen
                emptyList()

            useCase.run(
                startDate = LocalDate.of(2024, 6, 1),
                endDate = LocalDate.of(2024, 6, 1),
                chunkDays = 30,
                onProgress = null,
            )

            coVerify(exactly = 2) { hcRepo.readStepsRecords(any(), any()) }
        }

    @Test
    fun `resyncRange writes zero steps for selected device days without raw step records`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(
                    UserPreferences(
                        deviceByDataType = mapOf(HealthDataType.STEPS.name to "Watch"),
                    ),
                )
            coEvery { hcRepo.readStepsRecords(any(), any()) } returns emptyList()
            val date = LocalDate.of(2024, 6, 1)

            useCase.run(
                startDate = date,
                endDate = date,
                chunkDays = 30,
                onProgress = null,
            )

            coVerify { scoringRepository.computeAndPersistDailySummary(date, 0L) }
        }

    @Test
    fun `resyncRange progress reports calendar days not internal two phase steps`() =
        runTest {
            val progress = mutableListOf<Pair<Int, Int>>()

            useCase.run(
                startDate = LocalDate.of(2024, 6, 1),
                endDate = LocalDate.of(2024, 6, 3),
                chunkDays = 30,
            ) { current, total ->
                progress += current to total
            }

            assertEquals(3, progress.last().first)
            assertEquals(3, progress.last().second)
            assert(progress.all { (_, total) -> total == 3 })
        }

    @Test
    fun `resyncRange clears frozen baselines only for requested range before walk-forward recompute`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 3)

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            coVerifyOrder {
                healthIngestionStore.clearFrozenBaselines(startDate, endDate.plusDays(1))
                scoringRepository.computeAndPersistDailySummary(startDate, any())
                scoringRepository.computeAndPersistDailySummary(startDate.plusDays(1), any())
                scoringRepository.computeAndPersistDailySummary(endDate, any())
            }
        }

    @Test
    fun `resyncRange calls SelectedSourcePruner after ingestion and before session linkage reconciliation`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 3)

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            coVerifyOrder {
                selectedSourcePruner.prune(startDate, endDate, any(), any())
                sessionLinkReconciler.reconcile(any(), any(), any())
                scoringRepository.computeAndPersistDailySummary(startDate, any())
            }
        }

    @Test
    fun `resyncRange collects telemetry by querying DAO counts before and after phases`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 1)

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            // count() is called 3 times: baseline, after ingest, and after prune
            coVerify(exactly = 3) { heartRateDao.count() }
            coVerify(exactly = 3) { hrvDao.count() }
            coVerify(exactly = 3) { sleepSessionDao.count() }
            coVerify(exactly = 3) { workoutDao.count() }
        }
}
