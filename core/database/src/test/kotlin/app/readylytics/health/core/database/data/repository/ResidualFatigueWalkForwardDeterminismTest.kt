package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
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
import app.readylytics.health.core.database.data.mapper.DailySummaryMapper
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.FatigueWorkoutInput
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
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
import app.readylytics.health.core.model.domain.scoring.TrimpModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.math.pow

/**
 * WP-27 shadow-mode determinism locks for the residual-fatigue walk-forward.
 *
 * Verifies the §9 guarantees that matter while fatigue stays shadow-only:
 *  - the accumulator (walk-forward) reproduces the summation formula exactly,
 *  - partial vs full sync ranges produce identical fatigue for overlapping days,
 *  - the single-day fallback matches the walk-forward value for the same day.
 *
 * Readiness is untouched by construction: the engine's Readiness path never reads
 * residualFatigue; these tests only assert fatigue is populated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResidualFatigueWalkForwardDeterminismTest {
    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val EPSILON = 0.001f
    }

    private val workoutDao = mockk<WorkoutDao>(relaxed = true)
    private val sleepSessionDao = mockk<SleepSessionDao>(relaxed = true)
    private val dailySummaryDao = mockk<DailySummaryDao>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringCalculator = mockk<ScoringCalculator>(relaxed = true)
    private val baselineComputer = mockk<BaselineComputer>(relaxed = true)
    private val computeSleepMetricsUseCase = mockk<ComputeSleepMetricsUseCase>(relaxed = true)
    private val scoringConfigFactory = mockk<ScoringConfigFactory>(relaxed = true)
    private val heartRateDao = mockk<HeartRateDao>(relaxed = true)
    private val minuteBucketDao = mockk<MinuteBucketDao>(relaxed = true)
    private val weightRecordDao = mockk<WeightRecordDao>(relaxed = true)
    private val bodyFatRecordDao = mockk<BodyFatRecordDao>(relaxed = true)
    private val bloodPressureRecordDao = mockk<BloodPressureRecordDao>(relaxed = true)
    private val oxygenSaturationRecordDao = mockk<OxygenSaturationRecordDao>(relaxed = true)
    private val bodyTemperatureRecordDao = mockk<BodyTemperatureRecordDao>(relaxed = true)
    private val scoringHistoryRepository = mockk<ScoringHistoryRepository>(relaxed = true)

    private val zoneId: ZoneId = ZoneId.of("UTC")
    private val day0: LocalDate = LocalDate.of(2026, 1, 1)
    private val day1: LocalDate = day0.plusDays(1)
    private val day2: LocalDate = day0.plusDays(2)
    private val config = ResidualFatigueConfig(enabled = true, halfLifeHours = 24f, fatigueGain = 1.0f)
    private val useCase = ComputeResidualFatigueUseCase()

    private lateinit var repo: ScoringRepositoryImpl

    @Before
    fun setup() {
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
            dataLoader,
            bodyMetricsDataLoader,
            seriesLoader,
            settingsRepo,
            baselineComputer,
            scoringConfigFactory,
            ComputeDailyTrimpUseCase(ComputeWorkoutTrimpUseCase()),
            ResolveDailyBaselinesUseCase(baselineComputer),
            AssembleEverydayLoadInputUseCase(),
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
        coEvery { baselineComputer.computeHrvBaselineBetween(any(), any(), any(), any(), any()) } returns null
        coEvery { baselineComputer.computeAdaptiveBaselineRhrBpmBetween(any(), any(), any(), any(), any()) } returns 60f
        coEvery { baselineComputer.computeHrvWindowsBetween(any(), any(), any(), any()) } returns
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

    private fun workoutInputs(): List<FatigueWorkoutInput> =
        listOf(
            FatigueWorkoutInput(
                workoutId = "day0-workout",
                endTimeMs = day0.atStartOfDay(zoneId).toInstant().toEpochMilli() + 2 * 3_600_000L,
                trimp = 30f,
            ),
            FatigueWorkoutInput(
                workoutId = "day1-workout",
                endTimeMs = day1.atStartOfDay(zoneId).toInstant().toEpochMilli() + 1 * 3_600_000L,
                trimp = 50f,
            ),
        )

    /**
     * Range-aware stub of the DAO query: like the real Room query, only workouts whose end time
     * falls inside the requested window are returned. A plain `any()`-return stubs every call the
     * same list regardless of window, which would mask window-width differences between paths.
     */
    private fun stubFatigueWorkouts(workouts: List<FatigueWorkoutInput>) {
        coEvery { workoutDao.getFatigueWorkoutInputs(any(), any()) } answers {
            val from = firstArg<Long>()
            val to = secondArg<Long>()
            workouts.filter { it.endTimeMs in from..to }
        }
        coEvery { workoutDao.getFatigueSeedWorkoutInputs(any(), any(), any()) } answers {
            val from = firstArg<Long>()
            val to = thirdArg<Long>()
            workouts.filter { it.endTimeMs in from..to }
        }
    }

    private fun evalMs(day: LocalDate): Long = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

    private fun workoutRecord(
        id: String,
        startTime: Long,
        endTime: Long,
        trimp: Float,
        modelTrimp: Float?,
    ) =
        WorkoutRecordEntity(
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

    private suspend fun runProductionPass(
        workout: WorkoutRecordEntity,
        prefs: UserPreferences,
    ): ProductionPass {
        val store = stubProductionWorkoutStore(workout)
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
            WalkForwardTrimpContext(TreeMap(), TreeMap()),
            WalkForwardBaselineContext(emptyList()),
            fatigueContext,
        )
        return requireNotNull(persisted.last().residualFatigue)
    }

    private fun stubProductionWorkoutStore(workout: WorkoutRecordEntity): ProductionWorkoutStore {
        val store = ProductionWorkoutStore(mutableMapOf(workout.id to workout), mutableListOf())
        stubProductionWorkoutQueries(store, workout)
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
    ) {
        coEvery { workoutDao.getWorkoutsInRange(any(), any()) } answers {
            val from = firstArg<Long>()
            val to = secondArg<Long>()
            store.workouts.values.filter { it.startTime in from until to }
        }
        coEvery { heartRateDao.getByTypeAndTimeRange(RecordType.EXERCISE.name, any(), any()) } answers {
            val from = secondArg<Long>()
            val to = thirdArg<Long>()
            listOf(
                HeartRateRecordEntity(
                    sourceRecordRef = 1L,
                    timestampMs = workout.startTime,
                    beatsPerMinute = 170,
                    recordType = RecordType.EXERCISE.name,
                    sessionId = workout.id,
                ),
            ).filter { it.timestampMs in from..to }
        }
    }

    private fun stubProductionFatigueQueries(store: ProductionWorkoutStore) {
        coEvery { workoutDao.getFatigueWorkoutInputs(any(), any()) } answers {
            val from = firstArg<Long>()
            val to = secondArg<Long>()
            store.workouts.values
                .filter { it.endTime in from..to }
                .mapNotNull { stored ->
                    (stored.modelTrimp ?: stored.trimp)
                        .takeIf { it > 0f }
                        ?.let { fatigueTrimp ->
                            FatigueWorkoutInput(
                                workoutId = stored.id,
                                endTimeMs = stored.endTime,
                                trimp = fatigueTrimp,
                            )
                        }
                }
        }
        coEvery { workoutDao.getFatigueSeedWorkoutInputs(any(), any(), any()) } answers {
            val from = firstArg<Long>()
            val seedCutoff = secondArg<Long>()
            val to = thirdArg<Long>()
            store.workouts.values
                .filter { it.startTime in from until seedCutoff && it.endTime <= to }
                .mapNotNull { stored ->
                    stored.modelTrimp
                        ?.takeIf { it > 0f }
                        ?.let { fatigueTrimp ->
                            FatigueWorkoutInput(
                                workoutId = stored.id,
                                endTimeMs = stored.endTime,
                                trimp = fatigueTrimp,
                            )
                        }
                }
        }
    }

    private fun impulseAtWorkoutEnd(fatigueAtEvaluation: Float, workoutEndTimeMs: Long): Float {
        val elapsedHours = (evalMs(day0) - workoutEndTimeMs).toDouble() / HOUR_MS
        return (fatigueAtEvaluation / 2.0.pow(-elapsedHours / config.halfLifeHours)).toFloat()
    }

    private data class ProductionPass(
        val firstFatigue: Float,
        val secondFatigue: Float,
        val firstCanonicalModelTrimp: Float,
    )

    private data class ProductionWorkoutStore(
        val workouts: MutableMap<String, WorkoutRecordEntity>,
        val writtenModelTrimps: MutableList<Float>,
    )

    private suspend fun canonicalModelTrimpFor(workoutId: String, prefs: UserPreferences): Float {
        val day0Start = day0.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val workout =
            workoutRecord(
                id = workoutId,
                startTime = day0Start + HOUR_MS,
                endTime = day0Start + 2 * HOUR_MS,
                trimp = 80f,
                modelTrimp = null,
            )
        return runProductionPass(workout, prefs).firstCanonicalModelTrimp
    }

    private suspend fun assertSelectedModelParameters(
        models: List<UserPreferences>,
        canonicalTrimps: List<Float>,
    ) {
        assertEquals(
            canonicalTrimps[1],
            canonicalModelTrimpFor("cheng-no-banister", models[1].copy(banisterMultiplier = 1f)),
            EPSILON,
        )
        assertNotEquals(
            canonicalTrimps[1],
            canonicalModelTrimpFor("cheng-different-beta", models[1].copy(chengBeta = 0.1f)),
            EPSILON,
        )
        assertEquals(
            canonicalTrimps[2],
            canonicalModelTrimpFor("itrimp-no-banister", models[2].copy(banisterMultiplier = 1f)),
            EPSILON,
        )
        assertNotEquals(
            canonicalTrimps[2],
            canonicalModelTrimpFor("itrimp-different-b", models[2].copy(itrimB = 1f)),
            EPSILON,
        )
    }

    private fun expectedFatigue(
        day: LocalDate,
        allWorkouts: List<FatigueWorkoutInput>,
    ): Float =
        useCase.compute(
            evalMs(day),
            allWorkouts.map { ComputeResidualFatigueUseCase.FatigueWorkoutInput(it.endTimeMs, it.trimp) },
            config,
        )

    private suspend fun runWalkForward(
        startDate: LocalDate,
        endDate: LocalDate,
        prefs: UserPreferences,
    ): Map<LocalDate, Float?> {
        val fatigueContext = repo.fetchWalkForwardFatigueContext(startDate, endDate, zoneId)
        val trimpContext = WalkForwardTrimpContext(TreeMap(), TreeMap())
        val baselineContext = WalkForwardBaselineContext(emptyList())
        val persisted = mutableListOf<DailySummaryEntity>()
        coEvery { dailySummaryDao.upsert(capture(persisted)) } returns Unit
        var day = startDate
        while (!day.isAfter(endDate)) {
            repo.computeAndPersistDailySummary(day, null, prefs, trimpContext, baselineContext, fatigueContext)
            day = day.plusDays(1)
        }
        return persisted.associate {
            Instant.ofEpochMilli(it.dateMidnightMs).atZone(zoneId).toLocalDate() to it.residualFatigue
        }
    }

    @Test
    fun `walk-forward accumulator reproduces summation formula per day`() =
        runTest {
            val workouts = workoutInputs()
            stubFatigueWorkouts(workouts)

            val prefs =
                UserPreferences(
                    scoringZoneId = zoneId.id,
                    residualFatigueHalfLifeHours = config.halfLifeHours,
                    residualFatigueGain = config.fatigueGain,
                )
            val fatigueByDate = runWalkForward(day0, day2, prefs)

            assertEquals(3, fatigueByDate.size)
            listOf(day0, day1, day2).forEach { day ->
                assertEquals(
                    expectedFatigue(day, workouts),
                    fatigueByDate[day],
                    "Day $day: accumulator must equal the summation formula",
                )
            }
        }

    @Test
    fun `partial walk-forward equals full walk-forward for overlapping days`() =
        runTest {
            val workouts = workoutInputs()
            stubFatigueWorkouts(workouts)

            val prefs =
                UserPreferences(
                    scoringZoneId = zoneId.id,
                    residualFatigueHalfLifeHours = config.halfLifeHours,
                    residualFatigueGain = config.fatigueGain,
                )
            val full = runWalkForward(day0, day2, prefs)
            val partial = runWalkForward(day1, day2, prefs)

            listOf(day1, day2).forEach { day ->
                assertEquals(
                    full[day],
                    partial[day],
                    "Day $day: residualFatigue must not depend on sync range",
                )
            }
        }

    @Test
    fun `single-day fallback matches walk-forward value for the same day`() =
        runTest {
            val workouts = workoutInputs()
            stubFatigueWorkouts(workouts)

            val prefs =
                UserPreferences(
                    scoringZoneId = zoneId.id,
                    residualFatigueHalfLifeHours = config.halfLifeHours,
                    residualFatigueGain = config.fatigueGain,
                )
            val walkForward = runWalkForward(day0, day1, prefs)

            val singleDay = repo.computeDailySummary(day1)

            assertEquals(walkForward[day1], singleDay.residualFatigue)
        }

    @Test
    fun `single-day fallback matches walk-forward for workout in the seed band`() =
        runTest {
            // A workout 10 days back sits in the 8-32-half-life band at the default 24h half-life:
            // inside the walk-forward's 32-day seed window but outside a naive 8x-half-life fallback
            // window. Both paths must cover the same window, or the same day scores differently
            // depending on which path recomputes it (spec §9 determinism).
            val oldWorkout =
                FatigueWorkoutInput(
                    workoutId = "old-workout",
                    endTimeMs =
                        day0
                            .minusDays(10)
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli() + 2 * 3_600_000L,
                    trimp = 30f,
                )
            stubFatigueWorkouts(listOf(oldWorkout))

            val prefs =
                UserPreferences(
                    scoringZoneId = zoneId.id,
                    residualFatigueHalfLifeHours = config.halfLifeHours,
                    residualFatigueGain = config.fatigueGain,
                )
            val walkForward = runWalkForward(day0, day0, prefs)

            val singleDay = repo.computeDailySummary(day0)

            assertEquals(walkForward[day0], singleDay.residualFatigue)
        }

    @Test
    fun `disabled fatigue persists null on both walk-forward and single-day paths`() =
        runTest {
            val workouts = workoutInputs()
            stubFatigueWorkouts(workouts)

            val disabledPrefs =
                UserPreferences(
                    scoringZoneId = zoneId.id,
                    residualFatigueEnabled = false,
                    residualFatigueHalfLifeHours = config.halfLifeHours,
                    residualFatigueGain = config.fatigueGain,
                )
            val walkForwardByDate = runWalkForward(day0, day1, disabledPrefs)
            assertNull(walkForwardByDate[day0], "Walk-forward with fatigue disabled must persist null")

            every { settingsRepo.userPreferences } returns flowOf(disabledPrefs)
            val singleDay = repo.computeDailySummary(day1)
            assertNull(singleDay.residualFatigue, "Single-day with fatigue disabled must persist null")
        }

    @Test
    fun `fetchWalkForwardFatigueContext holds sorted end-time impulse series`() =
        runTest {
            val workouts = workoutInputs()
            stubFatigueWorkouts(workouts)

            val context = repo.fetchWalkForwardFatigueContext(day0, day2, zoneId)

            assertEquals(
                workouts.map { it.endTimeMs },
                context.takeImpulsesThrough(Long.MAX_VALUE).map { it.endTimeMs },
                "Prefetch must preserve ascending end-time order",
            )
            assertEquals(0.0, context.accumulatedFatigue)
            assertEquals(Long.MIN_VALUE, context.lastEvaluationTimeMs)
        }

    @Test
    fun `walk-forward uses freshly calculated model TRIMP instead of stale persisted impulse`() =
        runTest {
            val day0Start = day0.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val workout =
                workoutRecord(
                    id = "stale-model",
                    startTime = day0Start + HOUR_MS,
                    endTime = day0Start + 2 * HOUR_MS,
                    trimp = 33f,
                    modelTrimp = 135f,
                )
            val pass =
                runProductionPass(
                    workout,
                    UserPreferences(
                        scoringZoneId = zoneId.id,
                        maxHeartRate = 190,
                        autoCalculateMaxHr = false,
                        residualFatigueHalfLifeHours = config.halfLifeHours,
                        residualFatigueGain = config.fatigueGain,
                    ),
                )
            val firstFatigueImpulse = impulseAtWorkoutEnd(pass.firstFatigue, workout.endTime)

            assertEquals(pass.firstCanonicalModelTrimp, firstFatigueImpulse, EPSILON)
            assertEquals(pass.firstFatigue, pass.secondFatigue, EPSILON)
            assertNotEquals(135f, firstFatigueImpulse, EPSILON)
            assertNotEquals(33f, firstFatigueImpulse, EPSILON)
        }

    @Test
    fun `walk-forward never uses Edwards fallback for a missing model TRIMP`() =
        runTest {
            val day0Start = day0.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val models =
                listOf(
                    UserPreferences(
                        scoringZoneId = zoneId.id,
                        trimpModel = TrimpModel.BANISTER,
                        banisterMultiplier = 2f,
                        maxHeartRate = 190,
                        autoCalculateMaxHr = false,
                        residualFatigueHalfLifeHours = config.halfLifeHours,
                        residualFatigueGain = config.fatigueGain,
                    ),
                    UserPreferences(
                        scoringZoneId = zoneId.id,
                        trimpModel = TrimpModel.CHENG,
                        banisterMultiplier = 8f,
                        chengBeta = 0.4f,
                        zone3MaxBpm = 150,
                        maxHeartRate = 190,
                        autoCalculateMaxHr = false,
                        residualFatigueHalfLifeHours = config.halfLifeHours,
                        residualFatigueGain = config.fatigueGain,
                    ),
                    UserPreferences(
                        scoringZoneId = zoneId.id,
                        trimpModel = TrimpModel.I_TRIMP,
                        banisterMultiplier = 8f,
                        itrimB = 3f,
                        maxHeartRate = 190,
                        autoCalculateMaxHr = false,
                        residualFatigueHalfLifeHours = config.halfLifeHours,
                        residualFatigueGain = config.fatigueGain,
                    ),
                )

            val canonicalTrimps =
                models.mapIndexed { index, prefs ->
                    val workout =
                        workoutRecord(
                            id = "missing-model-$index",
                            startTime = day0Start + HOUR_MS,
                            endTime = day0Start + 2 * HOUR_MS,
                            trimp = 80f,
                            modelTrimp = null,
                        )
                    val pass = runProductionPass(workout, prefs)
                    val firstFatigueImpulse = impulseAtWorkoutEnd(pass.firstFatigue, workout.endTime)

                    assertEquals(pass.firstCanonicalModelTrimp, firstFatigueImpulse, EPSILON)
                    assertEquals(pass.firstFatigue, pass.secondFatigue, EPSILON)
                    assertNotEquals(80f, firstFatigueImpulse, EPSILON)
                    pass.firstCanonicalModelTrimp
                }

            assertNotEquals(canonicalTrimps[0], canonicalTrimps[1], EPSILON)
            assertNotEquals(canonicalTrimps[0], canonicalTrimps[2], EPSILON)
            assertNotEquals(canonicalTrimps[1], canonicalTrimps[2], EPSILON)
            assertSelectedModelParameters(models, canonicalTrimps)
        }
}
