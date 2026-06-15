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
import app.readylytics.health.domain.scoring.PaiCalculator
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
                val frozenPaiScalingFactor = frozenSnapshot?.paiScalingFactor
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

                logD("ScoringRepository") { "PAI CALC START [$targetDate]" }

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
                    if (session != null) listOf(LongRange(session.startTime, session.endTime)) else emptyList()
                val workoutIntervalsMs = workouts.map { LongRange(it.startTime, it.endTime) }
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

                // Enforce 75-point daily cap. Standard PAI is pure load, no readiness penalty.
                // Round daily PAI to 1 decimal place to ensure display consistency.
                val scalingFactor = frozenPaiScalingFactor ?: scoringConfig.paiScalingFactor
                val dailyPaiRaw = PaiCalculator.calculateDailyPai(dailyTrimpRaw, scalingFactor)
                val dailyPai = round(dailyPaiRaw * 10f) / 10f
                val dailyPaiEverydayHr =
                    round(PaiCalculator.calculateDailyPai(trimpEverydayHr, scalingFactor) * 10f) / 10f

                val last6DaysPaiWorkoutOnly = sumPaiLastSixDays(targetDate, zoneId) { it.paiWorkoutOnly }
                val last6DaysPaiEverydayHr = sumPaiLastSixDays(targetDate, zoneId) { it.paiEverydayHr }
                // Total PAI is rounded to nearest integer to match the UI's simple sum of rounded daily values.
                val totalPaiWorkoutOnly = round(dailyPai + last6DaysPaiWorkoutOnly)
                val totalPaiEverydayHr = round(dailyPaiEverydayHr + last6DaysPaiEverydayHr)

                logD("ScoringRepository") {
                    "Result - DailyTrimp: $dailyTrimpRaw, DailyPai: $dailyPai, " +
                        "Last6d: $last6DaysPaiWorkoutOnly, Total7d: $totalPaiWorkoutOnly"
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
                        paiWorkoutOnly = dailyPai,
                        paiEverydayHr = dailyPaiEverydayHr,
                        totalPaiWorkoutOnly = totalPaiWorkoutOnly,
                        totalPaiEverydayHr = totalPaiEverydayHr,
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
                            appliedSf = scoringConfig.paiScalingFactor,
                            physiologyProfile = prefs.physiologyProfile.name,
                            paiTotalPre = last6DaysPaiWorkoutOnly,
                            paiTotalPost = summary.totalPaiWorkoutOnly,
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

                summary =
                    summary.copy(
                        hrvBaseline = computedHrvBaseline,
                        rhrBpm = rhrBaselineValue,
                        hrvMuMssd = hrvMuMssd,
                        baselineCalculatedAtDate = targetDate,
                        avgSleepingSpo2 = avgSpo2,
                        hrMax = summary.hrMax ?: hrMax,
                        paiScalingFactor = summary.paiScalingFactor ?: scoringConfig.paiScalingFactor,
                        snapshotProfile = summary.snapshotProfile ?: prefs.physiologyProfile.name,
                        hrvSigmaPrior = summary.hrvSigmaPrior ?: prefs.physiologyProfile.lnSigmaPrior,
                        baselineObservationCount = summary.baselineObservationCount,
                    )

                // Final summary remains consistent with the pre-calculated dailyPai and totalPai7d.
                // Readiness adjustment is excluded from PAI storage to match standard PAI models and manual sums.

                val updatedAudit =
                    scoringConfig.auditTrail.copy(
                        appliedSf = scoringConfig.paiScalingFactor,
                        physiologyProfile = prefs.physiologyProfile.name,
                        paiTotalPre = last6DaysPaiWorkoutOnly,
                        paiTotalPost = summary.totalPaiWorkoutOnly,
                    )
                logD("ScoringConfig") { "Telemetry: $updatedAudit" }

                summary
            }

        override fun toReadinessResult(summary: DailySummaryEntity): ReadinessResult {
            val flags: Set<RecoveryFlag> =
                summary.recoveryFlags
                    ?.split(',')
                    ?.mapNotNull { token ->
                        runCatching { RecoveryFlag.valueOf(token.trim()) }.getOrNull()
                    }?.toSet()
                    ?: emptySet()
            return ReadinessResult(
                readinessScore = summary.readinessScore,
                sleepScore = summary.sleepScore,
                loadScore = summary.loadScore,
                sRest = summary.sRest,
                recoveryFlags = flags,
                contributors = summary.contributors,
                diagnostics = summary.diagnostics,
            )
        }

        private suspend fun sumPaiLastSixDays(
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
