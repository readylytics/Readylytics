package app.readylytics.health.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerParameters
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.HealthRecordDaos
import app.readylytics.health.core.database.data.local.RoomDirtyRangeStore
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
import app.readylytics.health.core.database.data.local.SelectedSourcePrunerImpl
import app.readylytics.health.core.databaseschema.data.local.entity.HealthMutationStateEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.healthconnect.domain.sync.ForegroundSyncController
import app.readylytics.health.core.healthconnect.domain.sync.FullHistoricalResyncUseCase
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.migration.DatabaseReadinessInspector
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.sync.DirtyRangeStore
import app.readylytics.health.core.model.domain.sync.DirtyTicket
import app.readylytics.health.core.model.domain.sync.HealthMutationCoordinator
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.sync.SelectedSourcePruner
import app.readylytics.health.core.model.domain.util.RetentionBounds
import dagger.Lazy
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WP-17/HC-102 fix-round-3: the one-time selected-workout-device repair mode, split out of
 * [HealthResyncWorkerTest] so that file stays under detekt's `LargeClass` threshold (these tests
 * carry real in-memory Room setup that the rest of that file's mocked-use-case tests don't need).
 */
@RunWith(RobolectricTestRunner::class)
class HealthResyncWorkerSelectedWorkoutRepairTest {
    private val fixedInstant = Instant.parse("2026-05-01T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private val useCase = mockk<FullHistoricalResyncUseCase>()
    private val useCaseLazy = mockk<Lazy<FullHistoricalResyncUseCase>>()
    private val databaseReadinessGate = mockk<DatabaseReadinessInspector>()
    private val foregroundSyncController = mockk<ForegroundSyncController>(relaxed = true)
    private val foregroundSyncControllerLazy = mockk<Lazy<ForegroundSyncController>>()
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val settingsRepositoryLazy = mockk<Lazy<SettingsRepository>>()
    private val selectedSourcePruner = mockk<SelectedSourcePruner>()
    private val selectedSourcePrunerLazy = mockk<Lazy<SelectedSourcePruner>>()
    private val healthMutationCoordinator = mockk<HealthMutationCoordinator>()
    private val healthMutationCoordinatorLazy = mockk<Lazy<HealthMutationCoordinator>>()

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
        every { selectedSourcePrunerLazy.get() } returns selectedSourcePruner
        every { healthMutationCoordinatorLazy.get() } returns healthMutationCoordinator
        coEvery { healthMutationCoordinator.withMutation<ScoreInvalidation.AffectedRange?>(any()) } coAnswers {
            firstArg<suspend () -> ScoreInvalidation.AffectedRange?>().invoke()
        }

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
    fun `selected workout repair mode with no excluded device completes as a no-op and sets the flag`() =
        runBlocking {
            every { workerParams.inputData } returns
                androidx.work.Data
                    .Builder()
                    .putString(HealthResyncWorker.KEY_RECOMPUTE_MODE, HealthResyncWorker.MODE_SELECTED_WORKOUT_REPAIR)
                    .build()
            coEvery { settingsRepository.userPreferences } returns
                MutableStateFlow(UserPreferences(scoringVersion = 0))
            coEvery { settingsRepository.updateSelectedWorkoutRepairCompleted(true) } returns Unit

            val result = createWorker().doWork()

            assertEquals(
                androidx.work.ListenableWorker.Result
                    .success(),
                result,
            )
            coVerify(exactly = 1) { settingsRepository.updateSelectedWorkoutRepairCompleted(true) }
            coVerify(exactly = 0) { useCase.execute(any(), any(), any(), any()) }
            verify(exactly = 0) { selectedSourcePrunerLazy.get() }
        }

    @Test
    fun `selected workout repair mode short-circuits when already completed`() =
        runBlocking {
            every { workerParams.inputData } returns
                androidx.work.Data
                    .Builder()
                    .putString(HealthResyncWorker.KEY_RECOMPUTE_MODE, HealthResyncWorker.MODE_SELECTED_WORKOUT_REPAIR)
                    .build()
            coEvery { settingsRepository.userPreferences } returns
                MutableStateFlow(UserPreferences(selectedWorkoutRepairCompleted = true))

            val result = createWorker().doWork()

            assertEquals(
                androidx.work.ListenableWorker.Result
                    .success(),
                result,
            )
            coVerify(exactly = 0) { settingsRepository.updateSelectedWorkoutRepairCompleted(any()) }
            verify(exactly = 0) { selectedSourcePrunerLazy.get() }
        }

    @Test
    fun `selected workout repair mode prunes the excluded device and drains the journaled ticket`() =
        runBlocking {
            every { workerParams.inputData } returns
                androidx.work.Data
                    .Builder()
                    .putString(HealthResyncWorker.KEY_RECOMPUTE_MODE, HealthResyncWorker.MODE_SELECTED_WORKOUT_REPAIR)
                    .build()
            val prefs =
                UserPreferences(
                    deviceByDataType = mapOf(HealthDataType.EXERCISE.name to "Watch"),
                    retentionDaysEnabled = false,
                    scoringZoneId = "UTC",
                )
            coEvery { settingsRepository.userPreferences } returns MutableStateFlow(prefs)
            coEvery { settingsRepository.updateSelectedWorkoutRepairCompleted(true) } returns Unit
            // Fix-round-3: the live return value no longer drives the recompute range (the
            // durable journal does) -- return null here to prove that.
            coEvery {
                selectedSourcePruner.pruneExcludedWorkouts(any(), any(), "Watch", any())
            } returns null

            val database = buildInMemoryDatabase()
            try {
                val store = RoomDirtyRangeStore(database.dirtyRangeDao(), database.healthMutationStateDao())
                val affectedDate = LocalDate.of(2026, 5, 1)
                store.append(affectedDate, affectedDate, "WORKOUT", "ACTIVE")

                val rangeSlot = slot<ScoreInvalidation.AffectedRange?>()
                coEvery { useCase.execute(true, captureNullable(rangeSlot), any(), any()) } returns
                    app.readylytics.health.core.model.domain.model.Result
                        .Success(Unit)

                val result = createWorker(dirtyRangeStore = store).doWork()

                assertEquals(
                    androidx.work.ListenableWorker.Result
                        .success(),
                    result,
                )
                val expectedToday = LocalDate.of(2026, 5, 1)
                val expectedRetentionStart = expectedToday.minusDays(RetentionBounds.ABSOLUTE_MAX_DAYS)
                coVerify(exactly = 1) {
                    selectedSourcePruner.pruneExcludedWorkouts(
                        expectedRetentionStart,
                        expectedToday,
                        "Watch",
                        ZoneId.of("UTC"),
                    )
                }
                coVerify(exactly = 1) { settingsRepository.updateSelectedWorkoutRepairCompleted(true) }
                assertEquals(affectedDate, rangeSlot.captured?.start)
            } finally {
                database.close()
            }
        }

    /**
     * Fix-round-3 (durability): proves the repair recomputes correctly across TWO sequential
     * [HealthResyncWorker.doWork] calls using a REAL [SelectedSourcePrunerImpl] over a REAL
     * in-memory Room database -- not a mocked pruner -- so the prune's own delete and its journaled
     * ticket are genuinely durable, not simulated. Attempt 1 prunes "excluded" for real (its delete
     * commits) and journals a ticket, but the recompute step is injected to fail -- modeling a
     * worker killed/retried after the prune committed but before recompute succeeded. Attempt 2
     * reuses the same database: the live prune now finds nothing left to delete (already gone,
     * returns null) -- the exact state that made the original bug mark the flag complete without
     * ever recomputing -- but the ticket from attempt 1 survives in `dirty_ranges`, so attempt 2's
     * drain still recomputes the originally affected date, and only then does the flag flip true.
     */
    @Test
    fun failedRepairRetriesWithoutFlag() =
        runBlocking {
            every { workerParams.inputData } returns
                androidx.work.Data
                    .Builder()
                    .putString(HealthResyncWorker.KEY_RECOMPUTE_MODE, HealthResyncWorker.MODE_SELECTED_WORKOUT_REPAIR)
                    .build()
            val prefs =
                UserPreferences(
                    deviceByDataType = mapOf(HealthDataType.EXERCISE.name to "Watch"),
                    retentionDaysEnabled = false,
                    scoringZoneId = "UTC",
                )
            coEvery { settingsRepository.userPreferences } returns MutableStateFlow(prefs)

            val database = buildInMemoryDatabase()
            try {
                val affectedDate = LocalDate.of(2026, 5, 1)
                val timestamp = affectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                database.workoutDao().upsertAll(
                    listOf(
                        repairWorkoutRecord("kept", timestamp, "Watch"),
                        repairWorkoutRecord("excluded", timestamp, "Phone"),
                    ),
                )

                val store = RoomDirtyRangeStore(database.dirtyRangeDao(), database.healthMutationStateDao())
                val realPruner = buildRealPruner(database, store)

                assertFailedFirstAttemptJournalsTicket(store, database, realPruner, affectedDate)
                assertSecondAttemptDrainsTheSurvivingTicket(store, database, realPruner, affectedDate)
            } finally {
                database.close()
            }
        }

    private suspend fun assertFailedFirstAttemptJournalsTicket(
        store: RoomDirtyRangeStore,
        database: HealthDatabase,
        realPruner: SelectedSourcePruner,
        affectedDate: LocalDate,
    ) {
        // Attempt 1: prune deletes "excluded" for real and journals a ticket, but the recompute
        // step fails.
        coEvery { useCase.execute(true, any(), any(), any()) } returns
            app.readylytics.health.core.model.domain.model.Result
                .Failure("boom", "ERR")

        val firstResult = createWorker(dirtyRangeStore = store, pruner = realPruner).doWork()

        assertEquals(
            androidx.work.ListenableWorker.Result
                .retry(),
            firstResult,
        )
        coVerify(exactly = 0) { settingsRepository.updateSelectedWorkoutRepairCompleted(any()) }
        assertEquals(listOf("kept"), database.workoutDao().getSince(0).map { it.id })
        val ticketAfterFirstAttempt = store.pending(10)
        assertTrue(ticketAfterFirstAttempt.isNotEmpty())
        assertEquals(affectedDate, ticketAfterFirstAttempt.single().nextDay)
    }

    private suspend fun assertSecondAttemptDrainsTheSurvivingTicket(
        store: RoomDirtyRangeStore,
        database: HealthDatabase,
        realPruner: SelectedSourcePruner,
        affectedDate: LocalDate,
    ) {
        // Attempt 2 (fresh worker, same database): the live prune now finds nothing left to
        // delete (already gone), but the durable ticket from attempt 1 survives and drives
        // recompute. Recompute now succeeds.
        val rangeSlot = slot<ScoreInvalidation.AffectedRange?>()
        coEvery { useCase.execute(true, captureNullable(rangeSlot), any(), any()) } returns
            app.readylytics.health.core.model.domain.model.Result
                .Success(Unit)
        coEvery { settingsRepository.updateSelectedWorkoutRepairCompleted(true) } returns Unit

        val secondResult = createWorker(dirtyRangeStore = store, pruner = realPruner).doWork()

        assertEquals(
            androidx.work.ListenableWorker.Result
                .success(),
            secondResult,
        )
        assertEquals(affectedDate, rangeSlot.captured?.start)
        assertTrue(!rangeSlot.captured!!.endInclusive.isBefore(affectedDate))
        coVerify(exactly = 1) { settingsRepository.updateSelectedWorkoutRepairCompleted(true) }
        assertEquals(listOf("kept"), database.workoutDao().getSince(0).map { it.id })
    }

    private fun buildInMemoryDatabase(): HealthDatabase {
        val database =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        runBlocking {
            database.healthMutationStateDao().upsert(HealthMutationStateEntity(id = 1, sourceGeneration = 1))
        }
        return database
    }

    private fun buildRealPruner(
        database: HealthDatabase,
        store: RoomDirtyRangeStore,
    ) = SelectedSourcePrunerImpl(
        transactionRunner = RoomTransactionRunner(database),
        daos =
            HealthRecordDaos(
                sleepSessionDao = database.sleepSessionDao(),
                sleepStageDao = database.sleepStageDao(),
                heartRateDao = database.heartRateDao(),
                hrvDao = database.hrvDao(),
                workoutDao = database.workoutDao(),
                workoutRoutePointDao = database.workoutRoutePointDao(),
                weightRecordDao = database.weightRecordDao(),
                bodyFatRecordDao = database.bodyFatRecordDao(),
                bloodPressureRecordDao = database.bloodPressureRecordDao(),
                oxygenSaturationRecordDao = database.oxygenSaturationRecordDao(),
                bodyTemperatureRecordDao = database.bodyTemperatureRecordDao(),
                stepRecordDao = database.stepRecordDao(),
                sourceRecordDao = database.sourceRecordDao(),
                minuteBucketMaintenanceDao = database.minuteBucketMaintenanceDao(),
            ),
        dirtyRangeStore = store,
        healthMutationStateDao = database.healthMutationStateDao(),
    )

    @Test
    fun `selected workout repair receives scoring-zone today and retention start`() =
        runBlocking {
            every { workerParams.inputData } returns
                androidx.work.Data
                    .Builder()
                    .putString(HealthResyncWorker.KEY_RECOMPUTE_MODE, HealthResyncWorker.MODE_SELECTED_WORKOUT_REPAIR)
                    .build()
            val prefs =
                UserPreferences(
                    deviceByDataType = mapOf(HealthDataType.EXERCISE.name to "Watch"),
                    retentionDaysEnabled = true,
                    retentionDays = 30,
                    scoringZoneId = "Europe/Berlin",
                )
            coEvery { settingsRepository.userPreferences } returns MutableStateFlow(prefs)
            coEvery { settingsRepository.updateSelectedWorkoutRepairCompleted(true) } returns Unit
            coEvery {
                selectedSourcePruner.pruneExcludedWorkouts(any(), any(), any(), any())
            } returns null

            createWorker().doWork()

            val expectedZone = ZoneId.of("Europe/Berlin")
            val expectedToday = LocalDate.now(fixedClock.withZone(expectedZone))
            val expectedRetentionStart = expectedToday.minusDays(30)
            coVerify(exactly = 1) {
                selectedSourcePruner.pruneExcludedWorkouts(
                    expectedRetentionStart,
                    expectedToday,
                    "Watch",
                    expectedZone,
                )
            }
        }

    private fun createWorker(
        dirtyRangeStore: DirtyRangeStore? = null,
        pruner: SelectedSourcePruner? = null,
    ) = HealthResyncWorker(
        appContext = context,
        params = workerParams,
        fullHistoricalResyncUseCase = useCaseLazy,
        foregroundSyncController = foregroundSyncControllerLazy,
        databaseReadinessGate = databaseReadinessGate,
        settingsRepository = settingsRepositoryLazy,
        clock = fixedClock,
        dirtyRangeStore =
            Lazy {
                dirtyRangeStore ?: object : DirtyRangeStore {
                    override suspend fun pending(limit: Int): List<DirtyTicket> = emptyList()
                }
            },
        selectedSourcePruner = if (pruner != null) Lazy { pruner } else selectedSourcePrunerLazy,
        healthMutationCoordinator = healthMutationCoordinatorLazy,
    )

    /** Minimal real [WorkoutRecordEntity] for [failedRepairRetriesWithoutFlag]'s real-Room setup. */
    private fun repairWorkoutRecord(
        id: String,
        startTime: Long,
        deviceName: String?,
    ) = WorkoutRecordEntity(
        id = id,
        startTime = startTime,
        endTime = startTime + 3_600_000L,
        exerciseType = "RUNNING",
        durationMinutes = 60,
        zone1Minutes = 0f,
        zone2Minutes = 0f,
        zone3Minutes = 0f,
        zone4Minutes = 0f,
        zone5Minutes = 0f,
        trimp = 10f,
        avgHr = 120f,
        deviceName = deviceName,
    )
}
