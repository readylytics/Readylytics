package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.database.domain.sync.DailyRecomputeSupport
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.ScoringRepository
import app.readylytics.health.core.model.domain.repository.WalDiagnostics
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.sync.*
import app.readylytics.health.core.model.domain.sync.link.SessionLinkReconciler
import app.readylytics.health.core.scoring.domain.scoring.RasSourceModeBootstrapUseCase
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
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

/**
 * Task 5 fix round 1 (Important #1): pins that [DailySyncUseCase] actually wires
 * [ScoreInvalidation.exampleFanOutRange] into its correction-date handling, rather than that
 * function having zero production callers. `ScoreInvalidationTest` already covers the pure
 * function's own math in isolation (round 1); this covers reachability from the real trigger
 * point -- an out-of-window Health Connect change (a corrected/deleted workout among them) --
 * so a future change that deletes or bypasses that wiring shows up here, not silently.
 *
 * Deliberately a separate file from [DailySyncUseCaseTest] (already near this codebase's 800-line
 * file ceiling) rather than growing that one further.
 */
class DailySyncUseCaseExampleFanOutTest {
    private val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
    private val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringRepository = mockk<ScoringRepository>(relaxed = true)
    private val sessionLinkReconciler = mockk<SessionLinkReconciler>(relaxed = true)
    private val rasSourceModeBootstrapUseCase = mockk<RasSourceModeBootstrapUseCase>(relaxed = true)
    private val changeSynchronizer = mockk<HealthChangeSynchronizer>(relaxed = true)
    private val transactionRunner = RecordingTransactionRunner()
    private val walDiagnostics = mockk<WalDiagnostics>(relaxed = true)

    // Fixed rather than Clock.systemDefaultZone() so every "today" computed below is deterministic.
    private val fixedClock = Clock.fixed(Instant.parse("2024-06-15T12:00:00Z"), ZoneId.of("UTC"))

    private lateinit var useCase: DailySyncUseCase

    @Before
    fun setup() {
        coEvery { changeSynchronizer.applyPendingChanges() } returns HealthChangeSyncOutcome(emptySet(), false)
        coJustRun { changeSynchronizer.commitTokens(any()) }
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
        coEvery { scoringRepository.fetchWalkForwardFatigueContext(any(), any(), any(), any()) } returns
            WalkForwardFatigueContext(emptyList())
        coEvery { hcRepo.readSleepSessions(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readExerciseSessions(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readStepsRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readWeightRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBodyFatRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBloodPressureRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readOxygenSaturationRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBodyTemperatureRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readVo2MaxRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any()) } returns ReadOutcome.Available(Unit)
        coEvery { hcRepo.readHrvSamplesPaged(any(), any(), any(), any()) } returns ReadOutcome.Available(Unit)
        coEvery { hcRepo.readSteps(any(), any()) } returns ReadOutcome.Available(0L)
        coEvery { hcRepo.readDailyStepTotals(any(), any(), any()) } returns ReadOutcome.Available(emptyMap())
        coEvery { hcRepo.readHeartRateSamples(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readHrvSamples(any(), any()) } returns ReadOutcome.Available(emptyList())

        useCase =
            DailySyncUseCase(
                settingsRepo = settingsRepo,
                rasSourceModeBootstrapUseCase = rasSourceModeBootstrapUseCase,
                recomputeSupport = DailyRecomputeSupport(scoringRepository, settingsRepo, transactionRunner),
                dirtyRangeStore = FakeDirtyRangeStore(),
                walDiagnostics = walDiagnostics,
                ingestion =
                    DailySyncIngestionCollaborators(
                        sessionLinkReconciler = sessionLinkReconciler,
                        changeSynchronizer = changeSynchronizer,
                        healthIngestionStore = healthIngestionStore,
                        ingestionCoordinator =
                            HealthIngestionCoordinator(
                                hcRepo,
                                healthIngestionStore,
                                FakeScanStagingStore(),
                            ),
                        stepCountFetcher = StepCountFetcher(hcRepo),
                    ),
                ioDispatcher = Dispatchers.Unconfined,
                clock = fixedClock,
            )
    }

    @Test
    fun `an out-of-window correction's inline recompute reaches the example fan-out floor`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(fixedClock.withZone(zoneId))
            // Within MAX_INLINE_RECOMPUTE_DAYS (7): stays inline rather than escalating to the
            // durable historical resync, so this exercises resolveInlineOldestTargetDay directly.
            val correctionDate = today.minusDays(5)
            val nextTokens = mapOf(HealthDataType.SLEEP to "next-sleep-token")
            val scoredDays = mutableListOf<LocalDate>()

            coEvery { changeSynchronizer.applyPendingChanges() } returns
                HealthChangeSyncOutcome(
                    affectedDates = setOf(correctionDate),
                    requiresFullResync = false,
                    nextTokens = nextTokens,
                )
            coJustRun {
                scoringRepository.computeAndPersistDailySummary(capture(scoredDays), any(), any(), any(), any())
            }

            val result = useCase.run(windowDays = 1, onProgress = null)

            // The recompute must reach at least min(correctionDate + EXAMPLE_SELECTION_LOOKBACK_DAYS,
            // today) -- here, today, since the correction is recent -- which is exactly what
            // ScoreInvalidation.exampleFanOutRange(correctionDate, today, retentionStart) demands.
            val requiredThrough =
                minOf(correctionDate.plusDays(ScoreInvalidation.EXAMPLE_SELECTION_LOOKBACK_DAYS), today)
            assertEquals(correctionDate, scoredDays.first())
            assertTrue(
                "recompute must reach $requiredThrough to cover the example fan-out window, " +
                    "only reached ${scoredDays.last()}",
                !scoredDays.last().isBefore(requiredThrough),
            )
            assertTrue(result is app.readylytics.health.core.model.domain.model.Result.Success)
        }
}
