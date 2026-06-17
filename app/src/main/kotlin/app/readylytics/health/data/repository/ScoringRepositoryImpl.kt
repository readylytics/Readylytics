package app.readylytics.health.data.repository

import app.readylytics.health.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.data.local.dao.BodyFatRecordDao
import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.dao.WeightRecordDao
import app.readylytics.health.data.local.dao.WorkoutDao
import app.readylytics.health.data.local.entity.DailySummaryEntity
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.scoringZone
import app.readylytics.health.domain.model.DailySummaryMapper
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.domain.model.ReadinessResult
import app.readylytics.health.domain.model.RecordType
import app.readylytics.health.domain.model.RecoveryFlag
import app.readylytics.health.domain.model.getOrNull
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
import app.readylytics.health.domain.scoring.components.Phase
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
        private val hrvDao: HrvDao,
        private val weightRecordDao: WeightRecordDao,
        private val bodyFatRecordDao: BodyFatRecordDao,
        private val bloodPressureRecordDao: BloodPressureRecordDao,
        private val oxygenSaturationRecordDao: OxygenSaturationRecordDao,
        private val sleepPercentileRhrCalculator: SleepPercentileRhrCalculator,
    ) : ScoringRepository {
        private val calculationMutex = Mutex()

        override suspend fun computeAndPersistDailySummary(targetDate: LocalDate) {
            calculationMutex.withLock {
                val summary = computeDailySummary(targetDate)
                dailySummaryDao.upsert(summary)
            }
        }

        override suspend fun computeDailySummary(targetDate: LocalDate): DailySummaryEntity =
            withContext(Dispatchers.Default) {
                val prefs = settingsRepo.userPreferences.first()
                val zoneId = prefs.scoringZone()
                val dayMidnight = targetDate.atStartOfDay(zoneId).toInstant()
                val nextDayMidnight = targetDate.plusDays(1).atStartOfDay(zoneId).toInstant()
                val dayMidnightMs = dayMidnight.toEpochMilli()
                val nextDayMidnightMs = nextDayMidnight.toEpochMilli()

                // Retrieve the nightly frozen HR_rest (nocturnal floor) from the daily summary if available
                val dailySummary = dailySummaryDao.getByDate(dayMidnightMs)

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
                var dailyTrimpRaw = 0f

                workouts.forEach { workout ->
                    val workoutHrSamples =
                        heartRateDao
                            .getByTimeRange(workout.startTime, workout.endTime)
                            .filter { it.recordType == RecordType.EXERCISE.name }
                            .sortedBy { it.timestampMs }

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
                }

                // Sleep session is fetched here (before the everyday-HR block) because it provides
                // the sleep interval(s) that the everyday-HR load calculator must exclude.
                val session = sleepSessionDao.getSessionEndingInRange(dayMidnightMs, nextDayMidnightMs)

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
                    if (session != null) listOf(LongInterval(session.startTime, session.endTime)) else emptyList()
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

                logD("ScoringRepository") {
                    "Result - DailyTrimp: $dailyTrimpRaw, DailyRas: $dailyRas, " +
                        "Last6d: $last6DaysRasWorkoutOnly, Total7d: $totalRasWorkoutOnly"
                }

                val latestWeight = weightRecordDao.getLatestUpTo(nextDayMidnightMs)
                val latestBodyFat = bodyFatRecordDao.getLatestUpTo(nextDayMidnightMs)
                val latestBP = bloodPressureRecordDao.getLatestUpTo(nextDayMidnightMs)

                var summary =
                    (
                        dailySummaryDao.getByDate(dayMidnightMs)
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
                    )

                val calibrationFrom = dayMidnight.minus(ScoringConstants.CHRONIC_DAYS, ChronoUnit.DAYS).toEpochMilli()
                val isCalibrated =
                    sleepSessionDao.countSince(calibrationFrom) >= ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION

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
                        val hrvValues = hrvDao.getSleepRmssdForSession(session.id)
                        val avgHrv =
                            if (hrvValues.isNotEmpty()) {
                                (hrvValues.sum() / hrvValues.size).toInt()
                            } else {
                                null
                            }
                        val sleepHrSamples = heartRateDao.getSleepHrSamplesForSession(session.id)
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
                        )

                    // Calibration bypasses computeSleepMetricsUseCase; collect directly to populate restingHrBaseline + rhrRatio
                    val rhrWakeResult =
                        if (session != null) {
                            sleepPercentileRhrCalculator.collect(
                                session = session,
                                dayMidnight = dayMidnight,
                                percentile = prefs.restingHrPercentile,
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

                    return@withContext summary
                }

                val tzOffsetMs = zoneId.rules.getOffset(dayMidnight).totalSeconds * 1000L
                val ctlFetchFrom = dayMidnight.minus(ScoringConstants.CHRONIC_DAYS * 2, ChronoUnit.DAYS).toEpochMilli()
                val epochDayToTrimp = workoutDao.getDailyTrmpByEpochDay(ctlFetchFrom, nextDayMidnightMs, tzOffsetMs)
                val dailyTrimpByDate = epochDayToTrimp.mapKeys { (epochDay, _) -> LocalDate.ofEpochDay(epochDay) }

                val ctl = scoringCalculator.computeCtlEmaWithDecay(dailyTrimpByDate, targetDate)
                val atl = scoringCalculator.computeAtlEmaWithDecay(dailyTrimpByDate, targetDate)

                val sr = scoringCalculator.computeStrainRatio(atl, ctl)
                val loadScore = scoringCalculator.computeLoadScore(sr)

                // Everyday-HR ATL/CTL: build the series from the persisted everyday TRIMP column, then
                // inject the current day's freshly computed value (not yet persisted). A defensive copy
                // (toMutableMap) ensures the workout-only series above is never mutated/contaminated.
                val epochDayToTrimpEveryday =
                    dailySummaryDao.getEverydayTrimpByEpochDay(ctlFetchFrom, nextDayMidnightMs, tzOffsetMs)
                val everydayTrimpByDate =
                    epochDayToTrimpEveryday
                        .mapKeys { (epochDay, _) -> LocalDate.ofEpochDay(epochDay) }
                        .toMutableMap()
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

                summary
            }

        override suspend fun toReadinessResult(summary: DailySummaryEntity): ReadinessResult {
            val flags: Set<RecoveryFlag> =
                summary.recoveryFlags
                    ?.split(',')
                    ?.mapNotNull { token ->
                        runCatching { RecoveryFlag.valueOf(token.trim()) }.getOrNull()
                    }?.toSet()
                    ?: emptySet()
            val prefs = settingsRepo.userPreferences.first()
            val mode = prefs.strainLoadSourceMode
            val domainSummary = DailySummaryMapper.toDomain(summary, prefs.scoringZone())
            return ReadinessResult(
                readinessScore = LoadSourceSelector.selectReadiness(domainSummary, mode),
                sleepScore = summary.sleepScore,
                loadScore = LoadSourceSelector.selectLoadScore(domainSummary, mode),
                sRest = summary.sRest,
                recoveryFlags = flags,
                contributors = summary.contributors,
                diagnostics = summary.diagnostics,
            )
        }

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
