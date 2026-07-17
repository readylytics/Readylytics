package app.readylytics.health.domain.sync

import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.scoring.RasSourceModeBootstrapUseCase
import app.readylytics.health.domain.sync.link.SessionLinkReconciler
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
                recomputeSupport = DailyRecomputeSupport(scoringRepository, settingsRepo),
                ioDispatcher = Dispatchers.Unconfined,
            )
    }

    @Test
    fun `sync processes days in chronological order`() =
        runTest {
            val windowDays = 3
            val today = LocalDate.now(ZoneId.systemDefault())
            val day0 = today.minusDays(2)
            val day1 = today.minusDays(1)
            val day2 = today

            useCase.run(windowDays = windowDays, onProgress = null)

            coVerifyOrder {
                scoringRepository.computeAndPersistDailySummary(day0, 0L, any())
                scoringRepository.computeAndPersistDailySummary(day1, 0L, any())
                scoringRepository.computeAndPersistDailySummary(day2, 0L, any())
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
                scoringRepository.computeAndPersistDailySummary(any(), any(), capture(capturedPrefs))
            } returns Unit

            useCase.run(windowDays = 3, onProgress = null)

            assertEquals(3, capturedPrefs.size)
            assertEquals(1, capturedPrefs.distinct().size)
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
                scoringRepository.computeAndPersistDailySummary(any(), any(), any())
                changeSynchronizer.commitTokens(nextTokens)
            }
        }

    @Test
    fun `sync clears frozen baselines for scoring window before recomputing days`() =
        runTest {
            val windowDays = 2
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(zoneId)

            useCase.run(windowDays = windowDays, onProgress = null)

            coVerifyOrder {
                healthIngestionStore.clearFrozenBaselines(today.minusDays(1), today.plusDays(1), zoneId)
                scoringRepository.computeAndPersistDailySummary(today.minusDays(1), 0L, any())
                scoringRepository.computeAndPersistDailySummary(today, 0L, any())
            }
        }

    @Test
    fun `sync reconciles ingested overlap before scoring days`() =
        runTest {
            val windowDays = 1
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(zoneId)
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
                scoringRepository.computeAndPersistDailySummary(today, 0L, any())
            }
        }

    @Test
    fun `sync fetches and upserts all heart-related record types`() =
        runTest {
            // Mock non-empty returns to ensure mapping logic is triggered
            coEvery { hcRepo.readHeartRateSamples(any(), any()) } returns listOf(mockk(relaxed = true))
            coEvery { hcRepo.readHrvSamples(any(), any()) } returns listOf(mockk(relaxed = true))
            coEvery { hcRepo.readSteps(any(), any()) } returns 0L

            useCase.run(windowDays = 8, onProgress = null)

            coVerify {
                hcRepo.readHeartRateSamples(any(), any())
                hcRepo.readHrvSamples(any(), any())
                hcRepo.readSteps(any(), any())
                healthIngestionStore.persist(any())
            }
        }

    @Test
    fun `daily sync windowDays 1 fetches samples from yesterday to cover cross-midnight sleep`() =
        runTest {
            val hrvFromSlot = slot<Instant>()
            val hrFromSlot = slot<Instant>()
            coEvery { hcRepo.readHrvSamples(capture(hrvFromSlot), any()) } returns emptyList()
            coEvery { hcRepo.readHeartRateSamples(capture(hrFromSlot), any()) } returns emptyList()

            useCase.run(windowDays = 1, onProgress = null)

            // Last night's sleep session begins the previous evening (before midnight); the
            // ingestion fetch must reach back one extra day so its pre-midnight HR/HRV samples
            // are captured. windowDays = 1 => fetch from yesterday 00:00, not today 00:00.
            val zoneId = ZoneId.systemDefault()
            val yesterdayMidnight =
                LocalDate
                    .now(zoneId)
                    .minusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
            assertEquals(yesterdayMidnight, hrvFromSlot.captured)
            assertEquals(yesterdayMidnight, hrFromSlot.captured)
        }

    @Test
    fun `daily sync keeps current-day range and requests historical resync for older changes`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(zoneId)
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
            coEvery { hcRepo.readHeartRateSamples(capture(hrFromSlot), any()) } returns emptyList()
            coJustRun { scoringRepository.computeAndPersistDailySummary(capture(scoredDays), any(), any()) }

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
            val today = LocalDate.now(zoneId)
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
            coEvery { hcRepo.readHeartRateSamples(capture(hrFromSlot), any()) } returns emptyList()
            coJustRun { scoringRepository.computeAndPersistDailySummary(capture(scoredDays), any(), any()) }

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
            val today = LocalDate.now(zoneId)
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
            coJustRun { scoringRepository.computeAndPersistDailySummary(capture(scoredDays), any(), any()) }

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
}
