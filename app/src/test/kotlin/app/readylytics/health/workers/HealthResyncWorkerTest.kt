package app.readylytics.health.workers

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.readylytics.health.core.healthconnect.domain.sync.ForegroundSyncController
import app.readylytics.health.core.healthconnect.domain.sync.FullHistoricalResyncUseCase
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.migration.DatabaseReadinessInspector
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.core.model.domain.scoring.SleepScoreWeightProfile
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import dagger.Lazy
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class HealthResyncWorkerTest {
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
        coEvery { settingsRepository.userPreferences } returns
            MutableStateFlow(UserPreferences(scoringVersion = 0))

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
    fun `getForegroundInfo uses resync notification id and data sync service type`() =
        runBlocking {
            val worker = createWorker()
            val foregroundInfo: ForegroundInfo = worker.getForegroundInfo()

            assertEquals(SyncNotifications.NOTIFICATION_ID, foregroundInfo.notificationId)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, foregroundInfo.foregroundServiceType)
            assertTrue(foregroundInfo.notification.channelId == SyncNotifications.CHANNEL_ID)
        }

    @Test
    fun `worker progress keys stay stable`() {
        assertEquals("current", HealthResyncWorker.KEY_CURRENT)
        assertEquals("total", HealthResyncWorker.KEY_TOTAL)
    }

    @Test
    fun `doWork reports progress and returns success when resync usecase succeeds`() =
        runBlocking {
            coEvery { useCase.execute(any(), any(), any()) } answers {
                val progressCallback = thirdArg<(ResyncPhase, Int, Int) -> Unit>()
                progressCallback(ResyncPhase.RECOMPUTE, 1, 10)
                app.readylytics.health.core.model.domain.model.Result
                    .Success(Unit)
            }
            val worker = createWorker()
            val result = worker.doWork()
            assertEquals(
                androidx.work.ListenableWorker.Result
                    .success(),
                result,
            )
            verify(exactly = 1) { foregroundSyncControllerLazy.get() }
            verify(exactly = 1) {
                foregroundSyncController.onBackgroundRecalcProgress(ResyncPhase.RECOMPUTE, 1, 10)
            }
        }

    @Test
    fun `doWork passes recomputeOnly from input data through to the use case`() =
        runBlocking {
            every { workerParams.inputData } returns
                androidx.work.Data
                    .Builder()
                    .putBoolean(HealthResyncWorker.KEY_RECOMPUTE_ONLY, true)
                    .build()
            val recomputeOnlySlot = slot<Boolean>()
            coEvery { useCase.execute(capture(recomputeOnlySlot), any(), any()) } returns
                app.readylytics.health.core.model.domain.model.Result
                    .Success(Unit)

            val worker = createWorker()
            worker.doWork()

            assertTrue(recomputeOnlySlot.captured)
        }

    @Test
    fun `doWork defaults recomputeOnly to false when input data is absent`() =
        runBlocking {
            val recomputeOnlySlot = slot<Boolean>()
            coEvery { useCase.execute(capture(recomputeOnlySlot), any(), any()) } returns
                app.readylytics.health.core.model.domain.model.Result
                    .Success(Unit)

            val worker = createWorker()
            worker.doWork()

            assertTrue(!recomputeOnlySlot.captured)
        }

    @Test
    fun `doWork builds a range override from the epoch-day input data keys`() =
        runBlocking {
            every { workerParams.inputData } returns
                androidx.work.Data
                    .Builder()
                    .putBoolean(HealthResyncWorker.KEY_RECOMPUTE_ONLY, true)
                    .putLong(
                        HealthResyncWorker.KEY_RECOMPUTE_START_EPOCH_DAY,
                        java.time.LocalDate
                            .of(2026, 1, 1)
                            .toEpochDay(),
                    ).putLong(
                        HealthResyncWorker.KEY_RECOMPUTE_END_EPOCH_DAY,
                        java.time.LocalDate
                            .of(2026, 1, 31)
                            .toEpochDay(),
                    ).build()
            val rangeSlot = slot<app.readylytics.health.core.model.domain.sync.ScoreInvalidation.AffectedRange>()
            coEvery { useCase.execute(any(), capture(rangeSlot), any()) } returns
                app.readylytics.health.core.model.domain.model.Result
                    .Success(Unit)

            val worker = createWorker()
            worker.doWork()

            assertEquals(java.time.LocalDate.of(2026, 1, 1), rangeSlot.captured.start)
            assertEquals(java.time.LocalDate.of(2026, 1, 31), rangeSlot.captured.endInclusive)
        }

    @Test
    fun `doWork passes a null range override when the epoch-day keys are absent`() =
        runBlocking {
            val rangeSlot =
                slot<app.readylytics.health.core.model.domain.sync.ScoreInvalidation.AffectedRange?>()
            coEvery { useCase.execute(any(), captureNullable(rangeSlot), any()) } returns
                app.readylytics.health.core.model.domain.model.Result
                    .Success(Unit)

            val worker = createWorker()
            worker.doWork()

            assertEquals(null, rangeSlot.captured)
        }

    @Test
    fun `doWork returns retry when resync usecase fails`() =
        runBlocking {
            coEvery { useCase.execute(any(), any(), any()) } returns
                app.readylytics.health.core.model.domain.model.Result
                    .Failure("error", "network error")
            val worker = createWorker()
            val result = worker.doWork()
            assertEquals(
                androidx.work.ListenableWorker.Result
                    .retry(),
                result,
            )
        }

    @Test
    fun `doWork returns retry when resync usecase throws exception`() =
        runBlocking {
            coEvery { useCase.execute(any(), any(), any()) } throws RuntimeException("critical error")
            val worker = createWorker()
            val result = worker.doWork()
            assertEquals(
                androidx.work.ListenableWorker.Result
                    .retry(),
                result,
            )
        }

    @Test
    fun `doWork returns terminal failure when Health Connect permission is revoked`() =
        runBlocking {
            coEvery { useCase.execute(any(), any(), any()) } throws
                HealthConnectPermissionRevokedException(SecurityException("permission revoked"))
            val worker = createWorker()

            val result = worker.doWork()

            assertEquals(
                androidx.work.ListenableWorker.Result
                    .failure(),
                result,
            )
        }

    @Test
    fun `doWork retries without resolving Room dependency when database is not ready`() =
        runBlocking {
            every { databaseReadinessGate.inspect() } returns DatabaseReadiness.MigrationRequired(6)

            val result = createWorker().doWork()

            assertEquals(
                androidx.work.ListenableWorker.Result
                    .retry(),
                result,
            )
            verify(exactly = 0) { useCaseLazy.get() }
            verify(exactly = 0) { foregroundSyncControllerLazy.get() }
        }

    @Test
    fun `success bumps scoring version and marks the sleep-score recalc baseline`() =
        runBlocking {
            coEvery { useCase.execute(any(), any(), any()) } returns
                app.readylytics.health.core.model.domain.model.Result
                    .Success(Unit)
            createWorker().doWork()

            coVerify { settingsRepository.updateScoringVersion(SettingsDefaults.CURRENT_SCORING_VERSION) }
            coVerify {
                settingsRepository.updateSleepScoreRecalcBaseline(
                    SleepScoreWeightProfile.BALANCED,
                    SettingsDefaults.GOAL_SLEEP_HOURS,
                    SettingsDefaults.HYPERSOMNIA_ONSET_PERCENT,
                )
            }
        }

    @Test
    fun `success with a current scoring version skips the bump but still marks the baseline`() =
        runBlocking {
            coEvery { useCase.execute(any(), any(), any()) } returns
                app.readylytics.health.core.model.domain.model.Result
                    .Success(Unit)
            coEvery { settingsRepository.userPreferences } returns
                MutableStateFlow(UserPreferences(scoringVersion = SettingsDefaults.CURRENT_SCORING_VERSION))
            createWorker().doWork()

            coVerify(exactly = 0) { settingsRepository.updateScoringVersion(any()) }
            coVerify { settingsRepository.updateSleepScoreRecalcBaseline(any(), any(), any()) }
        }

    @Test
    fun `retry path does not persist scoring version or baseline`() =
        runBlocking {
            coEvery { useCase.execute(any(), any(), any()) } returns
                app.readylytics.health.core.model.domain.model.Result
                    .Failure("error", "network error")
            createWorker().doWork()

            coVerify(exactly = 0) { settingsRepository.updateScoringVersion(any()) }
            coVerify(exactly = 0) { settingsRepository.updateSleepScoreRecalcBaseline(any(), any(), any()) }
        }

    @Test
    fun `exception path does not persist scoring version or baseline`() =
        runBlocking {
            coEvery { useCase.execute(any(), any(), any()) } throws RuntimeException("critical error")
            createWorker().doWork()

            coVerify(exactly = 0) { settingsRepository.updateScoringVersion(any()) }
            coVerify(exactly = 0) { settingsRepository.updateSleepScoreRecalcBaseline(any(), any(), any()) }
        }

    @Test
    fun `worker records requested parameters rather than later edits`() =
        runBlocking {
            every { workerParams.inputData } returns
                androidx.work.Data
                    .Builder()
                    .putString(HealthResyncWorker.KEY_RECOMPUTE_MODE, HealthResyncWorker.MODE_TRAINING_READINESS)
                    .putFloat(HealthResyncWorker.KEY_TRAINING_READINESS_SCALE, 100f)
                    .putFloat(HealthResyncWorker.KEY_TRAINING_READINESS_WEIGHT, .9f)
                    .build()
            coEvery { useCase.executeTrainingReadinessProjection(any(), any()) } returns
                app.readylytics.health.core.model.domain.model.Result
                    .Success(Unit)
            val capturedAppliedConfigSlot =
                slot<app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig>()
            coEvery { settingsRepository.updateTrainingReadinessConfig(capture(capturedAppliedConfigSlot)) } returns
                Unit

            val result = createWorker().doWork()

            assertEquals(
                androidx.work.ListenableWorker.Result
                    .success(),
                result,
            )
            assertEquals(
                app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
                    .fromStored(100f, .9f),
                capturedAppliedConfigSlot.captured,
            )
        }

    @Test
    fun `training readiness mode reports RECOMPUTE phase progress and never persists post-recompute state`() =
        runBlocking {
            every { workerParams.inputData } returns
                androidx.work.Data
                    .Builder()
                    .putString(HealthResyncWorker.KEY_RECOMPUTE_MODE, HealthResyncWorker.MODE_TRAINING_READINESS)
                    .putFloat(HealthResyncWorker.KEY_TRAINING_READINESS_SCALE, 40f)
                    .putFloat(HealthResyncWorker.KEY_TRAINING_READINESS_WEIGHT, .7f)
                    .build()
            coEvery { useCase.executeTrainingReadinessProjection(any(), any()) } answers {
                val progressCallback = secondArg<(Int, Int) -> Unit>()
                progressCallback(1, 5)
                app.readylytics.health.core.model.domain.model.Result
                    .Success(Unit)
            }
            coEvery { settingsRepository.updateTrainingReadinessConfig(any()) } returns Unit

            createWorker().doWork()

            verify(exactly = 1) {
                foregroundSyncController.onBackgroundRecalcProgress(ResyncPhase.RECOMPUTE, 1, 5)
            }
            coVerify(exactly = 0) { settingsRepository.updateScoringVersion(any()) }
            coVerify(exactly = 0) { settingsRepository.updateSleepScoreRecalcBaseline(any(), any(), any()) }
        }

    @Test
    fun `training readiness mode retries and does not update applied config when the use case fails`() =
        runBlocking {
            every { workerParams.inputData } returns
                androidx.work.Data
                    .Builder()
                    .putString(HealthResyncWorker.KEY_RECOMPUTE_MODE, HealthResyncWorker.MODE_TRAINING_READINESS)
                    .build()
            coEvery { useCase.executeTrainingReadinessProjection(any(), any()) } returns
                app.readylytics.health.core.model.domain.model.Result
                    .Failure("error", "PROJECTION_ERROR")

            val result = createWorker().doWork()

            assertEquals(
                androidx.work.ListenableWorker.Result
                    .retry(),
                result,
            )
            coVerify(exactly = 0) { settingsRepository.updateTrainingReadinessConfig(any()) }
        }

    @Test
    fun `training readiness mode retries when persisting the applied config fails`() =
        runBlocking {
            every { workerParams.inputData } returns
                androidx.work.Data
                    .Builder()
                    .putString(HealthResyncWorker.KEY_RECOMPUTE_MODE, HealthResyncWorker.MODE_TRAINING_READINESS)
                    .build()
            coEvery { useCase.executeTrainingReadinessProjection(any(), any()) } returns
                app.readylytics.health.core.model.domain.model.Result
                    .Success(Unit)
            coEvery { settingsRepository.updateTrainingReadinessConfig(any()) } throws
                RuntimeException("datastore io failure")

            val result = createWorker().doWork()

            assertEquals(
                androidx.work.ListenableWorker.Result
                    .retry(),
                result,
            )
        }

    @Test
    fun `a malformed recompute mode falls back to the normal recompute-only path`() =
        runBlocking {
            every { workerParams.inputData } returns
                androidx.work.Data
                    .Builder()
                    .putString(HealthResyncWorker.KEY_RECOMPUTE_MODE, "not_a_real_mode")
                    .putBoolean(HealthResyncWorker.KEY_RECOMPUTE_ONLY, true)
                    .build()
            coEvery { useCase.execute(any(), any(), any()) } returns
                app.readylytics.health.core.model.domain.model.Result
                    .Success(Unit)

            createWorker().doWork()

            coVerify(exactly = 1) { useCase.execute(true, any(), any()) }
            coVerify(exactly = 0) { useCase.executeTrainingReadinessProjection(any(), any()) }
        }

    @Test
    fun `persistence failure does not fail the worker`() =
        runBlocking {
            coEvery { useCase.execute(any(), any(), any()) } returns
                app.readylytics.health.core.model.domain.model.Result
                    .Success(Unit)
            coEvery { settingsRepository.userPreferences } throws
                RuntimeException("datastore io failure")
            val result = createWorker().doWork()
            assertEquals(
                androidx.work.ListenableWorker.Result
                    .success(),
                result,
            )
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
