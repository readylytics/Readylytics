package app.readylytics.health.benchmark

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.HealthRecordDaos
import app.readylytics.health.core.database.data.local.RoomHealthIngestionStore
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
import app.readylytics.health.core.database.data.repository.BodyMetricsDataLoader
import app.readylytics.health.core.database.data.repository.DailySummaryRepositoryImpl
import app.readylytics.health.core.database.data.repository.HeartRateRepositoryImpl
import app.readylytics.health.core.database.data.repository.MorningRecommendationDependencies
import app.readylytics.health.core.database.data.repository.ReadinessSummaryCoordinator
import app.readylytics.health.core.database.data.repository.ScoringDataLoaders
import app.readylytics.health.core.database.data.repository.ScoringDayDataLoader
import app.readylytics.health.core.database.data.repository.ScoringDayUseCases
import app.readylytics.health.core.database.data.repository.ScoringHistoryRepositoryImpl
import app.readylytics.health.core.database.data.repository.ScoringRepositoryImpl
import app.readylytics.health.core.database.data.repository.ScoringSeriesLoader
import app.readylytics.health.core.database.data.repository.SleepSessionRepositoryImpl
import app.readylytics.health.core.database.data.repository.WorkoutRepositoryImpl
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepStageEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.security.EncryptionManager
import app.readylytics.health.core.scoring.domain.scoring.AssembleDailySummaryUseCase
import app.readylytics.health.core.scoring.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyRepository
import app.readylytics.health.core.scoring.domain.scoring.CompositeScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutLoadMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory
import app.readylytics.health.core.scoring.domain.scoring.SleepMetricsCollaborators
import app.readylytics.health.core.scoring.domain.scoring.WorkoutLoadClassifier
import app.readylytics.health.core.scoring.domain.scoring.sleep.CurrentNightHrvResolver
import app.readylytics.health.core.scoring.domain.scoring.sleep.HrCoverageValidator
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepModifierResolver
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepNadirAnalyzer
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepPercentileRhrCalculator
import app.readylytics.health.core.scoring.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.RasScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.SleepScoringStrategy
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId

/**
 * Shared constructor wiring and seed helpers for scoring and ingestion pipeline benchmarks.
 * Centralizes real production dependencies without test doubles in business logic.
 */
object ScoringBenchmarkHelper {
    fun createScoringRepository(
        db: HealthDatabase,
        zoneId: ZoneId,
        settingsRepo: SettingsRepository = BenchmarkFakeSettingsRepository(UserPreferences(scoringZoneId = zoneId.id)),
        encryptionManager: EncryptionManager = BenchmarkFakeEncryptionManager(),
    ): ScoringRepositoryImpl {
        val scoringHistoryRepository =
            ScoringHistoryRepositoryImpl(
                db.heartRateDao(),
                db.hrvDao(),
                db.sleepSessionDao(),
                db.dailySummaryDao(),
                db.minuteBucketDao(),
            )
        val loadScoringStrategy = LoadScoringStrategy()
        val scoringCalculator =
            CompositeScoringCalculator(
                sleepStrategy = SleepScoringStrategy(loadScoringStrategy),
                rasStrategy = RasScoringStrategy(),
                loadStrategy = loadScoringStrategy,
            )
        val baselineComputer = BaselineComputer(scoringHistoryRepository, scoringCalculator)
        val scoringConfigFactory = ScoringConfigFactory()
        val sleepSessionRepository =
            SleepSessionRepositoryImpl(
                db.sleepSessionDao(),
                db.sleepStageDao(),
            )
        val circadianConsistencyRepository =
            CircadianConsistencyRepository(
                sleepSessionRepository,
                settingsRepo,
                encryptionManager,
            )
        val sleepModifierResolver =
            SleepModifierResolver(
                sleepSessionRepository,
                circadianConsistencyRepository,
            )
        val computeSleepMetricsUseCase =
            ComputeSleepMetricsUseCase(
                collaborators =
                    SleepMetricsCollaborators(
                        baselineComputer = baselineComputer,
                        scoringHistoryRepository = scoringHistoryRepository,
                        scoringCalculator = scoringCalculator,
                        scoringConfigFactory = scoringConfigFactory,
                        encryptionManager = encryptionManager,
                        hrvResolver = CurrentNightHrvResolver(scoringHistoryRepository),
                        sleepPercentileRhrCalculator = SleepPercentileRhrCalculator(scoringHistoryRepository),
                        nadirAnalyzer = SleepNadirAnalyzer(scoringCalculator),
                        coverageValidator = HrCoverageValidator(),
                        sleepModifierResolver = sleepModifierResolver,
                    ),
            )
        val dataLoader =
            ScoringDayDataLoader(
                workoutDao = db.workoutDao(),
                sleepSessionDao = db.sleepSessionDao(),
                dailySummaryDao = db.dailySummaryDao(),
                heartRateDao = db.heartRateDao(),
                minuteBucketDao = db.minuteBucketDao(),
                weightRecordDao = db.weightRecordDao(),
                bodyFatRecordDao = db.bodyFatRecordDao(),
                bloodPressureRecordDao = db.bloodPressureRecordDao(),
                oxygenSaturationRecordDao = db.oxygenSaturationRecordDao(),
                bodyTemperatureRecordDao = db.bodyTemperatureRecordDao(),
            )
        val bodyMetricsDataLoader =
            BodyMetricsDataLoader(
                db.weightRecordDao(),
                db.bodyFatRecordDao(),
                db.bloodPressureRecordDao(),
                db.oxygenSaturationRecordDao(),
                db.bodyTemperatureRecordDao(),
            )
        val seriesLoader = ScoringSeriesLoader(db.workoutDao(), db.dailySummaryDao())
        val buildLoadSeriesUseCase = BuildLoadSeriesUseCase(scoringCalculator)
        val resolveDailyBaselinesUseCase = ResolveDailyBaselinesUseCase(baselineComputer)
        val assembleDailySummaryUseCase = AssembleDailySummaryUseCase()
        val readinessSummaryCoordinator =
            ReadinessSummaryCoordinator(
                dataLoader = dataLoader,
                seriesLoader = seriesLoader,
                scoringHistoryRepository = scoringHistoryRepository,
                baselineComputer = baselineComputer,
                buildLoadSeriesUseCase = buildLoadSeriesUseCase,
                computeSleepMetricsUseCase = computeSleepMetricsUseCase,
                resolveDailyBaselinesUseCase = resolveDailyBaselinesUseCase,
                assembleDailySummaryUseCase = assembleDailySummaryUseCase,
            )

        val workoutRepository = WorkoutRepositoryImpl(db.workoutDao(), db.workoutRoutePointDao())
        val dailySummaryRepository =
            DailySummaryRepositoryImpl(db.dailySummaryDao(), db.sleepSessionDao(), settingsRepo)
        val heartRateRepository = HeartRateRepositoryImpl(db.heartRateDao(), db.hrvDao(), db.minuteBucketDao())
        val getWorkoutDisplayMetricsUseCase =
            GetWorkoutDisplayMetricsUseCase(
                dailySummaryRepository = dailySummaryRepository,
                heartRateRepository = heartRateRepository,
                settingsRepo = settingsRepo,
                computeWorkoutLoadMetricsUseCase =
                    ComputeWorkoutLoadMetricsUseCase(
                        ComputeWorkoutTrimpUseCase(),
                        scoringCalculator,
                        WorkoutLoadClassifier(),
                    ),
            )
        return ScoringRepositoryImpl(
            loaders =
                ScoringDataLoaders(
                    day = dataLoader,
                    bodyMetrics = bodyMetricsDataLoader,
                    series = seriesLoader,
                ),
            settingsRepo = settingsRepo,
            baselineComputer = baselineComputer,
            scoringConfigFactory = scoringConfigFactory,
            useCases =
                ScoringDayUseCases(
                    computeDailyTrimp = ComputeDailyTrimpUseCase(ComputeWorkoutTrimpUseCase()),
                    computeResidualFatigue = ComputeResidualFatigueUseCase(),
                    resolveDailyBaselines = resolveDailyBaselinesUseCase,
                    assembleEverydayLoadInput = AssembleEverydayLoadInputUseCase(),
                ),
            scoringHistoryRepository = scoringHistoryRepository,
            readinessSummaryCoordinator = readinessSummaryCoordinator,
            defaultDispatcher = kotlinx.coroutines.Dispatchers.Default,
            recommendationDependencies =
                MorningRecommendationDependencies(
                    sleepSessionRepository = sleepSessionRepository,
                    computeSleepMetricsUseCase = computeSleepMetricsUseCase,
                    hrvResolver = CurrentNightHrvResolver(scoringHistoryRepository),
                    workoutRepository = workoutRepository,
                    dailySummaryRepository = dailySummaryRepository,
                    getWorkoutDisplayMetricsUseCase = getWorkoutDisplayMetricsUseCase,
                ),
        )
    }

    fun createRoomHealthIngestionStore(
        db: HealthDatabase,
        transactionRunner: app.readylytics.health.core.model.domain.repository.TransactionRunner =
            RoomTransactionRunner(db),
    ): RoomHealthIngestionStore {
        val daos =
            HealthRecordDaos(
                sleepSessionDao = db.sleepSessionDao(),
                sleepStageDao = db.sleepStageDao(),
                heartRateDao = db.heartRateDao(),
                hrvDao = db.hrvDao(),
                workoutDao = db.workoutDao(),
                workoutRoutePointDao = db.workoutRoutePointDao(),
                weightRecordDao = db.weightRecordDao(),
                bodyFatRecordDao = db.bodyFatRecordDao(),
                bloodPressureRecordDao = db.bloodPressureRecordDao(),
                oxygenSaturationRecordDao = db.oxygenSaturationRecordDao(),
                bodyTemperatureRecordDao = db.bodyTemperatureRecordDao(),
                stepRecordDao = db.stepRecordDao(),
                sourceRecordDao = db.sourceRecordDao(),
                minuteBucketMaintenanceDao = db.minuteBucketMaintenanceDao(),
            )
        return RoomHealthIngestionStore(
            daos = daos,
            dailySummaryDao = db.dailySummaryDao(),
            transactionRunner = transactionRunner,
            vo2MaxRecordDao = db.vo2MaxRecordDao(),
        )
    }

    fun seedCalibratedHistory(
        db: HealthDatabase,
        zoneId: ZoneId,
        targetDate: LocalDate,
        historyDays: Int = 30,
    ) {
        val sleepSessions = mutableListOf<SleepSessionEntity>()
        val sleepStages = mutableListOf<SleepStageEntity>()
        val heartRateRows = mutableListOf<HeartRateRecordEntity>()
        val hrvRows = mutableListOf<HrvRecordEntity>()
        val workouts = mutableListOf<WorkoutRecordEntity>()

        val hrSourceRef =
            runBlocking {
                db.sourceRecordDao().getOrCreateSourceRef("bench-calibrated-hr", "HEART_RATE", 0L)
            }
        val hrvSourceRef =
            runBlocking {
                db.sourceRecordDao().getOrCreateSourceRef("bench-calibrated-hrv", "HRV", 0L)
            }

        val startDate = targetDate.minusDays(historyDays.toLong())
        var currentDay = startDate
        var dayIndex = 0

        while (!currentDay.isAfter(targetDate)) {
            val bedTime =
                currentDay
                    .minusDays(1)
                    .atTime(23, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val wakeTime =
                currentDay
                    .atTime(7, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val sessionId = "calibrated_sleep_$dayIndex"

            sleepSessions +=
                SleepSessionEntity(
                    id = sessionId,
                    startTime = bedTime,
                    endTime = wakeTime,
                    durationMinutes = 480,
                    efficiency = 92f,
                    deepSleepMinutes = 100,
                    remSleepMinutes = 110,
                    lightSleepMinutes = 240,
                    awakeMinutes = 30,
                )
            sleepStages +=
                listOf(
                    SleepStageEntity(sessionId, "LIGHT", bedTime, bedTime + 120 * 60_000L, 120),
                    SleepStageEntity(sessionId, "DEEP", bedTime + 120 * 60_000L, bedTime + 220 * 60_000L, 100),
                    SleepStageEntity(sessionId, "REM", bedTime + 220 * 60_000L, bedTime + 330 * 60_000L, 110),
                    SleepStageEntity(sessionId, "LIGHT", bedTime + 330 * 60_000L, wakeTime, 120),
                )

            // Sleep resting HR (every 5 min)
            var sampleTime = bedTime
            var sampleIdx = 0
            while (sampleTime < wakeTime) {
                heartRateRows +=
                    HeartRateRecordEntity(
                        sourceRecordRef = hrSourceRef,
                        timestampMs = sampleTime,
                        beatsPerMinute = 52 + (sampleIdx % 12),
                        recordType = RecordType.RESTING.name,
                        sessionId = sessionId,
                    )
                sampleTime += 5 * 60_000L
                sampleIdx++
            }

            // Sleep HRV (every 30 min)
            sampleTime = bedTime + 30 * 60_000L
            var hrvIdx = 0
            while (sampleTime < wakeTime) {
                hrvRows +=
                    HrvRecordEntity(
                        sourceRecordRef = hrvSourceRef,
                        timestampMs = sampleTime,
                        rmssdMs = 45f + (hrvIdx % 15),
                        recordType = RecordType.RESTING.name,
                        sessionId = sessionId,
                    )
                sampleTime += 30 * 60_000L
                hrvIdx++
            }

            // Add a workout every other day
            if (dayIndex % 2 == 0) {
                val workoutStart =
                    currentDay
                        .atTime(17, 0)
                        .atZone(zoneId)
                        .toInstant()
                        .toEpochMilli()
                val workoutEnd = workoutStart + 45 * 60_000L
                val workoutId = "calibrated_workout_$dayIndex"
                workouts +=
                    WorkoutRecordEntity(
                        id = workoutId,
                        startTime = workoutStart,
                        endTime = workoutEnd,
                        exerciseType = "RUNNING",
                        durationMinutes = 45,
                        trimpScore = 65f,
                        avgHeartRate = 150,
                        maxHeartRate = 175,
                        zone1Minutes = 5,
                        zone2Minutes = 15,
                        zone3Minutes = 20,
                        zone4Minutes = 5,
                        zone5Minutes = 0,
                    )
            }

            currentDay = currentDay.plusDays(1)
            dayIndex++
        }

        runBlocking {
            db.sleepSessionDao().upsertAll(sleepSessions)
            db.sleepStageDao().upsertAll(sleepStages)
            db.heartRateDao().upsertAll(heartRateRows)
            db.hrvDao().upsertAll(hrvRows)
            db.workoutDao().upsertAll(workouts)
        }
    }
}
