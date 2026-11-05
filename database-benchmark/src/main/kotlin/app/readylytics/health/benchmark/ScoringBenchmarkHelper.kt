package app.readylytics.health.benchmark

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.HealthRecordDaos
import app.readylytics.health.core.database.data.local.RoomHealthIngestionStore
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
import app.readylytics.health.core.database.data.repository.BaseSummaryAssembler
import app.readylytics.health.core.database.data.repository.BodyMetricsDataLoader
import app.readylytics.health.core.database.data.repository.CalibrationGate
import app.readylytics.health.core.database.data.repository.DailySummaryRepositoryImpl
import app.readylytics.health.core.database.data.repository.DailyTrimpComputer
import app.readylytics.health.core.database.data.repository.DayAssembler
import app.readylytics.health.core.database.data.repository.FinalSummaryAssembler
import app.readylytics.health.core.database.data.repository.HeartRateRepositoryImpl
import app.readylytics.health.core.database.data.repository.RasTotalsComputer
import app.readylytics.health.core.database.data.repository.ReadinessSummaryCoordinator
import app.readylytics.health.core.database.data.repository.ResidualFatigueComputer
import app.readylytics.health.core.database.data.repository.ScoringDayContextResolver
import app.readylytics.health.core.database.data.repository.ScoringDayDataLoader
import app.readylytics.health.core.database.data.repository.ScoringHeartRateDataLoader
import app.readylytics.health.core.database.data.repository.ScoringHistoryRepositoryImpl
import app.readylytics.health.core.database.data.repository.ScoringRepositoryImpl
import app.readylytics.health.core.database.data.repository.ScoringRuntime
import app.readylytics.health.core.database.data.repository.ScoringSeriesLoader
import app.readylytics.health.core.database.data.repository.SleepSessionRepositoryImpl
import app.readylytics.health.core.database.data.repository.Vo2MaxScoringDependencies
import app.readylytics.health.core.database.data.repository.WalkForwardContextLoader
import app.readylytics.health.core.database.data.repository.WorkoutRepositoryImpl
import app.readylytics.health.core.database.data.repository.recommendation.MorningRecommendationAssembler
import app.readylytics.health.core.database.data.repository.recommendation.MorningRecoveryLoader
import app.readylytics.health.core.database.data.repository.recommendation.WorkoutExampleLoader
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepStageEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.security.EncryptionManager
import app.readylytics.health.core.scoring.domain.cardio.MaterkoAdaptedVo2MaxCalculator
import app.readylytics.health.core.scoring.domain.cardio.UthVo2MaxCalculator
import app.readylytics.health.core.scoring.domain.cardio.Vo2MaxSourceResolver
import app.readylytics.health.core.scoring.domain.scoring.AssembleDailySummaryUseCase
import app.readylytics.health.core.scoring.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.core.scoring.domain.scoring.CanonicalWorkoutResolver
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyRepository
import app.readylytics.health.core.scoring.domain.scoring.CompositeScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeTrainingReadinessUseCase
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
import java.time.Clock
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
            )
        val bodyMetricsDataLoader =
            BodyMetricsDataLoader(
                db.weightRecordDao(),
                db.bodyFatRecordDao(),
                db.bloodPressureRecordDao(),
                db.oxygenSaturationRecordDao(),
                db.bodyTemperatureRecordDao(),
                db.vo2MaxRecordDao(),
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
                        scoringCalculator,
                        WorkoutLoadClassifier(),
                    ),
                canonicalWorkoutResolver = CanonicalWorkoutResolver(ComputeWorkoutTrimpUseCase()),
            )
        // One shared instance of each, mirroring the Hilt @Singleton graph.
        val residualFatigueComputer = ResidualFatigueComputer(dataLoader, ComputeResidualFatigueUseCase())
        val calibrationGate = CalibrationGate(scoringHistoryRepository)
        val morningRecommendationAssembler =
            MorningRecommendationAssembler(
                sleepSessionRepository,
                MorningRecoveryLoader(
                    sleepSessionRepository,
                    computeSleepMetricsUseCase,
                    CurrentNightHrvResolver(scoringHistoryRepository),
                    residualFatigueComputer,
                    scoringConfigFactory,
                    baselineComputer,
                    calibrationGate,
                ),
                WorkoutExampleLoader(workoutRepository, dailySummaryRepository, getWorkoutDisplayMetricsUseCase),
            )
        val finalSummaryAssembler =
            FinalSummaryAssembler(
                BaseSummaryAssembler(bodyMetricsDataLoader),
                calibrationGate,
                baselineComputer,
                bodyMetricsDataLoader,
                readinessSummaryCoordinator,
                residualFatigueComputer,
                ComputeTrainingReadinessUseCase(scoringCalculator),
                Vo2MaxScoringDependencies(
                    UthVo2MaxCalculator(),
                    MaterkoAdaptedVo2MaxCalculator(),
                    Vo2MaxSourceResolver(),
                ),
            )
        val runtime = ScoringRuntime(kotlinx.coroutines.Dispatchers.Default, Clock.systemDefaultZone())
        val dayAssembler =
            DayAssembler(
                readinessSummaryCoordinator = readinessSummaryCoordinator,
                dataLoader = dataLoader,
                dailyTrimpComputer =
                    DailyTrimpComputer(
                        dataLoader,
                        ScoringHeartRateDataLoader(db.heartRateDao(), db.minuteBucketDao()),
                        ComputeDailyTrimpUseCase(ComputeWorkoutTrimpUseCase()),
                        AssembleEverydayLoadInputUseCase(),
                    ),
                rasTotalsComputer = RasTotalsComputer(seriesLoader),
                finalSummaryAssembler = finalSummaryAssembler,
                morningRecommendationAssembler = morningRecommendationAssembler,
                scoringDayContextResolver =
                    ScoringDayContextResolver(
                        scoringConfigFactory,
                        resolveDailyBaselinesUseCase,
                        scoringHistoryRepository,
                    ),
                defaultDispatcher = runtime.defaultDispatcher,
            )
        return ScoringRepositoryImpl(
            settingsRepo = settingsRepo,
            dayAssembler = dayAssembler,
            dataLoader = dataLoader,
            residualFatigueComputer = residualFatigueComputer,
            walkForwardContextLoader =
                WalkForwardContextLoader(
                    seriesLoader,
                    bodyMetricsDataLoader,
                    baselineComputer,
                    scoringHistoryRepository,
                    residualFatigueComputer,
                ),
            runtime = runtime,
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
            scanTypeStateDao = db.scanTypeStateDao(),
            clock = Clock.systemDefaultZone(),
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
                db.sourceRecordDao().getSourceRef("bench-calibrated-hr")
                    ?: run {
                        db.sourceRecordDao().insertIgnore(
                            HealthSourceRecordEntity(
                                sourceRecordId = "bench-calibrated-hr",
                                recordType = "HEART_RATE",
                                createdAtMs = 0L,
                            ),
                        )
                        db.sourceRecordDao().getSourceRef("bench-calibrated-hr") ?: 1L
                    }
            }
        val hrvSourceRef =
            runBlocking {
                db.sourceRecordDao().getSourceRef("bench-calibrated-hrv")
                    ?: run {
                        db.sourceRecordDao().insertIgnore(
                            HealthSourceRecordEntity(
                                sourceRecordId = "bench-calibrated-hrv",
                                recordType = "HRV",
                                createdAtMs = 0L,
                            ),
                        )
                        db.sourceRecordDao().getSourceRef("bench-calibrated-hrv") ?: 1L
                    }
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
                    SleepStageEntity(
                        sessionId = sessionId,
                        stageType = "LIGHT",
                        startTime = bedTime,
                        endTime = bedTime + 120 * 60_000L,
                        durationMinutes = 120,
                    ),
                    SleepStageEntity(
                        sessionId = sessionId,
                        stageType = "DEEP",
                        startTime = bedTime + 120 * 60_000L,
                        endTime = bedTime + 220 * 60_000L,
                        durationMinutes = 100,
                    ),
                    SleepStageEntity(
                        sessionId = sessionId,
                        stageType = "REM",
                        startTime = bedTime + 220 * 60_000L,
                        endTime = bedTime + 330 * 60_000L,
                        durationMinutes = 110,
                    ),
                    SleepStageEntity(
                        sessionId = sessionId,
                        stageType = "LIGHT",
                        startTime = bedTime + 330 * 60_000L,
                        endTime = wakeTime,
                        durationMinutes = 120,
                    ),
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
                        zone1Minutes = 5f,
                        zone2Minutes = 15f,
                        zone3Minutes = 20f,
                        zone4Minutes = 5f,
                        zone5Minutes = 0f,
                        trimp = 65f,
                        avgHr = 150f,
                    )

                // EXERCISE-tagged samples across the workout window. Without them
                // DailyTrimpComputer -> CanonicalWorkoutResolver has no samples to integrate and no
                // prior canonical metadata to reuse, so every workout resolves to
                // WorkoutHrQuality.UNAVAILABLE, the day's raw TRIMP comes back null, and
                // ScoringRepositoryImpl correctly returns DayAssembly.Unavailable with
                // WORKOUT_LOAD_UNAVAILABLE -- which is a deliberate, unit-tested contract
                // (ScoringRepositoryImplAssemblyStatusTest), not a bug. The stored `trimp`/`avgHr`
                // columns above are Edwards-style values and are NOT inputs to canonical TRIMP, so
                // seeding them is not enough on its own.
                var workoutSampleTime = workoutStart
                var workoutSampleIdx = 0
                while (workoutSampleTime < workoutEnd) {
                    heartRateRows +=
                        HeartRateRecordEntity(
                            sourceRecordRef = hrSourceRef,
                            timestampMs = workoutSampleTime,
                            beatsPerMinute = 135 + (workoutSampleIdx % 25),
                            recordType = RecordType.EXERCISE.name,
                            sessionId = workoutId,
                        )
                    workoutSampleTime += 60_000L
                    workoutSampleIdx++
                }
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
