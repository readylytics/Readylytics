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
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
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
import app.readylytics.health.core.model.data.preferences.UserPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ScoringRepositoryConcurrencyTest {
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

    private fun createRepo(dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()): ScoringRepositoryImpl {
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
            dispatcher,
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

    @Before
    fun setup() {
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
        coEvery { dailySummaryDao.getByDate(any()) } returns null
        coEvery { scoringHistoryRepository.getDailySummaryByDate(any(), any()) } returns null
        coEvery { sleepSessionDao.getOverlapping(any(), any()) } returns emptyList()
        coEvery { sleepSessionDao.countSince(any()) } returns 10
        coEvery { scoringHistoryRepository.countEligibleSleepDaysThrough(any(), any()) } returns 6
    }

    private fun mockWorkoutRecord(todayMs: Long) =
        WorkoutRecordEntity(
            id = "w1",
            startTime = todayMs + 1000,
            endTime = todayMs + 2000,
            exerciseType = "running",
            durationMinutes = 15,
            zone1Minutes = 0f,
            zone2Minutes = 0f,
            zone3Minutes = 0f,
            zone4Minutes = 0f,
            zone5Minutes = 0f,
            trimp = 10f,
            modelTrimp = 0f,
            avgHr = 140f,
        )

    private fun mockHrSample(todayMs: Long) =
        HeartRateRecordEntity(
            sourceRecordRef = 1L,
            timestampMs = todayMs + 1500,
            beatsPerMinute = 140,
            recordType = RecordType.EXERCISE.name,
            sessionId = "w1",
        )

    @Test
    fun `computeDailySummary and computeAndPersistDailySummary serialize via calculationMutex`() =
        runTest {
            val repo = createRepo(UnconfinedTestDispatcher(testScheduler))
            val today = LocalDate.now()
            val zoneId = ZoneId.systemDefault()
            val todayMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val concurrentCalls = AtomicInteger(0)
            val maxConcurrentCalls = AtomicInteger(0)

            coEvery { workoutDao.getWorkoutsInRange(any(), any()) } returns listOf(mockWorkoutRecord(todayMs))
            coEvery { heartRateDao.getByTypeAndTimeRange(RecordType.EXERCISE.name, any(), any()) } returns
                listOf(mockHrSample(todayMs))
            coEvery { computeSleepMetricsUseCase(any()) } returns
                Result.success(DailySummaryMapper.toDomain(DailySummaryEntity(0L), zoneId))
            coEvery { computeWorkoutTrimpUseCase.execute(any(), any(), any(), any(), any(), any(), any()) } returns
                Result.success(12f)
            coEvery { workoutDao.upsertAll(any()) } coAnswers {
                val current = concurrentCalls.incrementAndGet()
                maxConcurrentCalls.updateAndGet { maxOf(it, current) }
                delay(50)
                concurrentCalls.decrementAndGet()
            }

            val job1 = async { repo.computeDailySummary(today) }
            val job2 = async { repo.computeAndPersistDailySummary(today, null) }
            job1.await()
            job2.await()

            assertEquals(1, maxConcurrentCalls.get(), "Database writes on compute paths must not execute concurrently")
        }
}
