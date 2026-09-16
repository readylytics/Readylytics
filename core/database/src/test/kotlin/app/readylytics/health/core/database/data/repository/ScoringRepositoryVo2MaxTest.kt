package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.database.data.mapper.DailySummaryMapper
import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyTemperatureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.Vo2MaxRecordEntity
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.Vo2MaxEstimationMethod
import app.readylytics.health.core.model.domain.preferences.Vo2MaxSourceMode
import app.readylytics.health.core.model.domain.repository.FatigueWorkoutInput
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.scoring.domain.cardio.UthVo2MaxCalculator
import app.readylytics.health.core.scoring.domain.cardio.Vo2MaxSourceResolver
import app.readylytics.health.core.scoring.domain.scoring.AssembleDailySummaryUseCase
import app.readylytics.health.core.scoring.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeTrainingReadinessUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ln
import kotlin.math.pow
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ScoringRepositoryVo2MaxTest {
    private val workoutDao = mockk<WorkoutDao>(relaxed = true)
    private val sleepSessionDao = mockk<SleepSessionDao>(relaxed = true)
    private val dailySummaryDao = mockk<DailySummaryDao>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringCalculator = mockk<ScoringCalculator>(relaxed = true)
    private val baselineComputer = mockk<BaselineComputer>(relaxed = true)
    private val computeSleepMetricsUseCase = mockk<ComputeSleepMetricsUseCase>(relaxed = true)
    private val scoringConfigFactory = mockk<ScoringConfigFactory>(relaxed = true)
    private val computeWorkoutTrimpUseCase = mockk<ComputeWorkoutTrimpUseCase>(relaxed = true)
    private val heartRateDao = mockk<HeartRateDao>(relaxed = true)
    private val minuteBucketDao = mockk<MinuteBucketDao>(relaxed = true)
    private val weightRecordDao = mockk<WeightRecordDao>(relaxed = true)
    private val bodyFatRecordDao = mockk<BodyFatRecordDao>(relaxed = true)
    private val bloodPressureRecordDao = mockk<BloodPressureRecordDao>(relaxed = true)
    private val oxygenSaturationRecordDao = mockk<OxygenSaturationRecordDao>(relaxed = true)
    private val bodyTemperatureRecordDao = mockk<BodyTemperatureRecordDao>(relaxed = true)
    private val vo2MaxRecordDao = mockk<Vo2MaxRecordDao>(relaxed = true)
    private val scoringHistoryRepository = mockk<ScoringHistoryRepository>(relaxed = true)

    private val dataLoader = ScoringDayDataLoader(workoutDao, sleepSessionDao, dailySummaryDao)
    private val bodyMetricsDataLoader =
        BodyMetricsDataLoader(
            weightRecordDao,
            bodyFatRecordDao,
            bloodPressureRecordDao,
            oxygenSaturationRecordDao,
            bodyTemperatureRecordDao,
            vo2MaxRecordDao,
        )
    private val seriesLoader = ScoringSeriesLoader(workoutDao, dailySummaryDao)
    private val heartRateDataLoader = ScoringHeartRateDataLoader(heartRateDao, minuteBucketDao)

    private lateinit var repo: ScoringRepositoryImpl

    @Before
    fun setUp() {
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
        repo =
            ScoringRepositoryImpl(
                ScoringDataLoaders(dataLoader, bodyMetricsDataLoader, seriesLoader, heartRateDataLoader),
                settingsRepo,
                baselineComputer,
                scoringConfigFactory,
                ScoringDayUseCases(
                    ComputeDailyTrimpUseCase(computeWorkoutTrimpUseCase),
                    ComputeResidualFatigueUseCase(),
                    ResolveDailyBaselinesUseCase(baselineComputer),
                    AssembleEverydayLoadInputUseCase(),
                    ComputeTrainingReadinessUseCase(scoringCalculator),
                    UthVo2MaxCalculator(),
                    Vo2MaxSourceResolver(),
                ),
                scoringHistoryRepository,
                readinessSummaryCoordinator,
                UnconfinedTestDispatcher(),
                MorningRecommendationDependencies(
                    sleepSessionRepository = mockk(relaxed = true),
                    computeSleepMetricsUseCase = mockk(relaxed = true),
                    hrvResolver = mockk(relaxed = true),
                    workoutRepository = mockk(relaxed = true),
                    dailySummaryRepository = mockk(relaxed = true),
                    getWorkoutDisplayMetricsUseCase = mockk(relaxed = true),
                ),
            )
    }

    @Test
    fun `computeCurrentResidualFatigue decays through nowMs, not next-day midnight`() =
        runTest {
            val zoneId = ZoneId.of("UTC")
            val workoutEndMs = 1_700_000_000_000L
            val nowMs = workoutEndMs + 3 * 3_600_000L

            every { settingsRepo.userPreferences } returns
                flowOf(
                    UserPreferences(
                        scoringZoneId = zoneId.id,
                        residualFatigueHalfLifeHours = 24f,
                        residualFatigueGain = 1f,
                    ),
                )
            coEvery { workoutDao.getCanonicalFatigueInputsThrough(nowMs) } returns
                listOf(FatigueWorkoutInput(workoutId = "w1", endTimeMs = workoutEndMs, trimp = 100f))
            coEvery { workoutDao.countUnbackfilledThrough(any(), nowMs) } returns 0

            val result = repo.computeCurrentResidualFatigue(nowMs)

            val expected = (100f * 2.0.pow(-3.0 / 24.0)).toFloat()
            assertEquals(expected, requireNotNull(result), 0.01f)
        }

    @Test
    fun `materkoAdapted method computes estimate from hrv baseline and persists tag`() =
        runTest {
            val zoneId = ZoneId.of("UTC")
            val today = LocalDate.of(2026, 9, 1)
            val todayMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val existingSummary =
                DailySummaryEntity(
                    dateMidnightMs = todayMs,
                    baselineCalculatedAtDate = today.minusDays(1),
                    hrvMuMssd = ln(50.0).toFloat(),
                    rhrBpm = 60f,
                )
            val summary = DailySummaryMapper.toDomain(existingSummary, zoneId)
            every { settingsRepo.userPreferences } returns
                flowOf(
                    UserPreferences(
                        scoringZoneId = zoneId.id,
                        vo2MaxEstimationMethod = Vo2MaxEstimationMethod.MATERKO_ADAPTED,
                        vo2MaxSourceMode = Vo2MaxSourceMode.ESTIMATED_ONLY,
                    ),
                )
            coEvery { scoringHistoryRepository.getDailySummaryByDate(todayMs, zoneId) } returns summary
            coEvery { computeSleepMetricsUseCase(any()) } returns Result.success(summary)

            val result = repo.computeDailySummary(today)

            assertEquals(Vo2MaxSourceResolver.SOURCE_ESTIMATED_MATERKO_ADAPTED, result.vo2MaxSource)
            assertEquals(38.54f, result.vo2Max!!, 0.01f)
        }

    @Test
    fun `hrRatio method still emits uth tag`() =
        runTest {
            val zoneId = ZoneId.of("UTC")
            val today = LocalDate.of(2026, 9, 1)
            val todayMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val existingSummary =
                DailySummaryEntity(
                    dateMidnightMs = todayMs,
                    baselineCalculatedAtDate = today.minusDays(1),
                    hrvMuMssd = ln(50.0).toFloat(),
                    rhrBpm = 60f,
                    hrMax = 190f,
                )
            val summary = DailySummaryMapper.toDomain(existingSummary, zoneId)
            every { settingsRepo.userPreferences } returns
                flowOf(
                    UserPreferences(
                        scoringZoneId = zoneId.id,
                        vo2MaxEstimationMethod = Vo2MaxEstimationMethod.HR_RATIO,
                        vo2MaxSourceMode = Vo2MaxSourceMode.ESTIMATED_ONLY,
                    ),
                )
            coEvery { scoringHistoryRepository.getDailySummaryByDate(todayMs, zoneId) } returns summary
            coEvery { computeSleepMetricsUseCase(any()) } returns Result.success(summary)

            val result = repo.computeDailySummary(today)

            assertEquals(Vo2MaxSourceResolver.SOURCE_ESTIMATED_UTH, result.vo2MaxSource)
        }

    @Test
    fun `fetchWalkForwardVo2MaxContext loads range covering through endDate plus one midnight`() =
        runTest {
            val zoneId = ZoneId.of("UTC")
            val startDate = LocalDate.of(2026, 9, 1)
            val endDate = LocalDate.of(2026, 9, 5)
            val midnightAfterEnd = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val expectedRecord =
                Vo2MaxRecordEntity(
                    id = "v1",
                    timestampMs = midnightAfterEnd,
                    vo2Max = 45f,
                    measurementMethod = null,
                    deviceName = "TestDevice",
                )
            coEvery { vo2MaxRecordDao.getByTimeRange(any(), any()) } returns listOf(expectedRecord)

            val context = repo.fetchWalkForwardVo2MaxContext(startDate, endDate, zoneId)

            assertEquals(45f, context.vo2MaxByTimestampMs[midnightAfterEnd])
        }
}
