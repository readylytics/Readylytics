package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.scoring.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.core.scoring.domain.scoring.CompositeScoringCalculator
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.scoring.domain.scoring.TrimpDateBucketer
import app.readylytics.health.core.scoring.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.RasScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.SleepScoringStrategy
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class WorkoutModelTrimpIngestionDeterminismTest {
    private val zoneId = ZoneId.of("Europe/Berlin")
    private val targetDate = LocalDate.of(2026, 7, 28)
    private lateinit var database: HealthDatabase
    private lateinit var store: RoomHealthIngestionStore

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        store =
            RoomHealthIngestionStore(
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
                dailySummaryDao = database.dailySummaryDao(),
                sourceRecordDao = database.sourceRecordDao(),
                transactionRunner = RoomTransactionRunner(database),
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `bulk overlap refetch preserves existing model trimp while updating raw workout fields`() =
        runTest {
            val existing =
                workout(
                    id = "recent",
                    date = targetDate.minusDays(1),
                    zoneTrimp = 90f,
                    modelTrimp = 35f,
                )
            database.workoutDao().upsertAll(listOf(existing))

            store.persist(batch(existing.toInput(zoneTrimp = 120f)))

            val refreshed = database.workoutDao().getById(existing.id)!!
            assertEquals(120f, refreshed.trimp)
            assertEquals(35f, refreshed.modelTrimp)
        }

    @Test
    fun `current workout-only strain ratio is unchanged by stable-id overlap refetch`() =
        runTest {
            val older =
                workout(
                    id = "older",
                    date = targetDate.minusDays(14),
                    zoneTrimp = 100f,
                    modelTrimp = 100f,
                )
            val recent =
                workout(
                    id = "recent",
                    date = targetDate.minusDays(1),
                    zoneTrimp = 90f,
                    modelTrimp = 25f,
                )
            database.workoutDao().upsertAll(listOf(older, recent))
            val beforeRefresh = workoutOnlyStrainRatio()

            store.persist(batch(recent.toInput(zoneTrimp = 120f)))

            val afterRefresh = workoutOnlyStrainRatio()
            assertEquals(beforeRefresh, afterRefresh, absoluteTolerance = 0.000001f)
        }

    @Test
    fun `bulk refetch does not synthesize model trimp for an invalidated workout`() =
        runTest {
            val invalidated =
                workout(
                    id = "invalidated",
                    date = targetDate.minusDays(1),
                    zoneTrimp = 90f,
                    modelTrimp = null,
                )
            database.workoutDao().upsertAll(listOf(invalidated))

            store.persist(batch(invalidated.toInput(zoneTrimp = 120f)))

            assertNull(database.workoutDao().getById(invalidated.id)!!.modelTrimp)
        }

    private suspend fun workoutOnlyStrainRatio(): Float {
        val fromMs =
            targetDate
                .minusDays(ScoringConstants.CHRONIC_DAYS * 2)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        val toMs =
            targetDate
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        val trimpByDate =
            TrimpDateBucketer
                .bucket(database.workoutDao().getTrimpPoints(fromMs, toMs), zoneId)
                .toMutableMap()
                .apply { put(targetDate, 0f) }
        val loadStrategy = LoadScoringStrategy()
        val calculator =
            CompositeScoringCalculator(
                sleepStrategy = SleepScoringStrategy(loadStrategy),
                rasStrategy = RasScoringStrategy(),
                loadStrategy = loadStrategy,
            )
        return BuildLoadSeriesUseCase(calculator)
            .execute(
                targetDate = targetDate,
                dailyTrimpByDate = trimpByDate,
                everydayTrimpByDate = emptyMap(),
            ).strainRatio
    }

    private fun workout(
        id: String,
        date: LocalDate,
        zoneTrimp: Float,
        modelTrimp: Float?,
    ): WorkoutRecordEntity {
        val startMs =
            date
                .atStartOfDay(zoneId)
                .plusHours(18)
                .toInstant()
                .toEpochMilli()
        return WorkoutRecordEntity(
            id = id,
            startTime = startMs,
            endTime = startMs + 3_600_000L,
            exerciseType = "RUNNING",
            durationMinutes = 60,
            zone1Minutes = 10f,
            zone2Minutes = 20f,
            zone3Minutes = 20f,
            zone4Minutes = 10f,
            zone5Minutes = 0f,
            trimp = zoneTrimp,
            avgHr = 145f,
            deviceName = "Test Watch",
            modelTrimp = modelTrimp,
        )
    }

    private fun WorkoutRecordEntity.toInput(zoneTrimp: Float): WorkoutInput =
        WorkoutInput(
            id = id,
            startTime = startTime,
            endTime = endTime,
            exerciseType = exerciseType,
            durationMinutes = durationMinutes,
            zone1Minutes = zone1Minutes,
            zone2Minutes = zone2Minutes,
            zone3Minutes = zone3Minutes,
            zone4Minutes = zone4Minutes,
            zone5Minutes = zone5Minutes,
            trimp = zoneTrimp,
            avgHr = avgHr,
            deviceName = deviceName,
        )

    private fun batch(workout: WorkoutInput): HealthIngestionBatch =
        HealthIngestionBatch(
            sleepSessions = emptyList(),
            sleepStages = emptyList(),
            heartRateSamples = emptyList(),
            hrvSamples = emptyList(),
            workouts = listOf(workout),
            weights = emptyList(),
            bodyFatSamples = emptyList(),
            bloodPressureSamples = emptyList(),
            oxygenSaturationSamples = emptyList(),
            bodyTemperatureSamples = emptyList(),
            stepRecords = emptyList(),
        )
}
