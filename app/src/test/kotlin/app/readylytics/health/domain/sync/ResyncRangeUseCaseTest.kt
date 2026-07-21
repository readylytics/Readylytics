package app.readylytics.health.domain.sync

import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.domain.sync.link.SessionLinkReconciler
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

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

    private lateinit var useCase: ResyncRangeUseCase

    @Before
    fun setup() {
        coEvery { changeSynchronizer.applyPendingChanges() } returns HealthChangeSyncOutcome(emptySet(), false)
        every { checkpointStore.checkpoint } returns flowOf(null)
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
        // PERF-002/WP-20/WP-22: every non-empty RECOMPUTE range now fetches batched TRIMP-series and
        // baseline contexts once up front via these methods before calling the 5-arg
        // computeAndPersistDailySummary overload.
        coEvery { scoringRepository.fetchWalkForwardTrimpContext(any(), any(), any()) } returns
            WalkForwardTrimpContext(TreeMap(), TreeMap())
        coEvery { scoringRepository.fetchWalkForwardBaselineContext(any(), any(), any()) } returns
            WalkForwardBaselineContext(emptyList())

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
            coJustRun { hcRepo.readHrvSamplesPaged(capture(hrvFromSlot), any(), any()) }
            coJustRun { hcRepo.readHeartRateSamplesPaged(capture(hrFromSlot), any(), any()) }
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
            coJustRun { hcRepo.readHrvSamplesPaged(capture(hrvFromInstants), any(), any()) }
            coJustRun { hcRepo.readHeartRateSamplesPaged(capture(hrFromInstants), any(), any()) }
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
            coJustRun { hcRepo.readHrvSamplesPaged(any(), capture(hrvToInstants), any()) }
            coJustRun { hcRepo.readHeartRateSamplesPaged(any(), capture(hrToInstants), any()) }
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
    fun `resyncRange shrinks the ingest chunk after a Health Connect window timeout, then grows back`() =
        runTest {
            // HC-002: a window that can't be read within its budget must shrink and retry rather
            // than wedge the whole resync in a same-size retry loop. The timeout itself is
            // simulated by throwing HealthConnectWindowTimeoutException directly from the mocked HC
            // read (the real trigger -- HealthIngestionCoordinator's withTimeout expiring -- is
            // covered in isolation by HealthIngestionCoordinatorTimeoutTest); this test's concern is
            // ResyncRangeUseCase's shrink/retry/grow-back policy once that exception occurs.
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 25)
            val chunkDays = 10

            var callCount = 0
            val requestedWindowEnds = mutableListOf<Instant>()
            coEvery { hcRepo.readSleepSessions(any(), capture(requestedWindowEnds)) } coAnswers {
                callCount++
                if (callCount == 1) {
                    throw app.readylytics.health.domain.repository.HealthConnectWindowTimeoutException(
                        windowStart = Instant.EPOCH,
                        windowEnd = Instant.EPOCH,
                        cause = RuntimeException("synthetic timeout for test"),
                    )
                }
                emptyList()
            }

            val result = useCase.run(startDate, endDate, chunkDays = chunkDays, onProgress = null)

            assertTrue(result is app.readylytics.health.domain.model.Result.Success)
            val zoneId = ZoneId.systemDefault()
            val requestedChunkEnds = requestedWindowEnds.map { it.atZone(zoneId).toLocalDate() }
            assertEquals(
                listOf(
                    // Attempt 1 at the full chunk size times out.
                    startDate.plusDays(chunkDays.toLong()),
                    // Retry of the same chunk at half the size succeeds.
                    startDate.plusDays((chunkDays / 2).toLong()),
                    // Next chunk uses the full caller-supplied size again (grown back).
                    startDate.plusDays((chunkDays / 2).toLong()).plusDays(chunkDays.toLong()),
                    endDate.plusDays(1),
                ),
                requestedChunkEnds,
            )
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

            // HC-005/WP-08: readStepsRecords is now called twice per chunk regardless of the
            // selected device -- once by HealthIngestionCoordinator.ingestWindow (populates the raw
            // step_records table for every device, unfiltered) and once by StepCountFetcher.fetchRange
            // (the device-filtered daily-total aggregate actually used for scoring).
            coVerify(exactly = 2) { hcRepo.readStepsRecords(any(), any()) }
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

            // First call (ingestWindow's retryWithBackoff) throws, then succeeds on retry (2 calls);
            // the recompute-phase StepCountFetcher.fetchRange call succeeds immediately after (the
            // mock's last-defined `andThen` behavior persists) for a 3rd call. See HC-005/WP-08: the
            // ingestion coordinator now also reads raw step records, independent of the per-device
            // aggregate fetch this test originally exercised alone.
            coVerify(exactly = 3) { hcRepo.readStepsRecords(any(), any()) }
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

            coVerify { scoringRepository.computeAndPersistDailySummary(date, 0L, any(), any(), any()) }
        }

    @Test
    fun `resyncRange RECOMPUTE progress reports calendar days not internal chunk counts`() =
        runTest {
            val progress = mutableListOf<Triple<ResyncPhase, Int, Int>>()

            useCase.run(
                startDate = LocalDate.of(2024, 6, 1),
                endDate = LocalDate.of(2024, 6, 3),
                chunkDays = 30,
                onProgress = { phase, current, total ->
                    progress += Triple(phase, current, total)
                },
            )

            val recompute = progress.filter { it.first == ResyncPhase.RECOMPUTE }
            assertEquals(3, recompute.last().second)
            assertEquals(3, recompute.last().third)
            assertTrue(recompute.all { (_, _, total) -> total == 3 })
        }

    @Test
    fun `resyncRange emits phases in order for a fresh run`() =
        runTest {
            val phases = mutableListOf<ResyncPhase>()

            useCase.run(
                startDate = LocalDate.of(2024, 6, 1),
                endDate = LocalDate.of(2024, 6, 3),
                chunkDays = 30,
                onProgress = { phase, _, _ -> phases += phase },
            )

            assertEquals(
                listOf(ResyncPhase.INGEST, ResyncPhase.PRUNE, ResyncPhase.RECONCILE, ResyncPhase.RECOMPUTE),
                phases.distinct(),
            )
        }

    @Test
    fun `resyncRange resuming from a PRUNE checkpoint skips the INGEST phase signal`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 3)
            every { checkpointStore.checkpoint } returns
                flowOf(
                    ResyncCheckpoint(
                        startDate = startDate,
                        endDate = endDate,
                        phase = ResyncPhase.PRUNE,
                        nextDate = startDate,
                        selectionHash = "",
                        baselineChangeTokens = mapOf(HealthDataType.STEPS to "token"),
                    ),
                )
            val phases = mutableListOf<ResyncPhase>()

            useCase.run(startDate, endDate, chunkDays = 30, onProgress = { phase, _, _ -> phases += phase })

            assertEquals(
                listOf(ResyncPhase.PRUNE, ResyncPhase.RECONCILE, ResyncPhase.RECOMPUTE),
                phases.distinct(),
            )
        }

    @Test
    fun `resyncRange clears frozen baselines only for requested range before walk-forward recompute`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 3)

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            coVerifyOrder {
                healthIngestionStore.clearFrozenBaselines(startDate, endDate.plusDays(1), zoneId)
                scoringRepository.computeAndPersistDailySummary(startDate, any(), any(), any(), any())
                scoringRepository.computeAndPersistDailySummary(startDate.plusDays(1), any(), any(), any(), any())
                scoringRepository.computeAndPersistDailySummary(endDate, any(), any(), any(), any())
            }
        }

    @Test
    fun `skipIngestAndPrune runs reconcile and recompute without touching Health Connect or pruning`() =
        runTest {
            // SCORE-007: a settings-driven recompute-only pass must never re-read Health Connect
            // or prune, only rebuild session-linking and scores from already-stored raw data.
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 2)

            useCase.run(
                startDate = startDate,
                endDate = endDate,
                chunkDays = 30,
                onProgress = null,
                skipIngestAndPrune = true,
            )

            coVerify(exactly = 0) { hcRepo.readSleepSessions(any(), any()) }
            coVerify(exactly = 0) { hcRepo.readHeartRateSamplesPaged(any(), any(), any()) }
            coVerify(exactly = 0) { selectedSourcePruner.prune(any(), any(), any(), any()) }
            coVerify(exactly = 0) { changeSynchronizer.captureChangesTokens() }
            coVerify(exactly = 0) { changeSynchronizer.applyPendingChanges() }
            coVerify(exactly = 0) { changeSynchronizer.commitTokens(any()) }
            coVerify(exactly = 1) { sessionLinkReconciler.reconcile(any(), any(), any()) }
            coVerify(exactly = 2) { scoringRepository.computeAndPersistDailySummary(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `resyncRange shares one preferences snapshot across every recomputed day`() =
        runTest {
            // Each independent read of settingsRepo.userPreferences returns a distinct value here,
            // simulating a preference change mid-resync. SCORE-004 requires the walk-forward to
            // recompute every day from the single snapshot taken at the start of run(), never a
            // fresh per-day read, so every day's captured prefs argument must be identical.
            var accessCount = 0
            every { settingsRepo.userPreferences } answers {
                accessCount++
                flowOf(UserPreferences(scoringZoneId = "snapshot-$accessCount"))
            }
            val capturedPrefs = mutableListOf<UserPreferences>()
            coEvery {
                scoringRepository.computeAndPersistDailySummary(any(), any(), capture(capturedPrefs), any(), any())
            } returns Unit

            useCase.run(
                startDate = LocalDate.of(2024, 6, 1),
                endDate = LocalDate.of(2024, 6, 3),
                chunkDays = 30,
                onProgress = null,
            )

            assertEquals(3, capturedPrefs.size)
            assertEquals(1, capturedPrefs.distinct().size)
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
                scoringRepository.computeAndPersistDailySummary(startDate, any(), any(), any(), any())
            }
        }

    @Test
    fun `resyncRange collects range telemetry before and after ingest and prune`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 1)

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            // Each range count is called 3 times: baseline, after ingest, and after prune.
            coVerify(exactly = 3) { healthIngestionStore.countHeartRateInRange(any(), any()) }
            coVerify(exactly = 3) { healthIngestionStore.countHrvInRange(any(), any()) }
            coVerify(exactly = 3) { healthIngestionStore.countSleepSessionsInRange(any(), any()) }
            coVerify(exactly = 3) { healthIngestionStore.countWorkoutsInRange(any(), any()) }
        }

    @Test
    fun `resyncRange preserves permission revoked exception and diagnostic context`() =
        runTest {
            val expected =
                HealthConnectPermissionRevokedException(
                    cause = SecurityException("READ_HEART_RATE denied"),
                    operation = "read",
                    recordType = "HeartRateRecord",
                )
            coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any()) } throws expected

            val actual =
                assertFailsWith<HealthConnectPermissionRevokedException> {
                    useCase.run(
                        startDate = LocalDate.of(2024, 6, 1),
                        endDate = LocalDate.of(2024, 6, 1),
                        chunkDays = 30,
                        onProgress = null,
                    )
                }

            assertSame(expected, actual)
            assertTrue(actual.message.orEmpty().contains("operation=read"))
            assertTrue(actual.message.orEmpty().contains("recordType=HeartRateRecord"))
            assertTrue(actual.message.orEmpty().contains("READ_HEART_RATE denied"))
        }
}
