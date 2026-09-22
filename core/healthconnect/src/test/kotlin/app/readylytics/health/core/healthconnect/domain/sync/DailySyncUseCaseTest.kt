package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.sync.*
import app.readylytics.health.core.database.domain.sync.DailyRecomputeSupport
import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord
import app.readylytics.health.core.model.domain.model.DomainHrvRecord
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.HealthConnectWindowTimeoutException
import app.readylytics.health.core.model.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.ScoringRepository
import app.readylytics.health.core.model.domain.repository.WalDiagnostics
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.scoring.domain.scoring.RasSourceModeBootstrapUseCase
import app.readylytics.health.core.model.domain.sync.link.SessionLinkReconciler
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertFailsWith
import app.readylytics.health.core.model.domain.repository.WalkForwardContexts

/**
 * Behavioral characterization of the foreground daily-sync flow. The collaborators that actually
 * touch Health Connect / Room / scoring ([HealthIngestionCoordinator], [StepCountFetcher],
 * [DailyRecomputeSupport]) are constructed for real over the same low-level mocks the god-class
 * test used, so the call-order/idempotency assertions are preserved across the M1 extraction.
 */
abstract class DailySyncUseCaseTestFixture {
    protected val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
    protected val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
    protected val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    protected val scoringRepository = mockk<ScoringRepository>(relaxed = true)
    protected val sessionLinkReconciler = mockk<SessionLinkReconciler>(relaxed = true)
    protected val rasSourceModeBootstrapUseCase = mockk<RasSourceModeBootstrapUseCase>(relaxed = true)
    protected val changeSynchronizer = mockk<HealthChangeSynchronizer>(relaxed = true)
    protected val transactionRunner = RecordingTransactionRunner()
    protected val walDiagnostics = mockk<WalDiagnostics>(relaxed = true)

    // Fixed rather than Clock.systemDefaultZone() so every "today" computed below is deterministic
    // (DI-002): production resolves "today" via clock.withZone(zoneId), so this must be the same
    // clock instance the tests build their expected dates from.
    protected val fixedClock = Clock.fixed(Instant.parse("2024-06-15T12:00:00Z"), ZoneId.of("UTC"))

    protected lateinit var useCase: DailySyncUseCase

    @Before
    fun setup() {
        coEvery { changeSynchronizer.applyPendingChanges() } returns HealthChangeSyncOutcome(emptySet(), false)
        coJustRun { changeSynchronizer.commitTokens(any()) }
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
        // WP-27: the daily walk-forward builds one mutable fatigue accumulator per run; give the
        // relaxed mock a real (empty) context so recomputeDay receives a non-null instance.
        coEvery { scoringRepository.fetchWalkForwardFatigueContext(any(), any(), any(), any()) } returns
            WalkForwardFatigueContext(emptyList())
        coEvery { hcRepo.readSleepSessions(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readExerciseSessions(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any()) } returns ReadOutcome.Available(Unit)
        coEvery { hcRepo.readHrvSamplesPaged(any(), any(), any(), any()) } returns ReadOutcome.Available(Unit)
        coEvery { hcRepo.readStepsRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readSteps(any(), any()) } returns ReadOutcome.Available(0L)
        coEvery { hcRepo.readDailyStepTotals(any(), any(), any()) } returns ReadOutcome.Available(emptyMap())
        coEvery { hcRepo.readWeightRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBodyFatRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBloodPressureRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readOxygenSaturationRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBodyTemperatureRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readVo2MaxRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.hasVo2MaxPermission() } returns false

        useCase =
            DailySyncUseCase(
                settingsRepo = settingsRepo,
                sessionLinkReconciler = sessionLinkReconciler,
                rasSourceModeBootstrapUseCase = rasSourceModeBootstrapUseCase,
                changeSynchronizer = changeSynchronizer,
                healthIngestionStore = healthIngestionStore,
                ingestionCoordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore, FakeScanStagingStore()),
                stepCountFetcher = StepCountFetcher(hcRepo),
                recomputeSupport = DailyRecomputeSupport(scoringRepository, settingsRepo, transactionRunner),
                walDiagnostics = walDiagnostics,
                ioDispatcher = Dispatchers.Unconfined,
                clock = fixedClock,
            )
    }

}
class DailySyncUseCaseTest : DailySyncUseCaseTestFixture() {
    @Test
    fun `sync processes days in chronological order`() =
        runTest {
            val windowDays = 3
            val today = LocalDate.now(fixedClock.withZone(ZoneId.systemDefault()))
            val day0 = today.minusDays(2)
            val day1 = today.minusDays(1)
            val day2 = today

            useCase.run(windowDays = windowDays, onProgress = null)

            coVerifyOrder {
                scoringRepository.computeAndPersistDailySummary(day0, 0L, any(), any(), any())
                scoringRepository.computeAndPersistDailySummary(day1, 0L, any(), any(), any())
                scoringRepository.computeAndPersistDailySummary(day2, 0L, any(), any(), any())
            }
        }

    @Test
    fun `sync shares one preferences snapshot across every recomputed day`() =
        runTest {
            // Each independent read of settingsRepo.userPreferences returns a distinct value here,
            // simulating a preference change mid-sync. SCORE-004 requires the walk-forward to
            // recompute every day from the single snapshot taken at the start of run(), never a
            // fresh per-day read, so every day's captured prefs argument must be identical.
            var accessCount = 0
            every { settingsRepo.userPreferences } answers {
                accessCount++
                flowOf(UserPreferences(scoringZoneId = "snapshot-$accessCount"))
            }
            val capturedPrefs = mutableListOf<UserPreferences>()
            coEvery {
                scoringRepository.computeAndPersistDailySummary(
                    any(),
                    any(),
                    capture(capturedPrefs),
                    any(),
                    any())
            } returns Unit

            useCase.run(windowDays = 3, onProgress = null)

            assertEquals(3, capturedPrefs.size)
            assertEquals(1, capturedPrefs.distinct().size)
        }

    @Test
    fun `sync builds one walk-forward context pair and shares it across every recomputed day`() =
        runTest {
            // PERF-002/WP-20/WP-22 shape, now on the daily path: each recomputed day must read the
            // TRIMP series and the RHR/HRV baseline window through ONE context built for the whole
            // window, not re-query its own 84-/56-day lookback per day.
            val capturedContexts = mutableListOf<WalkForwardContexts>()
            val capturedRunContexts = mutableListOf<ScoringRunContext>()
            coEvery {
                scoringRepository.computeAndPersistDailySummary(
                    any(),
                    any(),
                    any(),
                    capture(capturedContexts),
                    capture(capturedRunContexts),
                )
            } returns Unit

            useCase.run(windowDays = 3, onProgress = null)

            val capturedTrimp = capturedContexts.map { it.trimp }
            val capturedBaseline = capturedContexts.map { it.baseline }
            val capturedFatigue = capturedContexts.map { it.fatigue }
            assertEquals(3, capturedTrimp.size)
            assertEquals(1, capturedTrimp.distinctBy { System.identityHashCode(it) }.size)
            assertEquals(3, capturedBaseline.size)
            assertEquals(1, capturedBaseline.distinctBy { System.identityHashCode(it) }.size)
            assertEquals(3, capturedFatigue.size)
            assertEquals(1, capturedFatigue.distinctBy { System.identityHashCode(it) }.size)
            assertEquals(3, capturedRunContexts.size)
            assertEquals(1, capturedRunContexts.distinctBy { System.identityHashCode(it) }.size)
            coVerify(exactly = 1) {
                scoringRepository.fetchWalkForwardTrimpContext(any(), any(), any())
            }
            coVerify(exactly = 1) {
                scoringRepository.fetchWalkForwardBaselineContext(any(), any(), any())
            }
            coVerify(exactly = 1) {
                scoringRepository.fetchWalkForwardFatigueContext(any(), any(), any(), any())
            }
        }

    @Test
    fun `sync builds the walk-forward contexts over the widened recompute window`() =
        runTest {
            // The window widens to absorb a recent out-of-window HC change (see the
            // `absorbs recent out-of-window change inline` test); the contexts must cover the
            // widened range, not the nominal windowDays range, or the widened day reads an
            // incomplete series.
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(fixedClock.withZone(zoneId))
            val yesterday = today.minusDays(1)
            coEvery { changeSynchronizer.applyPendingChanges() } returns
                HealthChangeSyncOutcome(
                    affectedDates = setOf(yesterday),
                    requiresFullResync = false,
                )

            useCase.run(windowDays = 1, onProgress = null)

            coVerify(exactly = 1) {
                scoringRepository.fetchWalkForwardTrimpContext(yesterday, today, any())
            }
            coVerify(exactly = 1) {
                scoringRepository.fetchWalkForwardBaselineContext(yesterday, today, any())
            }
            coVerify(exactly = 1) {
                scoringRepository.fetchWalkForwardFatigueContext(yesterday, today, any(), any())
            }
        }

    @Test
    fun `sync commits candidate change tokens after scoring succeeds`() =
        runTest {
            val nextTokens = mapOf(HealthDataType.SLEEP to "next-sleep-token")
            coEvery { changeSynchronizer.applyPendingChanges() } returns
                HealthChangeSyncOutcome(
                    affectedDates = emptySet(),
                    requiresFullResync = false,
                    nextTokens = nextTokens,
                )

            useCase.run(windowDays = 1, onProgress = null)

            coVerifyOrder {
                scoringRepository.computeAndPersistDailySummary(any(), any(), any(), any(), any())
                changeSynchronizer.commitTokens(nextTokens)
            }
        }

    @Test
    fun `sync clears frozen baselines for scoring window before recomputing days`() =
        runTest {
            val windowDays = 2
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(fixedClock.withZone(zoneId))

            useCase.run(windowDays = windowDays, onProgress = null)

            coVerifyOrder {
                healthIngestionStore.clearFrozenBaselines(today.minusDays(1), today.plusDays(1), zoneId)
                scoringRepository.computeAndPersistDailySummary(today.minusDays(1), 0L, any(), any(), any())
                scoringRepository.computeAndPersistDailySummary(today, 0L, any(), any(), any())
            }
        }

    @Test
    fun `sync reconciles ingested overlap before scoring days`() =
        runTest {
            val windowDays = 1
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(fixedClock.withZone(zoneId))
            val ingestStartMs =
                today
                    .minusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val windowEndExclusiveMs =
                today
                    .plusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()

            coJustRun { sessionLinkReconciler.reconcile(any(), any(), any()) }

            useCase.run(windowDays = windowDays, onProgress = null)

            coVerifyOrder {
                sessionLinkReconciler.reconcile(
                    startMs = ingestStartMs,
                    endMs = windowEndExclusiveMs - 1,
                    zoneThresholds = any(),
                )
                healthIngestionStore.clearFrozenBaselines(today, today.plusDays(1), zoneId)
                scoringRepository.computeAndPersistDailySummary(today, 0L, any(), any(), any())
            }
        }

    @Test
    fun `sync fetches and upserts all heart-related record types`() =
        runTest {
            coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any()) } coAnswers {
                val callback = it.invocation.args[3] as suspend (List<DomainHeartRateRecord>, String?) -> Unit
                callback(listOf(mockk(relaxed = true)), null)
                app.readylytics.health.core.model.domain.repository.ReadOutcome.Available(Unit)
            }
            coEvery { hcRepo.readHrvSamplesPaged(any(), any(), any(), any()) } coAnswers {
                val callback = it.invocation.args[3] as suspend (List<DomainHrvRecord>, String?) -> Unit
                callback(listOf(mockk(relaxed = true)), null)
                app.readylytics.health.core.model.domain.repository.ReadOutcome.Available(Unit)
            }
            coEvery { hcRepo.readSteps(any(), any()) } returns
                app.readylytics.health.core.model.domain.repository.ReadOutcome.Available(0L)

            useCase.run(windowDays = 8, onProgress = null)

            coVerify {
                hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any())
                hcRepo.readHrvSamplesPaged(any(), any(), any(), any())
                hcRepo.readSteps(any(), any())
                healthIngestionStore.persist(any())
                healthIngestionStore.replaceHeartRateSources(any())
                healthIngestionStore.replaceHrvSources(any())
            }
        }

    @Test
    fun `daily sync windowDays 1 fetches samples from yesterday to cover cross-midnight sleep`() =
        runTest {
            val hrvFromSlot = slot<Instant>()
            val hrFromSlot = slot<Instant>()
            coEvery { hcRepo.readHrvSamplesPaged(capture(hrvFromSlot), any(), any(), any()) } returns
                app.readylytics.health.core.model.domain.repository.ReadOutcome.Available(Unit)
            coEvery { hcRepo.readHeartRateSamplesPaged(capture(hrFromSlot), any(), any(), any()) } returns
                app.readylytics.health.core.model.domain.repository.ReadOutcome.Available(Unit)

            useCase.run(windowDays = 1, onProgress = null)

            // Last night's sleep session begins the previous evening (before midnight); the
            // ingestion fetch must reach back one extra day so its pre-midnight HR/HRV samples
            // are captured. windowDays = 1 => fetch from yesterday 00:00, not today 00:00.
            val zoneId = ZoneId.systemDefault()
            val yesterdayMidnight =
                LocalDate
                    .now(fixedClock.withZone(zoneId))
                    .minusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
            assertEquals(yesterdayMidnight, hrvFromSlot.captured)
            assertEquals(yesterdayMidnight, hrFromSlot.captured)
        }

    @Test
    fun `daily sync ingests today's window before the back-day reach-back window`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(fixedClock.withZone(zoneId))
            val todayMidnight = today.atStartOfDay(zoneId).toInstant()
            val yesterdayMidnight = today.minusDays(1).atStartOfDay(zoneId).toInstant()
            val froms = mutableListOf<Instant>()
            coEvery { hcRepo.readSleepSessions(capture(froms), any()) } returns
                app.readylytics.health.core.model.domain.repository.ReadOutcome.Available(emptyList())

            useCase.run(windowDays = 1, onProgress = null)

            assertEquals(listOf(todayMidnight, yesterdayMidnight), froms)
        }

    @Test
    fun `sync retries today's ingest with an extended budget after a timeout`() =
        runTest {
            var sleepReadCalls = 0
            coEvery { hcRepo.readSleepSessions(any(), any()) } coAnswers {
                if (++sleepReadCalls == 1) {
                    throw HealthConnectWindowTimeoutException(
                        Instant.EPOCH,
                        Instant.EPOCH.plusSeconds(1),
                        RuntimeException("timeout"),
                    )
                }
                app.readylytics.health.core.model.domain.repository.ReadOutcome.Available(emptyList())
            }

            val result = useCase.run(windowDays = 1, onProgress = null)

            assertTrue(result is app.readylytics.health.core.model.domain.model.Result.Success)
            // today attempt + today retry + back-day = 3 sleep reads proves the retry happened.
            assertEquals(3, sleepReadCalls)
        }

    @Test
    fun `sync returns DEFERRED_DAILY_SYNC when today's ingest times out even after retry`() =
        runTest {
            coEvery { hcRepo.readSleepSessions(any(), any()) } throws
                HealthConnectWindowTimeoutException(
                    Instant.EPOCH,
                    Instant.EPOCH.plusSeconds(1),
                    RuntimeException("timeout"),
                )

            val result = useCase.run(windowDays = 1, onProgress = null)

            assertTrue(result is app.readylytics.health.core.model.domain.model.Result.Failure)
            assertEquals(
                "DEFERRED_DAILY_SYNC",
                (result as app.readylytics.health.core.model.domain.model.Result.Failure).code,
            )
            // today's two attempts both timed out; the back-day segment never ran and nothing scored.
            coVerify(exactly = 2) { hcRepo.readSleepSessions(any(), any()) }
            coVerify(exactly = 0) {
                scoringRepository.computeAndPersistDailySummary(any(), any(), any(), any(), any())
            }
            coVerify(exactly = 0) { changeSynchronizer.commitTokens(any()) }
        }

    @Test
    fun `sync continues and scores today when the back-day reach-back times out`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(fixedClock.withZone(zoneId))
            val todayMidnight = today.atStartOfDay(zoneId).toInstant()
            val yesterdayMidnight = today.minusDays(1).atStartOfDay(zoneId).toInstant()
            coEvery { hcRepo.readSleepSessions(any(), any()) } coAnswers {
                if (firstArg<Instant>() == yesterdayMidnight) {
                    throw HealthConnectWindowTimeoutException(
                        yesterdayMidnight,
                        todayMidnight,
                        RuntimeException("timeout"),
                    )
                }
                app.readylytics.health.core.model.domain.repository.ReadOutcome.Available(emptyList())
            }

            val result = useCase.run(windowDays = 1, onProgress = null)

            assertTrue(result is app.readylytics.health.core.model.domain.model.Result.Success)
            coVerify(exactly = 1) {
                scoringRepository.computeAndPersistDailySummary(today, any(), any(), any(), any())
            }
        }

    @Test
    fun `daily sync requests historical resync when delta changes span beyond the sync window`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(fixedClock.withZone(zoneId))
            val oldestAffectedDay = today.minusDays(60)
            val hrFromSlot = slot<Instant>()
            val scoredDays = mutableListOf<LocalDate>()

            coEvery { changeSynchronizer.applyPendingChanges() } returns
                HealthChangeSyncOutcome(
                    affectedDates = setOf(oldestAffectedDay),
                    requiresFullResync = false,
                    nextTokens = mapOf(HealthDataType.SLEEP to "next-sleep-token"),
                )
            coEvery { hcRepo.readHeartRateSamplesPaged(capture(hrFromSlot), any(), any(), any()) } returns
                app.readylytics.health.core.model.domain.repository.ReadOutcome.Available(Unit)
            coJustRun {
                scoringRepository.computeAndPersistDailySummary(
                    capture(scoredDays),
                    any(),
                    any(),
                    any(),
                    any())
            }

            val result = useCase.run(windowDays = 1, onProgress = null)

            assertEquals(today.minusDays(1).atStartOfDay(zoneId).toInstant(), hrFromSlot.captured)
            assertEquals(listOf(today), scoredDays)
            assertTrue(result is app.readylytics.health.core.model.domain.model.Result.Failure)
            assertEquals(
                "REQUIRES_HISTORICAL_RESYNC",
                (result as app.readylytics.health.core.model.domain.model.Result.Failure).code,
            )
            coVerify(exactly = 0) { changeSynchronizer.commitTokens(any()) }
        }

    @Test
    fun `daily sync absorbs recent out-of-window change inline without historical resync`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(fixedClock.withZone(zoneId))
            val yesterday = today.minusDays(1)
            val nextTokens = mapOf(HealthDataType.SLEEP to "next-sleep-token")
            val hrFromSlot = slot<Instant>()
            val scoredDays = mutableListOf<LocalDate>()

            coEvery { changeSynchronizer.applyPendingChanges() } returns
                HealthChangeSyncOutcome(
                    affectedDates = setOf(yesterday),
                    requiresFullResync = false,
                    nextTokens = nextTokens,
                )
            coEvery { hcRepo.readHeartRateSamplesPaged(capture(hrFromSlot), any(), any(), any()) } returns
                app.readylytics.health.core.model.domain.repository.ReadOutcome.Available(Unit)
            coJustRun {
                scoringRepository.computeAndPersistDailySummary(
                    capture(scoredDays),
                    any(),
                    any(),
                    any(),
                    any())
            }

            val result = useCase.run(windowDays = 1, onProgress = null)

            // Walk-forward widens to the affected day and recomputes it through today, contiguously.
            assertEquals(listOf(yesterday, today), scoredDays)
            // Ingestion reaches one extra day back from the widened oldest target day.
            assertEquals(today.minusDays(2).atStartOfDay(zoneId).toInstant(), hrFromSlot.captured)
            assertTrue(result is app.readylytics.health.core.model.domain.model.Result.Success)
            coVerify(exactly = 1) { changeSynchronizer.commitTokens(nextTokens) }
        }

}
