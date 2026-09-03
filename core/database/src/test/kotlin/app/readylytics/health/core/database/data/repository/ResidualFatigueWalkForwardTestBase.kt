package app.readylytics.health.core.database.data.repository
import app.readylytics.health.core.scoring.domain.scoring.ComputeTrainingReadinessUseCase

import app.readylytics.health.core.database.data.mapper.DailySummaryMapper
import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyTemperatureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.repository.FatigueWorkoutInput
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.model.domain.repository.WalkForwardContexts
import app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import app.readylytics.health.core.scoring.domain.scoring.AssembleDailySummaryUseCase
import app.readylytics.health.core.scoring.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap
import kotlin.math.pow

/**
 * Shared fixture for the WP-27 residual-fatigue repository tests: the mocked DAO graph, a real
 * [ScoringRepositoryImpl] wired over it, and the range-aware fatigue/workout stubs both suites need.
 *
 * Split out of `ResidualFatigueWalkForwardDeterminismTest` so neither suite carries the scaffolding
 * plus every scenario in one class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ResidualFatigueWalkForwardTestBase {
    companion object {
        const val HOUR_MS = 3_600_000L
        const val EPSILON = 0.001f
    }

    protected val workoutDao = mockk<WorkoutDao>(relaxed = true)
    protected val sleepSessionDao = mockk<SleepSessionDao>(relaxed = true)
    protected val dailySummaryDao = mockk<DailySummaryDao>(relaxed = true)
    protected val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    protected val scoringCalculator = mockk<ScoringCalculator>(relaxed = true)
    protected val baselineComputer = mockk<BaselineComputer>(relaxed = true)
    protected val computeSleepMetricsUseCase = mockk<ComputeSleepMetricsUseCase>(relaxed = true)
    protected val scoringConfigFactory = mockk<ScoringConfigFactory>(relaxed = true)
    protected val heartRateDao = mockk<HeartRateDao>(relaxed = true)
    protected val minuteBucketDao = mockk<MinuteBucketDao>(relaxed = true)
    protected val weightRecordDao = mockk<WeightRecordDao>(relaxed = true)
    protected val bodyFatRecordDao = mockk<BodyFatRecordDao>(relaxed = true)
    protected val bloodPressureRecordDao = mockk<BloodPressureRecordDao>(relaxed = true)
    protected val oxygenSaturationRecordDao = mockk<OxygenSaturationRecordDao>(relaxed = true)
    protected val bodyTemperatureRecordDao = mockk<BodyTemperatureRecordDao>(relaxed = true)
    protected val scoringHistoryRepository = mockk<ScoringHistoryRepository>(relaxed = true)

    protected val zoneId: ZoneId = ZoneId.of("UTC")
    protected val day0: LocalDate = LocalDate.of(2026, 1, 1)
    protected val day1: LocalDate = day0.plusDays(1)
    protected val day2: LocalDate = day0.plusDays(2)
    protected val config = ResidualFatigueConfig(halfLifeHours = 24f, fatigueGain = 1.0f)
    protected val useCase = ComputeResidualFatigueUseCase()

    protected lateinit var repo: ScoringRepositoryImpl

    @Before
    fun setupFixture() {
        repo = createRepo()
        stubScoringDependencies()
    }

    private fun createRepo(): ScoringRepositoryImpl {
        val dataLoader =
            ScoringDayDataLoader(
                workoutDao,
                sleepSessionDao,
                dailySummaryDao,
                heartRateDao,
                minuteBucketDao,
                weightRecordDao,
                bodyFatRecordDao,
                bloodPressureRecordDao,
                oxygenSaturationRecordDao,
                bodyTemperatureRecordDao,
            )
        val bodyMetricsDataLoader =
            BodyMetricsDataLoader(
                weightRecordDao,
                bodyFatRecordDao,
                bloodPressureRecordDao,
                oxygenSaturationRecordDao,
                bodyTemperatureRecordDao,
            )
        val seriesLoader = ScoringSeriesLoader(workoutDao, dailySummaryDao)
        val readinessSummaryCoordinator =
            ReadinessSummaryCoordinator(
                dataLoader,
                seriesLoader,
                scoringHistoryRepository,
                baselineComputer,
                BuildLoadSeriesUseCase(scoringCalculator),
                computeSleepMetricsUseCase,
                ResolveDailyBaselinesUseCase(baselineComputer),
                AssembleDailySummaryUseCase(),
            )
        return ScoringRepositoryImpl(
            ScoringDataLoaders(
                dataLoader,
                bodyMetricsDataLoader,
                seriesLoader,
            ),
            settingsRepo,
            baselineComputer,
            scoringConfigFactory,
            ScoringDayUseCases(
                ComputeDailyTrimpUseCase(ComputeWorkoutTrimpUseCase()),
                ComputeResidualFatigueUseCase(),
                ResolveDailyBaselinesUseCase(baselineComputer),
                AssembleEverydayLoadInputUseCase(),
                        ComputeTrainingReadinessUseCase(scoringCalculator),
            ),
            scoringHistoryRepository,
            readinessSummaryCoordinator,
            UnconfinedTestDispatcher(),
        )
    }

    private fun stubScoringDependencies() {
        every { settingsRepo.userPreferences } returns
            flowOf(UserPreferences(scoringZoneId = zoneId.id, residualFatigueHalfLifeHours = 24f))
        coEvery { dailySummaryDao.getByDate(any()) } returns null
        coEvery { scoringHistoryRepository.getDailySummaryByDate(any(), any()) } returns null
        coEvery { sleepSessionDao.getOverlapping(any(), any()) } returns emptyList()
        coEvery { sleepSessionDao.countSince(any()) } returns 10
        coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns null
        coEvery { workoutDao.getWorkoutsInRange(any(), any()) } returns emptyList()
        coEvery { heartRateDao.getByTimeRange(any(), any()) } returns emptyList()
        coEvery { workoutDao.getTrimpPoints(any(), any()) } returns emptyList()
        coEvery { dailySummaryDao.getEverydayTrimpPoints(any(), any()) } returns emptyList()
        coEvery {
            baselineComputer.computeHrvBaselineBetween(any(), any(), any(), any(), any(), any())
        } returns null
        coEvery {
            baselineComputer.computeHrvBaselineBetween(any(), any(), null, any(), null, null)
        } returns null
        coEvery {
            baselineComputer.computeHrvBaselineBetween(any(), any(), any(), any(), null, null)
        } returns null
        coEvery {
            baselineComputer.computeAdaptiveBaselineRhrBpmBetween(any(), any(), any(), any(), any(), null)
        } returns 60f
        coEvery {
            baselineComputer.computeAdaptiveBaselineRhrBpmBetween(any(), any(), any(), any(), any(), any())
        } returns 60f
        coEvery { baselineComputer.computeHrvWindowsBetween(any(), any(), any(), any(), any(), null) } returns
            BaselineComputer.HrvWindows(
                muHistory = emptyList(),
                sigmaHistory = emptyList(),
                historicalSessions = emptyList(),
                validHistoricalSessionIds = emptyList(),
                validHistoricalDayCount = 6,
            )
        coEvery { baselineComputer.computeHrvWindowsBetween(any(), any(), any(), any(), any(), any()) } returns
            BaselineComputer.HrvWindows(
                muHistory = emptyList(),
                sigmaHistory = emptyList(),
                historicalSessions = emptyList(),
                validHistoricalSessionIds = emptyList(),
                validHistoricalDayCount = 6,
            )
        coEvery { computeSleepMetricsUseCase(any()) } returns
            Result.success(DailySummaryMapper.toDomain(DailySummaryEntity(0L), zoneId))
    }

    protected fun workoutInputs(): List<FatigueWorkoutInput> =
        listOf(
            FatigueWorkoutInput(
                workoutId = "day0-workout",
                endTimeMs = day0.atStartOfDay(zoneId).toInstant().toEpochMilli() + 2 * HOUR_MS,
                trimp = 30f,
            ),
            FatigueWorkoutInput(
                workoutId = "day1-workout",
                endTimeMs = day1.atStartOfDay(zoneId).toInstant().toEpochMilli() + 1 * HOUR_MS,
                trimp = 50f,
            ),
        )

    /**
     * Range-aware stub of the DAO queries. A plain `any()`-return stubs every call with the same
     * list regardless of its time predicates, which would mask window-boundary differences. Existing
     * input-only fixtures have no start time, so they default to historical seeds; boundary tests
     * supply their real start time explicitly.
     */
    protected fun stubFatigueWorkouts(
        workouts: List<FatigueWorkoutInput>,
        startTimeMs: (FatigueWorkoutInput) -> Long = { Long.MIN_VALUE },
    ) {
        coEvery { workoutDao.getCanonicalFatigueInputsThrough(any()) } answers {
            val evaluationTimeMs = firstArg<Long>()
            workouts.filter { it.endTimeMs <= evaluationTimeMs }
        }
        coEvery { workoutDao.getCanonicalFatigueSeed(any()) } answers {
            val boundaryMs = firstArg<Long>()
            workouts.filter { startTimeMs(it) < boundaryMs }
        }
    }

    protected fun evalMs(day: LocalDate): Long = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

    protected fun workoutRecord(
        id: String,
        startTime: Long,
        endTime: Long,
        trimp: Float,
        modelTrimp: Float?,
    ) = WorkoutRecordEntity(
        id = id,
        startTime = startTime,
        endTime = endTime,
        exerciseType = "RUNNING",
        durationMinutes = ((endTime - startTime) / 60_000L).toInt(),
        zone1Minutes = 0f,
        zone2Minutes = 0f,
        zone3Minutes = 0f,
        zone4Minutes = 0f,
        zone5Minutes = 0f,
        trimp = trimp,
        avgHr = 170f,
        modelTrimp = modelTrimp,
    )

    protected suspend fun runProductionPass(
        workout: WorkoutRecordEntity,
        prefs: UserPreferences,
        includeHeartRateSample: Boolean = true,
    ): ProductionPass {
        val store = stubProductionWorkoutStore(workout, includeHeartRateSample)
        val persisted = mutableListOf<DailySummaryEntity>()
        coEvery { dailySummaryDao.upsert(capture(persisted)) } returns Unit

        val firstFatigue = persistProductionDay(prefs, persisted)
        val secondFatigue = persistProductionDay(prefs, persisted)
        return ProductionPass(firstFatigue, secondFatigue, store.writtenModelTrimps.first())
    }

    private suspend fun persistProductionDay(
        prefs: UserPreferences,
        persisted: List<DailySummaryEntity>,
    ): Float {
        val fatigueContext = repo.fetchWalkForwardFatigueContext(day0, day0, zoneId)
        repo.computeAndPersistDailySummary(
            day0,
            null,
            prefs,
            WalkForwardContexts(
                WalkForwardTrimpContext(TreeMap(), TreeMap()),
                WalkForwardBaselineContext(emptyList()),
                fatigueContext,
            ),
        )
        return requireNotNull(persisted.last().residualFatigue)
    }

    protected fun stubProductionWorkoutStore(
        workout: WorkoutRecordEntity,
        includeHeartRateSample: Boolean = true,
    ): ProductionWorkoutStore {
        val store = ProductionWorkoutStore(mutableMapOf(workout.id to workout), mutableListOf())
        stubProductionWorkoutQueries(store, workout, includeHeartRateSample)
        stubProductionFatigueQueries(store)
        coEvery { workoutDao.upsertAll(any()) } answers {
            firstArg<List<WorkoutRecordEntity>>().forEach {
                store.workouts[it.id] = it
                store.writtenModelTrimps += requireNotNull(it.modelTrimp)
            }
        }
        return store
    }

    private fun stubProductionWorkoutQueries(
        store: ProductionWorkoutStore,
        workout: WorkoutRecordEntity,
        includeHeartRateSample: Boolean,
    ) {
        coEvery { workoutDao.getWorkoutsInRange(any(), any()) } answers {
            val from = firstArg<Long>()
            val to = secondArg<Long>()
            store.workouts.values.filter { it.startTime in from until to }
        }
        coEvery { heartRateDao.getByTypeAndTimeRange(RecordType.EXERCISE.name, any(), any()) } answers {
            val from = secondArg<Long>()
            val to = thirdArg<Long>()
            if (includeHeartRateSample) {
                listOf(
                    HeartRateRecordEntity(
                        sourceRecordRef = 1L,
                        timestampMs = workout.startTime,
                        beatsPerMinute = 170,
                        recordType = RecordType.EXERCISE.name,
                        sessionId = workout.id,
                    ),
                ).filter { it.timestampMs in from..to }
            } else {
                emptyList()
            }
        }
    }

    private fun stubProductionFatigueQueries(store: ProductionWorkoutStore) {
        coEvery { workoutDao.getCanonicalFatigueInputsThrough(any()) } answers {
            val evaluationTimeMs = firstArg<Long>()
            store.workouts.values
                .filter { it.endTime <= evaluationTimeMs }
                .mapNotNull { stored -> stored.toFatigueInput() }
        }
        coEvery { workoutDao.getCanonicalFatigueSeed(any()) } answers {
            val boundaryMs = firstArg<Long>()
            store.workouts.values
                .filter { it.startTime < boundaryMs }
                .mapNotNull { stored -> stored.toFatigueInput() }
        }
    }

    private fun WorkoutRecordEntity.toFatigueInput(): FatigueWorkoutInput? =
        modelTrimp
            ?.takeIf { it > 0f }
            ?.let { FatigueWorkoutInput(workoutId = id, endTimeMs = endTime, trimp = it) }

    protected fun impulseAtWorkoutEnd(
        fatigueAtEvaluation: Float,
        workoutEndTimeMs: Long,
    ): Float {
        val elapsedHours = (evalMs(day0) - workoutEndTimeMs).toDouble() / HOUR_MS
        return (fatigueAtEvaluation / 2.0.pow(-elapsedHours / config.halfLifeHours)).toFloat()
    }

    protected data class ProductionPass(
        val firstFatigue: Float,
        val secondFatigue: Float,
        val firstCanonicalModelTrimp: Float,
    )

    protected data class ProductionWorkoutStore(
        val workouts: MutableMap<String, WorkoutRecordEntity>,
        val writtenModelTrimps: MutableList<Float>,
    )

    protected fun expectedFatigue(
        day: LocalDate,
        allWorkouts: List<FatigueWorkoutInput>,
    ): Float =
        useCase.compute(
            evalMs(day),
            allWorkouts.map { ComputeResidualFatigueUseCase.FatigueWorkoutInput(it.endTimeMs, it.trimp) },
            config,
        )

    protected suspend fun runWalkForward(
        startDate: LocalDate,
        endDate: LocalDate,
        prefs: UserPreferences,
    ): Map<LocalDate, Float?> {
        val contexts =
            WalkForwardContexts(
                trimp = WalkForwardTrimpContext(TreeMap(), TreeMap()),
                baseline = WalkForwardBaselineContext(emptyList()),
                fatigue = repo.fetchWalkForwardFatigueContext(startDate, endDate, zoneId),
            )
        val persisted = mutableListOf<DailySummaryEntity>()
        coEvery { dailySummaryDao.upsert(capture(persisted)) } returns Unit
        var day = startDate
        while (!day.isAfter(endDate)) {
            repo.computeAndPersistDailySummary(day, null, prefs, contexts)
            day = day.plusDays(1)
        }
        return persisted.associate {
            Instant.ofEpochMilli(it.dateMidnightMs).atZone(zoneId).toLocalDate() to it.residualFatigue
        }
    }
}
