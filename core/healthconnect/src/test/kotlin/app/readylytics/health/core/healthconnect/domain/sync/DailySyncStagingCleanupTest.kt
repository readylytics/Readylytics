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
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.ScanIdentity
import app.readylytics.health.core.model.domain.sync.link.SessionLinkReconciler
import app.readylytics.health.core.scoring.domain.scoring.RasSourceModeBootstrapUseCase
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Task 4 review Finding 1 regression coverage, split out of [DailySyncUseCaseTest] to keep that
 * class under detekt's `LargeClass` threshold.
 */
class DailySyncStagingCleanupTest {
    private val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
    private val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringRepository = mockk<ScoringRepository>(relaxed = true)
    private val sessionLinkReconciler = mockk<SessionLinkReconciler>(relaxed = true)
    private val rasSourceModeBootstrapUseCase = mockk<RasSourceModeBootstrapUseCase>(relaxed = true)
    private val changeSynchronizer = mockk<HealthChangeSynchronizer>(relaxed = true)
    private val transactionRunner = RecordingTransactionRunner()
    private val walDiagnostics = mockk<WalDiagnostics>(relaxed = true)
    private val fixedClock = Clock.fixed(Instant.parse("2024-06-15T12:00:00Z"), ZoneId.of("UTC"))

    @Test
    fun `sync clears stale DAILY_SYNC staging from a prior day`() =
        runTest {
            // The daily flow reuses ScanIdentities.DAILY_RUN_ID every day with a new chunk id, and
            // (unlike a historical resync) never otherwise clears a past chunk's staging -- without
            // a cleanup call, staging would grow one row-set per day, per bulk type, forever.
            // Pre-populate a chunk that looks like leftovers from an earlier day's sync and assert a
            // successful sync purges it.
            coEvery { changeSynchronizer.applyPendingChanges() } returns HealthChangeSyncOutcome(emptySet(), false)
            coJustRun { changeSynchronizer.commitTokens(any()) }
            every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
            coEvery { scoringRepository.fetchWalkForwardFatigueContext(any(), any(), any(), any()) } returns
                WalkForwardFatigueContext(emptyList())
            coEvery { hcRepo.readSleepSessions(any(), any()) } returns ReadOutcome.Available(emptyList())
            coEvery { hcRepo.readExerciseSessions(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
            coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any()) } returns
                ReadOutcome.Available(Unit)
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

            val staging = InMemoryScanStagingStore()
            val staleScan = ScanIdentity(runId = ScanIdentities.DAILY_RUN_ID, chunkId = "stale-prior-day-chunk")
            staging.beginTypeScan(staleScan, HealthDataType.HEART_RATE, resume = false)
            staging.stageIds(staleScan, HealthDataType.HEART_RATE, listOf("stale-hr-id"))
            staging.markTypeScanComplete(staleScan, HealthDataType.HEART_RATE)

            val useCase =
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
                            ingestionCoordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore, staging),
                            stepCountFetcher = StepCountFetcher(hcRepo),
                        ),
                    ioDispatcher = Dispatchers.Unconfined,
                    clock = fixedClock,
                )

            val result = useCase.run(windowDays = 1, onProgress = null)

            assertTrue(result.isSuccess)
            assertNull(staging.stateOf(staleScan, HealthDataType.HEART_RATE))
            assertTrue(staging.stagedIds(staleScan, HealthDataType.HEART_RATE).isEmpty())
        }
}
