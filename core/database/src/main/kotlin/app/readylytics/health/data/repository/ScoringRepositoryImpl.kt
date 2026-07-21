package app.readylytics.health.data.repository

import app.readylytics.health.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.data.local.dao.BodyFatRecordDao
import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.dao.WeightRecordDao
import app.readylytics.health.data.local.dao.WorkoutDao
import app.readylytics.health.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.data.mapper.DailySummaryMapper
import app.readylytics.health.data.preferences.scoringZone
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.DailySummaryEntity
import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.domain.model.ReadinessResult
import app.readylytics.health.domain.model.RecordType
import app.readylytics.health.domain.model.SleepSessionEntity
import app.readylytics.health.domain.model.getOrNull
import app.readylytics.health.domain.repository.ScoringHistoryRepository
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.scoring.BaselineComputer
import app.readylytics.health.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.domain.scoring.EverydayHeartRateLoadCalculator
import app.readylytics.health.domain.scoring.EverydayHrLoadInput
import app.readylytics.health.domain.scoring.LongInterval
import app.readylytics.health.domain.scoring.RasCalculator
import app.readylytics.health.domain.scoring.ScoringCalculator
import app.readylytics.health.domain.scoring.ScoringConfigFactory
import app.readylytics.health.domain.scoring.ScoringConstants
import app.readylytics.health.domain.scoring.TrimpDateBucketer
import app.readylytics.health.domain.scoring.components.Phase
import app.readylytics.health.domain.scoring.sleep.SleepDayAggregate
import app.readylytics.health.domain.scoring.sleep.SleepDayAggregator
import app.readylytics.health.domain.scoring.sleep.SleepDayPolicy
import app.readylytics.health.domain.scoring.sleep.SleepDaySegment
import app.readylytics.health.domain.scoring.sleep.SleepPercentileRhrCalculator
import app.readylytics.health.domain.util.HeartRateFormulas
import app.readylytics.health.domain.util.logD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.round

@Singleton
class ScoringRepositoryImpl
    @Inject
    constructor(
        private val workoutDao: WorkoutDao,
        private val sleepSessionDao: SleepSessionDao,
        private val dailySummaryDao: DailySummaryDao,
        private val settingsRepo: SettingsRepository,
        private val scoringCalculator: ScoringCalculator,
        private val baselineComputer: BaselineComputer,
        private val computeSleepMetricsUseCase: ComputeSleepMetricsUseCase,
        private val scoringConfigFactory: ScoringConfigFactory,
        private val computeWorkoutTrimpUseCase: ComputeWorkoutTrimpUseCase,
        private val heartRateDao: HeartRateDao,
        private val weightRecordDao: WeightRecordDao,
        private val bodyFatRecordDao: BodyFatRecordDao,
        private val bloodPressureRecordDao: BloodPressureRecordDao,
        private val oxygenSaturationRecordDao: OxygenSaturationRecordDao,
        private val sleepPercentileRhrCalculator: SleepPercentileRhrCalculator,
        private val scoringHistoryRepository: ScoringHistoryRepository,
    ) : ScoringRepository {
        private val calculationMutex = Mutex()

        override suspend fun computeAndPersistDailySummary(
            targetDate: LocalDate,
            steps: Long?,
        ) {
            computeAndPersistDailySummary(targetDate, steps, settingsRepo.userPreferences.first())
        }

        override suspend fun computeAndPersistDailySummary(
            targetDate: LocalDate,
            steps: Long?,
            prefs: UserPreferences,
        ) {
            calculationMutex.withLock {
                val zoneId = prefs.scoringZone()
                val summary =
                    computeDailySummary(targetDate, prefs).let { computed ->
                        if (steps != null) {
                            computed.copy(stepCount = steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        } else {
                            computed
                        }
                    }
                persist(summary, zoneId)
            }
        }

        override suspend fun computeDailySummary(targetDate: LocalDate): DailySummary =
            computeDailySummary(targetDate, settingsRepo.userPreferences.first())

        private suspend fun computeDailySummary(
            targetDate: LocalDate,
            prefs: app.readylytics.health.data.preferences.UserPreferences,
        ): DailySummary =
            withContext(Dispatchers.Default) {
                val zoneId = prefs.scoringZone()
                val dayMidnight = targetDate.atStartOfDay(zoneId).toInstant()
                val nextDayMidnight = targetDate.plusDays(1).atStartOfDay(zoneId).toInstant()
                val dayMidnightMs = dayMidnight.toEpochMilli()
                val nextDayMidnightMs = nextDayMidnight.toEpochMilli()
                val sleepDayPolicy =
                    SleepDayPolicy(
                        coreMergeGapMinutes = prefs.coreMergeGapMinutes,
                        supplementalCutoffMinutesOfDay = prefs.supplementalCutoffMinutesOfDay,
                        minimumCountedSleepSegmentMinutes = prefs.minimumCountedSleepSegmentMinutes,
                        supplementalArchitectureCoveragePercent = prefs.supplementalArchitectureCoveragePercent,
                        scoringZoneId = zoneId,
                    )

                // Retrieve the nightly frozen HR_rest (nocturnal floor) from the daily summary if available
                val dailySummary = scoringHistoryRepository.getDailySummaryByDate(dayMidnightMs)

                val frozenSnapshot = dailySummary?.takeIf { it.baselineCalculatedAtDate != null }
                val frozenHrMax = frozenSnapshot?.hrMax
                val frozenRasScalingFactor = frozenSnapshot?.rasScalingFactor
                val hrMax = frozenHrMax ?: HeartRateFormulas.resolveMaxHeartRate(prefs)

                val rhrBaselineValue =
                    (if (dailySummary?.baselineCalculatedAtDate != null) dailySummary.rhrBpm else null)
                        ?: prefs.rhrBaselineOverride
                        ?: baselineComputer.computeAdaptiveBaselineRhrBpmBetween(
                            fromMs = dayMidnightMs,
                            toMs = nextDayMidnightMs,
                            percentile = prefs.restingHrPercentile,
                            sleepDayPolicy = sleepDayPolicy,
                        )
                        ?: ScoringConstants.DEFAULT_RHR_BPM

                if (hrMax <= 0f) throw IllegalStateException("HR Max is missing or invalid")
                if (rhrBaselineValue <= 0f) throw IllegalStateException("RHR Baseline is missing or invalid")

                val installDateFromPrefs = LocalDate.ofEpochDay(prefs.installDate / 86400000)
                val scoringConfig =
                    scoringConfigFactory.build(
                        userPreferences = prefs,
                        installDate = installDateFromPrefs,
                        currentDate = targetDate,
                    )

                logD("ScoringRepository") { "RAS CALC START [$targetDate]" }

                val workouts = workoutDao.getWorkoutsInRange(dayMidnightMs, nextDayMidnightMs)
                val allDayExerciseHrSamples =
                    if (workouts.isNotEmpty()) {
                        val minStart = workouts.minOf { it.startTime }
                        val maxEnd = workouts.maxOf { it.endTime }
                        heartRateDao
                            .getByTimeRange(minStart, maxEnd)
                            .filter { it.recordType == RecordType.EXERCISE.name }
                            .sortedBy { it.timestampMs }
                    } else {
                        emptyList()
                    }
                var dailyTrimpRaw = 0f
                val workoutModelTrimpUpdates = mutableListOf<WorkoutRecordEntity>()

                workouts.forEach { workout ->
                    val workoutHrSamples =
                        allDayExerciseHrSamples.filter { it.timestampMs in workout.startTime..workout.endTime }

                    val workoutAvgHr =
                        workoutHrSamples
                            .takeIf { it.isNotEmpty() }
                            ?.map { it.beatsPerMinute }
                            ?.average()
                            ?.toFloat()
                            ?: 0f
                    val samples =
                        workoutHrSamples.map { sample ->
                            ComputeWorkoutTrimpUseCase.HeartRateSample(
                                java.time.Instant.ofEpochMilli(sample.timestampMs),
                                sample.beatsPerMinute,
                            )
                        }
                    val workoutTrimpResult =
                        computeWorkoutTrimpUseCase.execute(
                            workoutStartTime = workout.startTime,
                            workoutEndTime = workout.endTime,
                            workoutAvgHr = workoutAvgHr,
                            samples = samples,
                            prefs = prefs,
                            restingHrBaseline = rhrBaselineValue,
                            storedTrimp = workout.trimp,
                            frozenHrMax = frozenHrMax,
                        )
                    val workoutTrimp = workoutTrimpResult.getOrNull() ?: 0f
                    dailyTrimpRaw += workoutTrimp
                    // SCORE-001: persist the user-selected-model TRIMP (Banister/Cheng/iTRIMP)
                    // alongside the existing zone-weighted `trimp` column, so WorkoutDao.getTrimpPoints'
                    // COALESCE(modelTrimp, trimp) can prefer it once this row has been touched.
                    if (workout.modelTrimp != workoutTrimp) {
                        workoutModelTrimpUpdates += workout.copy(modelTrimp = workoutTrimp)
                    }
                }
                if (workoutModelTrimpUpdates.isNotEmpty()) {
                    workoutDao.upsertAll(workoutModelTrimpUpdates)
                }

                // Sleep intervals are resolved before the everyday-HR block because the load calculator
                // must exclude every sleep segment contributing to this score day. Recovery metrics still
                // run only against the core cluster via currentSessionIds.
                val aggregatedSleep = resolveSleepAggregation(targetDate, zoneId, prefs)
                val session = aggregatedSleep?.scoringSession ?: sleepSessionDao.getSessionEndingInRange(dayMidnightMs, nextDayMidnightMs)
                val currentSessionIds = aggregatedSleep?.coreSessionIds ?: session?.let { setOf(it.id) }.orEmpty()

                // Everyday-HR load variant: full-day HR samples bucketed into waking, non-workout,
                // non-sleep minutes (interval exclusion handled inside the calculator).
                val everydayHrSamples =
                    heartRateDao
                        .getByTimeRange(dayMidnightMs, nextDayMidnightMs)
                        .map { sample ->
                            ComputeWorkoutTrimpUseCase.HeartRateSample(
                                Instant.ofEpochMilli(sample.timestampMs),
                                sample.beatsPerMinute,
                            )
                        }
                val sleepIntervalsMs =
                    aggregatedSleep?.allSleepIntervals
                        ?: if (session != null) listOf(LongInterval(session.startTime, session.endTime)) else emptyList()
                val workoutIntervalsMs = workouts.map { LongInterval(it.startTime, it.endTime) }
                val everydayResult =
                    EverydayHeartRateLoadCalculator.calculate(
                        EverydayHrLoadInput(
                            dayStartMs = dayMidnightMs,
                            dayEndMs = nextDayMidnightMs,
                            hrSamples = everydayHrSamples,
                            sleepIntervalsMs = sleepIntervalsMs,
                            workoutIntervalsMs = workoutIntervalsMs,
                            workoutOnlyTrimp = dailyTrimpRaw,
                            rhrBaseline = rhrBaselineValue,
                            hrMax = hrMax,
                            prefs = prefs,
                        ),
                    )
                val trimpEverydayHr = everydayResult.totalEverydayTrimp
                val everydayCoverageMinutes = everydayResult.coverageMinutes
                val everydayLoadConfidence = everydayResult.confidence.name

                // Enforce 75-point daily cap. Standard RAS is pure load, no readiness penalty.
                // Round daily RAS to 1 decimal place to ensure display consistency.
                val scalingFactor = frozenRasScalingFactor ?: scoringConfig.rasScalingFactor
                val dailyRasRaw = RasCalculator.calculateDailyRas(dailyTrimpRaw, scalingFactor)
                val dailyRas = round(dailyRasRaw * 10f) / 10f
                val dailyRasEverydayHr =
                    round(RasCalculator.calculateDailyRas(trimpEverydayHr, scalingFactor) * 10f) / 10f

                val last6DaysRasWorkoutOnly = sumRasLastSixDays(targetDate, zoneId) { it.rasWorkoutOnly }
                val last6DaysRasEverydayHr = sumRasLastSixDays(targetDate, zoneId) { it.rasEverydayHr }
                // Total RAS is rounded to nearest integer to match the UI's simple sum of rounded daily values.
                val totalRasWorkoutOnly = round(dailyRas + last6DaysRasWorkoutOnly)
                val totalRasEverydayHr = round(dailyRasEverydayHr + last6DaysRasEverydayHr)

                @Suppress("SENSELESS_COMPARISON")
                val dailyTrimpPresent = dailyTrimpRaw != null
                logD("ScoringRepository") {
                    "RAS calculation completed: workoutsFound=${workouts.isNotEmpty()}, dailyTrimpPresent=$dailyTrimpPresent"
                }

                val latestWeight = weightRecordDao.getLatestUpTo(nextDayMidnightMs)
                val latestBodyFat = bodyFatRecordDao.getLatestUpTo(nextDayMidnightMs)
                val latestBP = bloodPressureRecordDao.getLatestUpTo(nextDayMidnightMs)

                var summary =
                    (
                        scoringHistoryRepository.getDailySummaryByDate(dayMidnightMs)
                            ?: DailySummaryEntity(dateMidnightMs = dayMidnightMs)
                    ).copy(
                        trimpWorkoutOnly = dailyTrimpRaw,
                        trimpEverydayHr = trimpEverydayHr,
                        rasWorkoutOnly = dailyRas,
                        rasEverydayHr = dailyRasEverydayHr,
                        totalRasWorkoutOnly = totalRasWorkoutOnly,
                        totalRasEverydayHr = totalRasEverydayHr,
                        everydayCoverageMinutes = everydayCoverageMinutes,
                        everydayLoadConfidence = everydayLoadConfidence,
                        weightKg = latestWeight?.weightKg,
                        bodyFatPercent = latestBodyFat?.bodyFatPercent,
                        bloodPressureSystolic = latestBP?.systolicMmHg,
                        bloodPressureDiastolic = latestBP?.diastolicMmHg,
                        supplementalSleepDurationMinutes = aggregatedSleep?.aggregate?.supplementalSleepDurationMinutes,
                        napCount = aggregatedSleep?.aggregate?.supplementalBlocks?.size,
                    )

                val isCalibrated =
                    dailySummary?.baselineCalculatedAtDate != null ||
                        baselineComputer
                            .computeHrvWindowsBetween(
                                fromMs = dayMidnightMs,
                                toMs = nextDayMidnightMs,
                                sleepDayPolicy = sleepDayPolicy,
                            )?.validHistoricalDayCount
                            ?.plus(if (session != null) 1 else 0)
                            ?.let { it >= ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION }
                            ?: false

                val avgSpo2 =
                    if (session != null) {
                        val spo2Samples = oxygenSaturationRecordDao.getByTimeRange(session.startTime, session.endTime)
                        if (spo2Samples.isNotEmpty()) {
                            spo2Samples
                                .asSequence()
                                .map { it.percentage }
                                .average()
                                .toFloat()
                        } else {
                            null
                        }
                    } else {
                        null
                    }

                if (!isCalibrated) {
                    if (session != null) {
                        val hrvValues =
                            if (currentSessionIds.size <= 1) {
                                scoringHistoryRepository.getSleepRmssdForSession(session.id)
                            } else {
                                scoringHistoryRepository
                                    .getSleepRmssdForSessionsMap(currentSessionIds.toList())
                                    .values
                                    .flatten()
                            }
                        val avgHrv =
                            if (hrvValues.isNotEmpty()) {
                                (hrvValues.sum() / hrvValues.size).toInt()
                            } else {
                                null
                            }
                        val sleepHrSamples =
                            if (currentSessionIds.size <= 1) {
                                scoringHistoryRepository.getSleepHrSamplesForSession(session.id)
                            } else {
                                scoringHistoryRepository
                                    .getSleepHrProjectionForSessions(currentSessionIds.toList())
                                    .map { it.beatsPerMinute }
                                    .sorted()
                            }
                        val avgRhr =
                            if (sleepHrSamples.isNotEmpty()) {
                                val idx =
                                    Math
                                        .round((prefs.restingHrPercentile / 100.0) * (sleepHrSamples.size - 1))
                                        .toInt()
                                        .coerceIn(0, sleepHrSamples.size - 1)
                                sleepHrSamples[idx]
                            } else {
                                null
                            }
                        summary =
                            summary.copy(
                                nocturnalHrv = avgHrv,
                                restingHeartRate = avgRhr,
                                sleepDurationMinutes = session.durationMinutes,
                                deepSleepPercent =
                                    if (session.durationMinutes > 0) {
                                        session.deepSleepMinutes / session.durationMinutes.toFloat() * 100f
                                    } else {
                                        null
                                    },
                                remSleepPercent =
                                    if (session.durationMinutes > 0) {
                                        session.remSleepMinutes / session.durationMinutes.toFloat() * 100f
                                    } else {
                                        null
                                    },
                                isCalibrating = true,
                                avgSleepingSpo2 = avgSpo2,
                                snapshotCalibrationPhase = Phase.CALIBRATION.name,
                            )
                    } else {
                        summary =
                            summary.copy(
                                isCalibrating = true,
                                avgSleepingSpo2 = avgSpo2,
                                snapshotCalibrationPhase = Phase.CALIBRATION.name,
                            )
                    }

                    // Bounded baselines during calibration prevent default-50bpm display on dashboard.
                    val calibHrvBaseline =
                        baselineComputer.computeHrvBaselineBetween(
                            fromMs = dayMidnightMs,
                            toMs = nextDayMidnightMs,
                            hrvBaselineOverride = prefs.hrvBaselineOverride,
                            sleepDayPolicy = sleepDayPolicy,
                        )

                    // Calibration bypasses computeSleepMetricsUseCase; collect directly to populate restingHrBaseline + rhrRatio
                    val rhrWakeResult =
                        if (session != null) {
                            sleepPercentileRhrCalculator.collect(
                                session = session,
                                dayMidnight = dayMidnight,
                                percentile = prefs.restingHrPercentile,
                                currentSessionIds = currentSessionIds,
                            )
                        } else {
                            null
                        }

                    summary =
                        summary.copy(
                            hrvBaseline = calibHrvBaseline,
                            rhrBpm = rhrBaselineValue,
                        )

                    val updatedAudit =
                        scoringConfig.auditTrail.copy(
                            appliedSf = scoringConfig.rasScalingFactor,
                            physiologyProfile = prefs.physiologyProfile.name,
                            rasTotalPre = last6DaysRasWorkoutOnly,
                            rasTotalPost = summary.totalRasWorkoutOnly,
                        )
                    logD("ScoringConfig") { "Telemetry: $updatedAudit" }

                    return@withContext DailySummaryMapper.toDomain(summary, zoneId)
                }

                val ctlFetchFrom =
                    targetDate
                        .minusDays(ScoringConstants.CHRONIC_DAYS * 2)
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli()
                // SCORE-005: build the series from the persisted (now-COALESCEd) model-TRIMP column,
                // then inject today's freshly computed dailyTrimpRaw directly -- mirrors the
                // everyday-HR series below and keeps this read independent of the upsertAll above
                // being visible through this exact bucketed query.
                val dailyTrimpByDate =
                    TrimpDateBucketer
                        .bucket(
                            workoutDao.getTrimpPoints(ctlFetchFrom, nextDayMidnightMs),
                            zoneId,
                        ).toMutableMap()
                        .apply { put(targetDate, dailyTrimpRaw) }

                val ctl = scoringCalculator.computeCtlEmaWithDecay(dailyTrimpByDate, targetDate)
                val atl = scoringCalculator.computeAtlEmaWithDecay(dailyTrimpByDate, targetDate)

                val sr = scoringCalculator.computeStrainRatio(atl, ctl)
                val loadScore = scoringCalculator.computeLoadScore(sr)

                // Everyday-HR ATL/CTL: build the series from the persisted everyday TRIMP column, then
                // inject the current day's freshly computed value (not yet persisted). A defensive copy
                // (toMutableMap) ensures the workout-only series above is never mutated/contaminated.
                val everydayTrimpByDate =
                    TrimpDateBucketer
                        .bucket(
                            dailySummaryDao.getEverydayTrimpPoints(ctlFetchFrom, nextDayMidnightMs),
                            zoneId,
                        ).toMutableMap()
                        .apply { put(targetDate, trimpEverydayHr) }

                val ctlEverydayHr = scoringCalculator.computeCtlEmaWithDecay(everydayTrimpByDate, targetDate)
                val atlEverydayHr = scoringCalculator.computeAtlEmaWithDecay(everydayTrimpByDate, targetDate)
                val srEverydayHr = scoringCalculator.computeStrainRatio(atlEverydayHr, ctlEverydayHr)
                val loadScoreEverydayHr = scoringCalculator.computeLoadScore(srEverydayHr)

                summary =
                    summary.copy(
                        atlWorkoutOnly = atl,
                        ctlWorkoutOnly = ctl,
                        strainRatioWorkoutOnly = sr,
                        loadScoreWorkoutOnly = loadScore,
                        atlEverydayHr = atlEverydayHr,
                        ctlEverydayHr = ctlEverydayHr,
                        strainRatioEverydayHr = srEverydayHr,
                        loadScoreEverydayHr = loadScoreEverydayHr,
                    )

                val computedHrvBaseline =
                    baselineComputer.computeHrvBaselineBetween(
                        fromMs = dayMidnightMs,
                        toMs = nextDayMidnightMs,
                        hrvBaselineOverride = prefs.hrvBaselineOverride,
                        sleepDayPolicy = sleepDayPolicy,
                    )
                summary = summary.copy(hrvBaseline = computedHrvBaseline)

                if (session != null) {
                    val sleepMetricsResult =
                        computeSleepMetricsUseCase(
                            session = session,
                            dayMidnight = dayMidnight,
                            targetDate = targetDate,
                            prefs = prefs,
                            summary = summary,
                            loadScore = loadScore,
                            loadScoreEverydayHr = loadScoreEverydayHr,
                            zoneId = zoneId,
                            rhrBaselineValue = rhrBaselineValue,
                            dayEndMs = nextDayMidnightMs,
                            currentSessionIds = currentSessionIds,
                        )
                    summary = sleepMetricsResult.getOrNull() ?: summary
                }

                val hrvMuMssd =
                    if (frozenSnapshot != null) {
                        frozenSnapshot.hrvMuMssd
                    } else {
                        summary.hrvMuMssd
                    }
                val hrvSigmaMssd =
                    if (frozenSnapshot != null) {
                        frozenSnapshot.hrvSigmaMssd
                    } else {
                        summary.hrvSigmaMssd
                    }
                val rhrBpm =
                    if (frozenSnapshot != null) {
                        frozenSnapshot.rhrBpm
                    } else {
                        rhrBaselineValue
                    }
                val rhrSigma =
                    if (frozenSnapshot != null) {
                        frozenSnapshot.rhrSigma
                    } else {
                        summary.rhrSigma
                    }

                summary =
                    summary.copy(
                        hrvBaseline = computedHrvBaseline,
                        rhrBpm = rhrBpm,
                        hrvMuMssd = hrvMuMssd,
                        hrvSigmaMssd = hrvSigmaMssd,
                        rhrSigma = rhrSigma,
                        baselineCalculatedAtDate = targetDate,
                        avgSleepingSpo2 = avgSpo2,
                        hrMax = summary.hrMax ?: hrMax,
                        rasScalingFactor = summary.rasScalingFactor ?: scoringConfig.rasScalingFactor,
                        snapshotProfile = summary.snapshotProfile ?: prefs.physiologyProfile.name,
                        hrvSigmaPrior = summary.hrvSigmaPrior ?: prefs.physiologyProfile.lnSigmaPrior,
                        baselineObservationCount = summary.baselineObservationCount,
                    )

                // Final summary remains consistent with the pre-calculated dailyRas and totalRas7d.
                // Readiness adjustment is excluded from RAS storage to match standard RAS models and manual sums.

                val updatedAudit =
                    scoringConfig.auditTrail.copy(
                        appliedSf = scoringConfig.rasScalingFactor,
                        physiologyProfile = prefs.physiologyProfile.name,
                        rasTotalPre = last6DaysRasWorkoutOnly,
                        rasTotalPost = summary.totalRasWorkoutOnly,
                    )
                logD("ScoringConfig") { "Telemetry: $updatedAudit" }

                DailySummaryMapper.toDomain(summary, zoneId)
            }

        override suspend fun persist(summary: DailySummary) =
            persist(summary, settingsRepo.userPreferences.first().scoringZone())

        private suspend fun persist(
            summary: DailySummary,
            zoneId: ZoneId,
        ) {
            dailySummaryDao.upsert(DailySummaryMapper.toEntity(summary, zoneId))
        }

        override suspend fun toReadinessResult(summary: DailySummary): ReadinessResult = summary.readinessResult

        private suspend fun resolveSleepAggregation(
            targetDate: LocalDate,
            zoneId: ZoneId,
            prefs: app.readylytics.health.data.preferences.UserPreferences,
        ): SleepAggregationContext? {
            val fetchStartMs =
                targetDate
                    .minusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val fetchEndMs = targetDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val sessions = sleepSessionDao.getOverlapping(fetchStartMs, fetchEndMs)
            if (sessions.isEmpty()) return null

            val policy =
                SleepDayPolicy(
                    coreMergeGapMinutes = prefs.coreMergeGapMinutes,
                    supplementalCutoffMinutesOfDay = prefs.supplementalCutoffMinutesOfDay,
                    minimumCountedSleepSegmentMinutes = prefs.minimumCountedSleepSegmentMinutes,
                    supplementalArchitectureCoveragePercent = prefs.supplementalArchitectureCoveragePercent,
                    scoringZoneId = zoneId,
                )
            val aggregate =
                SleepDayAggregator.aggregateForScoreDay(
                    scoreDay = targetDate,
                    segments = sessions.map(::toSleepDaySegment),
                    policy = policy,
                ) ?: return null

            val coreSessionIds = aggregate.coreCluster.segments.map { it.stableId }.toSet()
            val coreSessions = sessions.filter { it.id in coreSessionIds }
            val baseSession = coreSessions.minByOrNull { it.endTime } ?: return null
            val stageTotals = aggregate.architectureTotals
            val scoringSession =
                baseSession.copy(
                    startTime = aggregate.recoveryWindow.startTimeMs,
                    endTime = aggregate.recoveryWindow.endTimeMs,
                    durationMinutes = aggregate.totalDurationMinutes,
                    efficiency = aggregateEfficiency(coreSessions),
                    deepSleepMinutes = stageTotals.deepMinutes,
                    remSleepMinutes = stageTotals.remMinutes,
                    lightSleepMinutes = stageTotals.lightMinutes,
                    awakeMinutes = stageTotals.awakeMinutes,
                )
            val allSleepIntervals =
                buildList {
                    aggregate.coreCluster.segments.forEach { add(LongInterval(it.startTimeMs, it.endTimeMs)) }
                    aggregate.supplementalBlocks.forEach { add(LongInterval(it.segment.startTimeMs, it.segment.endTimeMs)) }
                }

            return SleepAggregationContext(
                aggregate = aggregate,
                scoringSession = scoringSession,
                coreSessionIds = coreSessionIds,
                allSleepIntervals = allSleepIntervals,
            )
        }

        private fun toSleepDaySegment(session: SleepSessionEntity): SleepDaySegment {
            // HC-006: defensive guard for sessions already persisted with durationMinutes = 0 by the
            // pre-fix stage-less-session mapping (SleepDataMapper's raw-span fallback only applies to
            // freshly ingested sessions). Without this, a recompute-only pass -- which never re-reads
            // Health Connect -- would still see the old stored 0 and SleepDaySegment's
            // `durationMinutes > 0` invariant would throw.
            val durationMinutes =
                if (session.durationMinutes > 0) {
                    session.durationMinutes
                } else {
                    ((session.endTime - session.startTime) / 60_000L).toInt()
                }
            return SleepDaySegment(
                stableId = session.id,
                startTimeMs = session.startTime,
                endTimeMs = session.endTime,
                durationMinutes = durationMinutes,
                lightSleepMinutes = session.lightSleepMinutes,
                deepSleepMinutes = session.deepSleepMinutes,
                remSleepMinutes = session.remSleepMinutes,
                awakeMinutes = session.awakeMinutes,
                efficiency = session.efficiency,
                startZoneOffsetSeconds = session.startZoneOffsetSeconds,
                endZoneOffsetSeconds = session.endZoneOffsetSeconds,
                sourcePackageName = session.deviceName,
            )
        }

        private fun aggregateEfficiency(coreSessions: List<SleepSessionEntity>): Float {
            val weightedSessions = coreSessions.filter { it.durationMinutes > 0 }
            if (weightedSessions.isEmpty()) return 0f

            val numerator =
                weightedSessions.sumOf { session ->
                    session.efficiency.toDouble() * session.durationMinutes.toDouble()
                }
            val denominator = weightedSessions.sumOf { it.durationMinutes }.toDouble()
            return if (denominator > 0.0) {
                (numerator / denominator).toFloat()
            } else {
                weightedSessions.first().efficiency
            }
        }

        private data class SleepAggregationContext(
            val aggregate: SleepDayAggregate,
            val scoringSession: SleepSessionEntity,
            val coreSessionIds: Set<String>,
            val allSleepIntervals: List<LongInterval>,
        )

        private suspend fun sumRasLastSixDays(
            targetDate: LocalDate,
            zoneId: ZoneId,
            selector: (DailySummaryEntity) -> Float?,
        ): Float {
            val previousDaysMs =
                (1..6).map { i ->
                    targetDate
                        .minusDays(i.toLong())
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli()
                }
            val summaries = dailySummaryDao.getByDates(previousDaysMs)
            return summaries.mapNotNull(selector).sum()
        }
    }
