package app.readylytics.health.workers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.WorkerParameters
import app.readylytics.health.core.healthconnect.domain.sync.ForegroundSyncController
import app.readylytics.health.core.healthconnect.domain.sync.FullHistoricalResyncUseCase
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.migration.DatabaseReadinessInspector
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
import app.readylytics.health.core.model.domain.util.RetentionBounds
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class HealthResyncWorkerScoringVersionTest {
    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private val useCase = mockk<FullHistoricalResyncUseCase>()
    private val useCaseLazy = mockk<Lazy<FullHistoricalResyncUseCase>>()
    private val databaseReadinessGate = mockk<DatabaseReadinessInspector>()
    private val foregroundSyncController = mockk<ForegroundSyncController>(relaxed = true)
    private val foregroundSyncControllerLazy = mockk<Lazy<ForegroundSyncController>>()
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val settingsRepositoryLazy = mockk<Lazy<SettingsRepository>>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workerParams = mockk(relaxed = true)
        every { workerParams.taskExecutor } returns mockk(relaxed = true)
        every { workerParams.inputData } returns androidx.work.Data.EMPTY
        every { useCaseLazy.get() } returns useCase
        every { foregroundSyncControllerLazy.get() } returns foregroundSyncController
        every { databaseReadinessGate.inspect() } returns DatabaseReadiness.Ready
        every { settingsRepositoryLazy.get() } returns settingsRepository

        val progressUpdater = mockk<androidx.work.ProgressUpdater>()
        every { workerParams.progressUpdater } returns progressUpdater
        every { progressUpdater.updateProgress(any(), any(), any()) } returns
            com.google.common.util.concurrent.Futures
                .immediateFuture(null)

        val foregroundUpdater = mockk<androidx.work.ForegroundUpdater>()
        every { workerParams.foregroundUpdater } returns foregroundUpdater
        every { foregroundUpdater.setForegroundAsync(any(), any(), any()) } returns
            com.google.common.util.concurrent.Futures
                .immediateFuture(null)
    }

    @Test
    fun `a stale scoringVersion after a successful resync bumps to CURRENT_SCORING_VERSION exactly once`() =
        runTest {
            // Arrange: prefs.scoringVersion = 3 (stale relative to CURRENT_SCORING_VERSION = 4)
            coEvery { settingsRepository.userPreferences } returns
                MutableStateFlow(UserPreferences(scoringVersion = 3))
            coEvery { useCase.execute(any(), any(), any(), any()) } returns Result.Success(Unit)

            // Act
            val result = createWorker().doWork()

            // Assert
            assertEquals(
                androidx.work.ListenableWorker.Result
                    .success(),
                result,
            )
            coVerify(exactly = 1) {
                settingsRepository.updateScoringVersion(SettingsDefaults.CURRENT_SCORING_VERSION)
            }
        }

    @Test
    fun `an up to date scoringVersion skips version bump on successful resync`() =
        runTest {
            // Arrange: prefs.scoringVersion = CURRENT_SCORING_VERSION = 3
            coEvery { settingsRepository.userPreferences } returns
                MutableStateFlow(UserPreferences(scoringVersion = SettingsDefaults.CURRENT_SCORING_VERSION))
            coEvery { useCase.execute(any(), any(), any(), any()) } returns Result.Success(Unit)

            // Act
            val result = createWorker().doWork()

            // Assert
            assertEquals(
                androidx.work.ListenableWorker.Result
                    .success(),
                result,
            )
            coVerify(exactly = 0) { settingsRepository.updateScoringVersion(any()) }
        }

    @Test
    fun `failed resync does not bump stale scoringVersion`() =
        runTest {
            // Arrange: prefs.scoringVersion = 2
            coEvery { settingsRepository.userPreferences } returns
                MutableStateFlow(UserPreferences(scoringVersion = 2))
            coEvery { useCase.execute(any(), any(), any(), any()) } returns Result.Failure("error", "resync failed")

            // Act
            val result = createWorker().doWork()

            // Assert
            assertEquals(
                androidx.work.ListenableWorker.Result
                    .retry(),
                result,
            )
            coVerify(exactly = 0) { settingsRepository.updateScoringVersion(any()) }
        }

    @Test
    fun `a bounded recompute-only pass does not advance a stale scoringVersion even on success`() =
        runTest {
            // A narrow historical range far short of the full retention window -- e.g. a settings
            // change, DataCleanupWorker's retention fan-out, or a correction's example fan-out.
            every { workerParams.inputData } returns
                Data
                    .Builder()
                    .putBoolean(HealthResyncWorker.KEY_RECOMPUTE_ONLY, true)
                    .putLong(
                        HealthResyncWorker.KEY_RECOMPUTE_START_EPOCH_DAY,
                        LocalDate.of(2026, 1, 1).toEpochDay(),
                    ).putLong(
                        HealthResyncWorker.KEY_RECOMPUTE_END_EPOCH_DAY,
                        LocalDate.of(2026, 1, 10).toEpochDay(),
                    ).build()
            coEvery { settingsRepository.userPreferences } returns
                MutableStateFlow(UserPreferences(scoringVersion = 3))
            coEvery { useCase.execute(any(), any(), any(), any()) } returns Result.Success(Unit)

            val result = createWorker().doWork()

            assertEquals(
                androidx.work.ListenableWorker.Result
                    .success(),
                result,
            )
            coVerify(exactly = 0) { settingsRepository.updateScoringVersion(any()) }
            // Progress plumbing is unaffected by the version gate -- still reaches the controller.
            verify(exactly = 1) { foregroundSyncController.onBackgroundRecalcStarted() }
            verify(exactly = 1) { foregroundSyncController.onBackgroundRecalcFinished(true) }
        }

    @Test
    fun `a recompute-only pass without a range override advances a stale scoringVersion on success`() =
        runTest {
            // No KEY_RECOMPUTE_START/END_EPOCH_DAY: an unbounded recompute-only pass, which -- like
            // a full resync -- always covers the entire retention window through today.
            every { workerParams.inputData } returns
                Data.Builder().putBoolean(HealthResyncWorker.KEY_RECOMPUTE_ONLY, true).build()
            coEvery { settingsRepository.userPreferences } returns
                MutableStateFlow(UserPreferences(scoringVersion = 3))
            coEvery { useCase.execute(any(), any(), any(), any()) } returns Result.Success(Unit)

            val result = createWorker().doWork()

            assertEquals(
                androidx.work.ListenableWorker.Result
                    .success(),
                result,
            )
            coVerify(exactly = 1) {
                settingsRepository.updateScoringVersion(SettingsDefaults.CURRENT_SCORING_VERSION)
            }
        }

    @Test
    fun `a bounded recompute-only pass spanning the full retention window still advances`() =
        runTest {
            val prefs = UserPreferences(scoringVersion = 3)
            val today = LocalDate.now()
            val retentionStart = RetentionBounds.resolveResyncStartDate(prefs, today)
            every { workerParams.inputData } returns
                Data
                    .Builder()
                    .putBoolean(HealthResyncWorker.KEY_RECOMPUTE_ONLY, true)
                    .putLong(HealthResyncWorker.KEY_RECOMPUTE_START_EPOCH_DAY, retentionStart.toEpochDay())
                    .putLong(HealthResyncWorker.KEY_RECOMPUTE_END_EPOCH_DAY, today.toEpochDay())
                    .build()
            coEvery { settingsRepository.userPreferences } returns MutableStateFlow(prefs)
            coEvery { useCase.execute(any(), any(), any(), any()) } returns Result.Success(Unit)

            val result = createWorker().doWork()

            assertEquals(
                androidx.work.ListenableWorker.Result
                    .success(),
                result,
            )
            coVerify(exactly = 1) {
                settingsRepository.updateScoringVersion(SettingsDefaults.CURRENT_SCORING_VERSION)
            }
        }

    @Test
    fun `a killed bounded pass leaves the stale version in place for a later retry to advance`() =
        runTest {
            // Simulates "failure/resume": first a bounded pass that fails (e.g. transient IO) --
            // version must stay stale -- then a later, unbounded retry that succeeds and advances it.
            every { workerParams.inputData } returns
                Data
                    .Builder()
                    .putBoolean(HealthResyncWorker.KEY_RECOMPUTE_ONLY, true)
                    .putLong(
                        HealthResyncWorker.KEY_RECOMPUTE_START_EPOCH_DAY,
                        LocalDate.of(2026, 1, 1).toEpochDay(),
                    ).putLong(
                        HealthResyncWorker.KEY_RECOMPUTE_END_EPOCH_DAY,
                        LocalDate.of(2026, 1, 10).toEpochDay(),
                    ).build()
            coEvery { settingsRepository.userPreferences } returns
                MutableStateFlow(UserPreferences(scoringVersion = 3))
            coEvery { useCase.execute(any(), any(), any(), any()) } returns Result.Failure("error", "resync failed")

            val failedResult = createWorker().doWork()

            assertEquals(
                androidx.work.ListenableWorker.Result
                    .retry(),
                failedResult,
            )
            coVerify(exactly = 0) { settingsRepository.updateScoringVersion(any()) }

            // Retry: a fresh worker instance, unbounded recompute-only, now succeeds.
            every { workerParams.inputData } returns
                Data.Builder().putBoolean(HealthResyncWorker.KEY_RECOMPUTE_ONLY, true).build()
            coEvery { useCase.execute(any(), any(), any(), any()) } returns Result.Success(Unit)

            val retriedResult = createWorker().doWork()

            assertEquals(
                androidx.work.ListenableWorker.Result
                    .success(),
                retriedResult,
            )
            coVerify(exactly = 1) {
                settingsRepository.updateScoringVersion(SettingsDefaults.CURRENT_SCORING_VERSION)
            }
        }

    @Test
    fun `successful recompute initializes absent applied training readiness pair from scoring defaults`() =
        runTest {
            coEvery { settingsRepository.userPreferences } returns
                MutableStateFlow(
                    UserPreferences(
                        trainingReadinessResidualFatigueScale = 150f,
                        trainingReadinessLoadBalanceWeight = 0.82f,
                    ),
                )
            coEvery { useCase.execute(any(), any(), any(), any()) } returns Result.Success(Unit)

            createWorker().doWork()

            coVerify(exactly = 1) {
                settingsRepository.updateTrainingReadinessConfig(
                    TrainingReadinessConfig.fromStored(
                        SettingsDefaults.TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE,
                        SettingsDefaults.TRAINING_READINESS_LOAD_BALANCE_WEIGHT,
                    ),
                )
            }
        }

    @Test
    fun `successful recompute never overwrites existing applied pair with pending editable values`() =
        runTest {
            val applied = TrainingReadinessConfig.fromStored(scale = 125f, weight = 0.85f)
            coEvery { settingsRepository.userPreferences } returns
                MutableStateFlow(
                    UserPreferences(
                        trainingReadinessResidualFatigueScale = 150f,
                        trainingReadinessLoadBalanceWeight = 0.82f,
                        lastAppliedTrainingReadinessResidualFatigueScale = applied.residualFatigueScale,
                        lastAppliedTrainingReadinessLoadBalanceWeight = applied.loadBalanceWeight,
                    ),
                )
            coEvery { useCase.execute(any(), any(), any(), any()) } returns Result.Success(Unit)

            createWorker().doWork()

            coVerify(exactly = 0) { settingsRepository.updateTrainingReadinessConfig(any()) }
        }

    private fun createWorker() =
        HealthResyncWorker(
            appContext = context,
            params = workerParams,
            fullHistoricalResyncUseCase = useCaseLazy,
            foregroundSyncController = foregroundSyncControllerLazy,
            databaseReadinessGate = databaseReadinessGate,
            settingsRepository = settingsRepositoryLazy,
        )
}
