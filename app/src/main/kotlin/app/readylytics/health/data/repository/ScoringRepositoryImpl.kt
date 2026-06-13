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
import app.readylytics.health.domain.model.ReadinessResult
import app.readylytics.health.domain.model.RecordType
import app.readylytics.health.domain.model.RecoveryFlag
import app.readylytics.health.domain.model.getOrNull
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.scoring.BaselineComputer
import app.readylytics.health.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.domain.scoring.PaiCalculator
import app.readylytics.health.domain.scoring.ScoringCalculator
import app.readylytics.health.domain.scoring.ScoringConfigFactory
import app.readylytics.health.domain.scoring.ScoringConstants
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
                val zoneId = ZoneId.systemDefault()
                val dayMidnight = targetDate.atStartOfDay(zoneId).toInstant()
                val nextDayMidnight = targetDate.plusDays(1).atStartOfDay(zoneId).toInstant()
                val dayMidnightMs = dayMidnight.toEpochMilli()
                val nextDayMidnightMs = nextDayMidnight.toEpochMilli()

                val prefs = settingsRepo.userPreferences.first()

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

                // All-Day HR Strain Track (Track B): integrate TRIMP over the continuous
                // non-sleep HR stream for the day, independent of workout boundaries.
                val allDaySamples =
                    heartRateDao
                        .getByTimeRange(dayMidnightMs, nextDayMidnightMs)
                        .filter { it.recordType != RecordType.SLEEP.name }
                        .sortedBy { it.timestampMs }
                        .map { sample ->
                            ComputeWorkoutTrimpUseCase.HeartRateSample(
                                Instant.ofEpochMilli(sample.timestampMs),
                                sample.beatsPerMinute,
                            )
                        }
                val dailyHrTrimpRaw =
                    PaiCalculator.calculateAllDayTrimp(
                        samples = allDaySamples,
                        hrMax = hrMax,
                        rhrBaseline = rhrBaselineValue,
                        gender = prefs.gender,
                        trimpModel = prefs.trimpModel,
                        banisterMultiplier = prefs.banisterMultiplier,
                        chengBeta = prefs.chengBeta,
                        itrimB = prefs.itrimB,
                        ltBpm = prefs.zone3MaxBpm.toFloat(),
                    )
                val dailyHrPai =
                    round(
                        PaiCalculator.calculateDailyPai(
                            dailyHrTrimpRaw,
                            frozenPaiScalingFactor ?: scoringConfig.paiScalingFactor,
                        ) * 10f,
                    ) / 10f

                // Enforce 75-point daily cap. Standard PAI is pure load, no readiness penalty.
                // Round daily PAI to 1 decimal place to ensure display consistency.
                val dailyPaiRaw =
                    PaiCalculator.calculateDailyPai(
                        dailyTrimpRaw,
                        frozenPaiScalingFactor ?: scoringConfig.paiScalingFactor,
                    )
                val dailyPai = round(dailyPaiRaw * 10f) / 10f

                val last6DaysPai = sumPaiScoreLastSixDays(targetDate)
                // Total PAI is rounded to nearest integer to match the UI's simple sum of rounded daily values.
                val totalPai7d = round(dailyPai + last6DaysPai)

                logD("ScoringRepository") {
                    "Result - DailyTrimp: $dailyTrimpRaw, DailyPai: $dailyPai, Last6d: $last6DaysPai, Total7d: $totalPai7d"
                }

                val latestWeight = weightRecordDao.getLatestUpTo(nextDayMidnightMs)
                val latestBodyFat = bodyFatRecordDao.getLatestUpTo(nextDayMidnightMs)
                val latestBP = bloodPressureRecordDao.getLatestUpTo(nextDayMidnightMs)

                var summary =
                    (
                        dailySummaryDao.getByDate(dayMidnightMs)
                            ?: DailySummaryEntity(dateMidnightMs = dayMidnightMs)
                    ).copy(
                        paiScore = dailyPai,
                        totalPai = totalPai7d,
                        dailyHrTrimp = dailyHrTrimpRaw,
                        dailyHrPai = dailyHrPai,
                        weightKg = latestWeight?.weightKg,
                        bodyFatPercent = latestBodyFat?.bodyFatPercent,
                        bloodPressureSystolic = latestBP?.systolicMmHg,
                        bloodPressureDiastolic = latestBP?.diastolicMmHg,
                    )

                val calibrationFrom = dayMidnight.minus(ScoringConstants.CHRONIC_DAYS, ChronoUnit.DAYS).toEpochMilli()
                val isCalibrated =
                    sleepSessionDao.countSince(calibrationFrom) >= ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION

                val session = sleepSessionDao.getSessionEndingInRange(dayMidnightMs, nextDayMidnightMs)
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
                            )
                    } else {
                        summary = summary.copy(isCalibrating = true, avgSleepingSpo2 = avgSpo2)
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
                            paiTotalPre = last6DaysPai,
                            paiTotalPost = summary.totalPai,
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

                summary = summary.copy(loadScore = loadScore, strainRatio = sr, totalTrimp = dailyTrimpRaw)

                // Track B (All-Day HR) ATL/CTL/Load: history comes from previously persisted
                // dailyHrTrimp values, with today's freshly computed value layered on top.
                val dailyHrTrimpHistory = dailySummaryDao.getDailyHrTrimpSince(ctlFetchFrom, nextDayMidnightMs)
                val dailyHrTrimpByDate =
                    dailyHrTrimpHistory
                        .associate { row ->
                            Instant.ofEpochMilli(row.dateMidnightMs).atZone(zoneId).toLocalDate() to (row.dailyHrTrimp ?: 0f)
                        }.toMutableMap()
                dailyHrTrimpByDate[targetDate] = dailyHrTrimpRaw

                val ctlHr = scoringCalculator.computeCtlEmaWithDecay(dailyHrTrimpByDate, targetDate)
                val atlHr = scoringCalculator.computeAtlEmaWithDecay(dailyHrTrimpByDate, targetDate)
                val srHr = scoringCalculator.computeStrainRatio(atlHr, ctlHr)
                val loadScoreHr = scoringCalculator.computeLoadScore(srHr)

                summary = summary.copy(dailyHrAtl = atlHr, dailyHrCtl = ctlHr, dailyHrLoadScore = loadScoreHr)

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
                            zoneId = zoneId,
                            rhrBaselineValue = rhrBaselineValue,
                            dayEndMs = nextDayMidnightMs,
                        )
                    summary = sleepMetricsResult.getOrNull() ?: summary
                }

                // Track B readiness reuses the same sRest/sleepScore/recovery flags as Track A
                // (no all-day variant of HRV/RHR/sleep architecture exists); only loadScore differs.
                val recoveryFlagsForHr =
                    summary.recoveryFlags
                        ?.split(',')
                        ?.mapNotNull { token -> runCatching { RecoveryFlag.valueOf(token.trim()) }.getOrNull() }
                        ?.toSet()
                        ?: emptySet()
                val readinessScoreHr =
                    scoringCalculator.computeReadinessScore(
                        sRest = summary.sRest ?: 0f,
                        sleepScore = summary.sleepScore ?: 0f,
                        loadScore = loadScoreHr,
                        recoveryFlags = recoveryFlagsForHr,
                    )
                summary = summary.copy(dailyHrReadinessScore = readinessScoreHr)

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
                        paiTotalPre = last6DaysPai,
                        paiTotalPost = summary.totalPai,
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

        private suspend fun sumPaiScoreLastSixDays(targetDate: LocalDate): Float {
            val zoneIdSystem = ZoneId.systemDefault()
            val previousDaysMs =
                (1..6).map { i ->
                    targetDate
                        .minusDays(i.toLong())
                        .atStartOfDay(zoneIdSystem)
                        .toInstant()
                        .toEpochMilli()
                }
            val summaries = dailySummaryDao.getByDates(previousDaysMs)
            return summaries.mapNotNull { it.paiScore }.sum()
        }
    }
