package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.sync.*
import app.readylytics.health.core.database.domain.sync.DailyRecomputeSupport
import app.readylytics.health.domain.model.DomainHeartRateRecord
import app.readylytics.health.domain.model.DomainHrvRecord
import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.HealthConnectWindowTimeoutException
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.repository.WalDiagnostics
import app.readylytics.health.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.domain.repository.WalkForwardTrimpContext
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

/**
 * Behavioral characterization of the foreground daily-sync flow. The collaborators that actually
 * touch Health Connect / Room / scoring ([HealthIngestionCoordinator], [StepCountFetcher],
 * [DailyRecomputeSupport]) are constructed for real over the same low-level mocks the god-class
 * test used, so the call-order/idempotency assertions are preserved across the M1 extraction.
 */
class DailySyncUseCaseTest {
    private val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
    private val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringRepository = mockk<ScoringRepository>(relaxed = true)
    private val sessionLinkReconciler = mockk<SessionLinkReconciler>(relaxed = true)
    private val rasSourceModeBootstrapUseCase = mockk<RasSourceModeBootstrapUseCase>(relaxed = true)
    private val changeSynchronizer = mockk<HealthChangeSynchronizer>(relaxed = true)
    private val transactionRunner = RecordingTransactionRunner()
    private val walDiagnostics = mockk<WalDiagnostics>(relaxed = true)

    // Fixed rather than Clock.systemDefaultZone() so every "today" computed below is deterministic
    // (DI-002): production resolves "today" via clock.withZone(zoneId), so this must be the same
    // clock instance the tests build their expected dates from.
    private val fixedClock = Clock.fixed(Instant.parse("2024-06-15T12:00:00Z"), ZoneId.of("UTC"))

    private lateinit var useCase: DailySyncUseCase

    @Before
    fun setup() {
        coEvery { changeSynchronizer.applyPendingChanges() } returns HealthChangeSyncOutcome(emptySet(), false)
        coJustRun { changeSynchronizer.commitTokens(any()) }
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())

        useCase =
            DailySyncUseCase(
                settingsRepo = settingsRepo,
                sessionLinkReconciler = sessionLinkReconciler,
                rasSourceModeBootstrapUseCase = rasSourceModeBootstrapUseCase,
                changeSynchronizer = changeSynchronizer,
                healthIngestionStore = healthIngestionStore,
                ingestionCoordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore),
                stepCountFetcher = StepCountFetcher(hcRepo),
                recomputeSupport = DailyRecomputeSupport(scoringRepository, settingsRepo, transactionRunner),
                walDiagnostics = walDiagnostics,
                ioDispatcher = Dispatchers.Unconfined,
                clock = fixedClock,
            )
    }

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
                    any(),
                )
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
            val capturedTrimp = mutableListOf<WalkForwardTrimpContext>()
            val capturedBaseline = mutableListOf<WalkForwardBaselineContext>()
            coEvery {
                scoringRepository.computeAndPersistDailySummary(
                    any(),
                    any(),
                    any(),
                    capture(capturedTrimp),
                    capture(capturedBaseline),
                )
            } returns Unit

            useCase.run(windowDays = 3, onProgress = null)

            assertEquals(3, capturedTrimp.size)
            assertEquals(1, capturedTrimp.distinctBy { System.identityHashCode(it) }.size)
            assertEquals(3, capturedBaseline.size)
            assertEquals(1, capturedBaseline.distinctBy { System.identityHashCode(it) }.size)
            coVerify(exactly = 1) {
                scoringRepository.fetchWalkForwardTrimpContext(any(), any(), any())
            }
            coVerify(exactly = 1) {
                scoringRepository.fetchWalkForwardBaselineContext(any(), any(), any())
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
            // HR/HRV are streamed page-by-page (HC-001); drive one non-empty page through each
            // callback to ensure the per-page mapping/persist logic is triggered.
            coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any()) } coAnswers {
                thirdArg<suspend (List<DomainHeartRateRecord>) -> Unit>().invoke(listOf(mockk(relaxed = true)))
            }
            coEvery { hcRepo.readHrvSamplesPaged(any(), any(), any()) } coAnswers {
                thirdArg<suspend (List<DomainHrvRecord>) -> Unit>().invoke(listOf(mockk(relaxed = true)))
            }
            coEvery { hcRepo.readSteps(any(), any()) } returns 0L

            useCase.run(windowDays = 8, onProgress = null)

            coVerify {
                hcRepo.readHeartRateSamplesPaged(any(), any(), any())
                hcRepo.readHrvSamplesPaged(any(), any(), any())
                hcRepo.readSteps(any(), any())
                healthIngestionStore.persist(any())
                healthIngestionStore.persistHeartRateSamples(any())
                healthIngestionStore.persistHrvSamples(any())
            }
        }

    @Test
    fun `daily sync windowDays 1 fetches samples from yesterday to cover cross-midnight sleep`() =
        runTest {
            val hrvFromSlot = slot<Instant>()
            val hrFromSlot = slot<Instant>()
            coJustRun { hcRepo.readHrvSamplesPaged(capture(hrvFromSlot), any(), any()) }
            coJustRun { hcRepo.readHeartRateSamplesPaged(capture(hrFromSlot), any(), any()) }

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
            coEvery { hcRepo.readSleepSessions(capture(froms), any()) } returns emptyList()

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
                emptyList()
            }

            val result = useCase.run(windowDays = 1, onProgress = null)

            assertTrue(result is app.readylytics.health.domain.model.Result.Success)
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

            assertTrue(result is app.readylytics.health.domain.model.Result.Failure)
            assertEquals(
                "DEFERRED_DAILY_SYNC",
                (result as app.readylytics.health.domain.model.Result.Failure).code,
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
                emptyList()
            }

            val result = useCase.run(windowDays = 1, onProgress = null)

            assertTrue(result is app.readylytics.health.domain.model.Result.Success)
            coVerify(exactly = 1) {
                scoringRepository.computeAndPersistDailySummary(today, any(), any(), any(), any())
            }
        }

    @Test
    fun `daily sync keeps current-day range and requests historical resync for older changes`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(fixedClock.withZone(zoneId))
            // Beyond the inline-recompute floor: must escalate to the durable historical resync
            // rather than being absorbed by the foreground walk-forward.
            val oldestAffectedDay = today.minusDays(8)
            val hrFromSlot = slot<Instant>()
            val scoredDays = mutableListOf<LocalDate>()

            coEvery { changeSynchronizer.applyPendingChanges() } returns
                HealthChangeSyncOutcome(
                    affectedDates = setOf(oldestAffectedDay),
                    requiresFullResync = false,
                    nextTokens = mapOf(HealthDataType.SLEEP to "next-sleep-token"),
                )
            coJustRun { hcRepo.readHeartRateSamplesPaged(capture(hrFromSlot), any(), any()) }
            coJustRun {
                scoringRepository.computeAndPersistDailySummary(
                    capture(scoredDays),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }

            val result = useCase.run(windowDays = 1, onProgress = null)

            assertEquals(today.minusDays(1).atStartOfDay(zoneId).toInstant(), hrFromSlot.captured)
            assertEquals(listOf(today), scoredDays)
            assertTrue(result is app.readylytics.health.domain.model.Result.Failure)
            assertEquals(
                "REQUIRES_HISTORICAL_RESYNC",
                (result as app.readylytics.health.domain.model.Result.Failure).code,
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
            coJustRun { hcRepo.readHeartRateSamplesPaged(capture(hrFromSlot), any(), any()) }
            coJustRun {
                scoringRepository.computeAndPersistDailySummary(
                    capture(scoredDays),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }

            val result = useCase.run(windowDays = 1, onProgress = null)

            // Walk-forward widens to the affected day and recomputes it through today, contiguously.
            assertEquals(listOf(yesterday, today), scoredDays)
            // Ingestion reaches one extra day back from the widened oldest target day.
            assertEquals(today.minusDays(2).atStartOfDay(zoneId).toInstant(), hrFromSlot.captured)
            assertTrue(result is app.readylytics.health.domain.model.Result.Success)
            coVerify(exactly = 1) { changeSynchronizer.commitTokens(nextTokens) }
        }

    @Test
    fun `daily sync absorbs change exactly at the inline floor inline`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(fixedClock.withZone(zoneId))
            // Exactly MAX_INLINE_RECOMPUTE_DAYS (7) back: the floor is inclusive, so still inline.
            val floorDay = today.minusDays(7)
            val nextTokens = mapOf(HealthDataType.SLEEP to "next-sleep-token")
            val scoredDays = mutableListOf<LocalDate>()

            coEvery { changeSynchronizer.applyPendingChanges() } returns
                HealthChangeSyncOutcome(
                    affectedDates = setOf(floorDay),
                    requiresFullResync = false,
                    nextTokens = nextTokens,
                )
            coJustRun {
                scoringRepository.computeAndPersistDailySummary(
                    capture(scoredDays),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }

            val result = useCase.run(windowDays = 1, onProgress = null)

            assertEquals(floorDay, scoredDays.first())
            assertEquals(today, scoredDays.last())
            assertTrue(result is app.readylytics.health.domain.model.Result.Success)
            coVerify(exactly = 1) { changeSynchronizer.commitTokens(nextTokens) }
        }

    @Test
    fun `sync rethrows cancellation instead of converting to failure`() =
        runTest {
            coEvery { hcRepo.readSleepSessions(any(), any()) } throws CancellationException("cancelled")

            assertFailsWith<CancellationException> {
                useCase.run(windowDays = 1, onProgress = null)
            }
        }

    @Test
    fun `sync rethrows permission-revoked instead of flattening to SYNC_ERROR`() =
        runTest {
            // HC-008: a revoked Health Connect permission must surface distinctly so
            // ForegroundSyncController/the periodic worker can route to the permission-recovery
            // flow, not be swallowed into a generic Result.Failure("SYNC_ERROR").
            coEvery { hcRepo.readSleepSessions(any(), any()) } throws
                app.readylytics.health.domain.repository.HealthConnectPermissionRevokedException(
                    SecurityException("revoked"),
                )

            assertFailsWith<app.readylytics.health.domain.repository.HealthConnectPermissionRevokedException> {
                useCase.run(windowDays = 1, onProgress = null)
            }
        }

    @Test
    fun `sync resolves today from the injected clock, not the real system clock`() =
        runTest {
            // DI-002: a use case wired to a clock fixed on a historical date must resolve "today"
            // from that clock, never from the machine's real wall-clock date. A historical instant
            // (2019, long past) makes the assertion exact and immune to coincidental matches with
            // whatever day this test actually runs on.
            val historicalClock = Clock.fixed(Instant.parse("2019-01-10T08:00:00Z"), ZoneId.of("UTC"))
            val clockedUseCase =
                DailySyncUseCase(
                    settingsRepo = settingsRepo,
                    sessionLinkReconciler = sessionLinkReconciler,
                    rasSourceModeBootstrapUseCase = rasSourceModeBootstrapUseCase,
                    changeSynchronizer = changeSynchronizer,
                    healthIngestionStore = healthIngestionStore,
                    ingestionCoordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore),
                    stepCountFetcher = StepCountFetcher(hcRepo),
                    recomputeSupport = DailyRecomputeSupport(scoringRepository, settingsRepo, transactionRunner),
                    walDiagnostics = walDiagnostics,
                    ioDispatcher = Dispatchers.Unconfined,
                    clock = historicalClock,
                )
            every { settingsRepo.userPreferences } returns flowOf(UserPreferences(scoringZoneId = "UTC"))
            val expectedDay = LocalDate.of(2019, 1, 10)

            clockedUseCase.run(windowDays = 1, onProgress = null)

            coVerify {
                scoringRepository.computeAndPersistDailySummary(expectedDay, any(), any(), any(), any())
            }
            coVerify(exactly = 0) {
                scoringRepository.computeAndPersistDailySummary(
                    LocalDate.now(ZoneId.of("UTC")),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun `sync recomputes the whole window inside exactly one transaction`() =
        runTest {
            // F7: Room invalidates per table per transaction. One transaction for the whole
            // walk-forward means every observed daily_summaries/workout_records query in the UI
            // re-runs once per sync instead of once per synced day.
            useCase.run(windowDays = 8, onProgress = null)

            assertEquals(1, transactionRunner.transactionCount)
            assertEquals(1, transactionRunner.maxDepth)
        }

    @Test
    fun `sync clears frozen baselines and scores every day inside the transaction`() =
        runTest {
            // The frozen-baseline clear is a daily_summaries write too; leaving it outside would
            // cost a second invalidation round per sync.
            val insideTransaction = mutableListOf<String>()
            coEvery { healthIngestionStore.clearFrozenBaselines(any(), any(), any()) } answers {
                insideTransaction += "clear:${transactionRunner.openDepth}"
            }
            coEvery {
                scoringRepository.computeAndPersistDailySummary(any(), any(), any(), any(), any())
            } answers {
                insideTransaction += "score:${transactionRunner.openDepth}"
            }

            useCase.run(windowDays = 3, onProgress = null)

            assertEquals(
                listOf("clear:1", "score:1", "score:1", "score:1"),
                insideTransaction,
            )
        }

    @Test
    fun `sync emits an indeterminate RECONCILE progress signal before reconcile runs`() =
        runTest {
            // US-003: onProgress must fire (RECONCILE, 0, 0) before sessionLinkReconciler.reconcile
            // is invoked, so the UI banner switches to the RECONCILE label before that phase starts.
            val events = mutableListOf<String>()
            val onProgress: (ResyncPhase, Int, Int) -> Unit = { phase, current, total ->
                if (phase == ResyncPhase.RECONCILE) events += "progress:RECONCILE:$current:$total"
            }
            coEvery { sessionLinkReconciler.reconcile(any(), any(), any()) } answers {
                events += "reconcile:called"
            }

            useCase.run(windowDays = 1, onProgress = onProgress)

            assertEquals(listOf("progress:RECONCILE:0:0", "reconcile:called"), events)
        }

    @Test
    fun `sync emits incrementing indeterminate INGEST progress signals per streamed page`() =
        runTest {
            // US-004: each HR/HRV page persisted during ingestWindow must report an indeterminate
            // (total = 0) INGEST signal with a monotonically incrementing page count. M4: the first
            // page carries 2 records and the second carries 1 -- if the counter incremented per
            // record instead of per page, this would report (…, 2, 0) then (…, 3, 0) instead of the
            // expected (…, 1, 0) then (…, 2, 0), so this distinguishes the two implementations.
            val progressEvents = mutableListOf<Triple<ResyncPhase, Int, Int>>()
            val onProgress: (ResyncPhase, Int, Int) -> Unit = { phase, current, total ->
                progressEvents += Triple(phase, current, total)
            }
            coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any()) } coAnswers {
                val callback = thirdArg<suspend (List<DomainHeartRateRecord>) -> Unit>()
                callback(listOf(mockk(relaxed = true), mockk(relaxed = true)))
                callback(listOf(mockk(relaxed = true)))
            }

            useCase.run(windowDays = 1, onProgress = onProgress)

            val ingestEvents = progressEvents.filter { it.first == ResyncPhase.INGEST }
            // B′: the page counter is local to each ingestWindow call, so it resets to 1 when the
            // back-day segment starts. Both segments emit (1,0) then (2,0).
            assertEquals(
                listOf(
                    Triple(ResyncPhase.INGEST, 1, 0),
                    Triple(ResyncPhase.INGEST, 2, 0),
                    Triple(ResyncPhase.INGEST, 1, 0),
                    Triple(ResyncPhase.INGEST, 2, 0),
                ),
                ingestEvents,
            )
        }

    @Test
    fun `sync opens no transaction around the Health Connect window read`() =
        runTest {
            // Holding a write transaction across HC IPC would pin the transaction thread for the
            // duration of a remote read. Ingestion, reconcile and the step fetch must all be done
            // before the transaction opens.
            var depthDuringHcRead = -1
            coEvery { hcRepo.readSteps(any(), any()) } answers {
                depthDuringHcRead = transactionRunner.openDepth
                0L
            }

            useCase.run(windowDays = 2, onProgress = null)

            assertEquals(0, depthDuringHcRead)
        }
}
