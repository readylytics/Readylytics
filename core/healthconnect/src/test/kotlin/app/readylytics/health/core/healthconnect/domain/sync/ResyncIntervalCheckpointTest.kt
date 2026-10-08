package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.sync.*
import app.readylytics.health.core.model.domain.util.RetentionBounds
import app.readylytics.health.core.database.domain.sync.DailyRecomputeSupport
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.ScoringRepository
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.model.domain.scoring.TrimpModel
import app.readylytics.health.core.model.domain.sync.link.SessionLinkReconciler
import io.mockk.clearMocks
import io.mockk.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

class ResyncIntervalCheckpointTest {
    private val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
    private val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringRepository = mockk<ScoringRepository>(relaxed = true)
    private val sessionLinkReconciler = mockk<SessionLinkReconciler>(relaxed = true)
    private val changeSynchronizer = mockk<HealthChangeSynchronizer>(relaxed = true)
    private val selectedSourcePruner = mockk<SelectedSourcePruner>(relaxed = true)
    private val checkpointStore = InMemoryResyncCheckpointStore()
    private val baselineTokens = mapOf(HealthDataType.SLEEP to "baseline-sleep-token")
    private val transactionRunner = RecordingTransactionRunner()

    // Required (no default) since the final-review fix: production binds RoomScanStagingStore via
    // Hilt, so tests must name their store explicitly rather than inherit a heap-backed default.
    private val staging = FakeScanStagingStore()

    private lateinit var useCase: ResyncRangeUseCase

    @Before
    fun setup() {
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
        coEvery { changeSynchronizer.applyPendingChanges() } returns HealthChangeSyncOutcome(emptySet(), false)
        coEvery { changeSynchronizer.captureChangesTokens() } returns
            CapturedChangeTokens(baselineTokens)
        coEvery { changeSynchronizer.commitTokens(any(), any()) } returns Unit
        // PERF-002/WP-20/WP-22: every non-empty RECOMPUTE range now fetches batched TRIMP-series and
        // baseline contexts once up front via these methods before calling the 6-arg
        // computeAndPersistDailySummary overload.
        coEvery { scoringRepository.fetchWalkForwardTrimpContext(any(), any(), any()) } returns
            WalkForwardTrimpContext(TreeMap(), TreeMap())
        coEvery { scoringRepository.fetchWalkForwardBaselineContext(any(), any(), any()) } returns
            WalkForwardBaselineContext(emptyList())
        // WP-27: the walk-forward also prefetches one mutable residual-fatigue accumulator per run.
        // A relaxed mock would return null here, and the recompute loop's non-null-context guard
        // would then silently fall back to the 3-arg recomputeDay (no fatigue computed) -- stub it
        // so the walk-forward actually exercises the 6-arg path.
        coEvery { scoringRepository.fetchWalkForwardFatigueContext(any(), any(), any(), any()) } returns
            WalkForwardFatigueContext(emptyList())
        coEvery { hcRepo.readSleepSessions(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readExerciseSessionsWithCompletion(any(), any(), any()) } coAnswers {
            app.readylytics.health.core.model.domain.repository.ExerciseSessionRead(
                hcRepo.readExerciseSessions(firstArg(), secondArg(), true, thirdArg()),
            )
        }

        coEvery { hcRepo.readExerciseSessions(any(), any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any(), any()) } returns
            ReadOutcome.Available(Unit)
        coEvery { hcRepo.readHrvSamplesPaged(any(), any(), any(), any(), any()) } returns ReadOutcome.Available(Unit)
        coEvery { hcRepo.readStepsRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readStepsRecordsPaged(any(), any(), any(), any()) } returns ReadOutcome.Available(Unit)
        coEvery { hcRepo.readSteps(any(), any(), any()) } returns ReadOutcome.Available(0L)
        coEvery { hcRepo.readDailyStepTotals(any(), any(), any(), any()) } returns ReadOutcome.Available(emptyMap())
        coEvery { hcRepo.readWeightRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBodyFatRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBloodPressureRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readOxygenSaturationRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBodyTemperatureRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readVo2MaxRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        setupUseCaseAfterRestart()
    }

    private fun setupUseCaseAfterRestart() {
        useCase =
            ResyncRangeUseCase(
                settingsRepo = settingsRepo,
                clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneId.of("UTC")),
                sessionLinkReconciler = sessionLinkReconciler,
                changeSynchronizer = changeSynchronizer,
                selectedSourcePruner = selectedSourcePruner,
                checkpointStore = checkpointStore,
                healthIngestionStore = healthIngestionStore,
                ingestion =
                    ResyncIngestionDependencies(
                        ingestionCoordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore, staging),
                        stepCountFetcher = StepCountFetcher(hcRepo),
                        staging = staging,
                    ),
                recomputeSupport = DailyRecomputeSupport(scoringRepository, settingsRepo, transactionRunner),
                ioDispatcher = Dispatchers.Unconfined,
            )
    }

    @Test
    fun checkpointedBaselinePromotesAfterRestart() = runTest {
        val start = LocalDate.of(2024, 6, 1)
        val capturedIntervals = mapOf("DISTANCE" to "run-A", "ELEVATION_GAINED" to "elevation-A")
        coEvery { changeSynchronizer.captureChangesTokens() } returns
            CapturedChangeTokens(baselineTokens, capturedIntervals)
        coEvery { hcRepo.readExerciseSessionsWithCompletion(any(), any(), any()) } returns
            app.readylytics.health.core.model.domain.repository.ExerciseSessionRead(
                ReadOutcome.Available(emptyList()), setOf("DISTANCE"),
            )
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            useCase.run(start, start.plusDays(1), 1, { phase, _, _ ->
                if (phase == ResyncPhase.INGEST) throw kotlinx.coroutines.CancellationException("restart")
            })
        }
        val reopenedCheckpoint = requireNotNull(checkpointStore.value)
        assertEquals(capturedIntervals, reopenedCheckpoint.baselineIntervalTokens)
        assertEquals(setOf("DISTANCE"), reopenedCheckpoint.completedIntervalTypes)
        coVerify(exactly = 0) { changeSynchronizer.commitTokens(any(), any()) }
        // A subsequent chunk succeeding for both types cannot undo the first chunk's denial.
        coEvery { hcRepo.readExerciseSessionsWithCompletion(any(), any(), any()) } returns
            app.readylytics.health.core.model.domain.repository.ExerciseSessionRead(
                ReadOutcome.Available(emptyList()), capturedIntervals.keys,
            )
        setupUseCaseAfterRestart()
        val result = useCase.run(start, start.plusDays(1), 1, null)
        assertEquals(true, result.isSuccess)
        coVerify(exactly = 1) { changeSynchronizer.captureChangesTokens() }
        coVerify(exactly = 1) { changeSynchronizer.commitTokens(any(), mapOf("DISTANCE" to "run-A")) }
    }

    @Test
    fun emptyChunkThenPopulatedChunkPromotesCompletedIntervals() = runTest {
        val start = LocalDate.of(2024, 6, 1)
        val intervals = mapOf("DISTANCE" to "baseline-distance", "ELEVATION_GAINED" to "baseline-elevation")
        coEvery { changeSynchronizer.captureChangesTokens() } returns CapturedChangeTokens(baselineTokens, intervals)
        val session = app.readylytics.health.core.model.domain.model.DomainExerciseSessionRecord(
            "workout", Instant.parse("2024-06-02T10:00:00Z"), Instant.parse("2024-06-02T11:00:00Z"), "1", "writer",
        )
        coEvery { hcRepo.readExerciseSessionsWithCompletion(any(), any(), any()) } returnsMany listOf(
            app.readylytics.health.core.model.domain.repository.ExerciseSessionRead(
                ReadOutcome.Available(emptyList()), intervals.keys,
            ),
            app.readylytics.health.core.model.domain.repository.ExerciseSessionRead(
                ReadOutcome.Available(listOf(session)), intervals.keys,
            ),
        )
        val result = useCase.run(start, start.plusDays(1), 1, null)
        assertEquals(true, result.isSuccess)
        coVerify(exactly = 1) { changeSynchronizer.commitTokens(any(), intervals) }
    }
}
