package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.util.mean
import com.gregor.lauritz.healthdashboard.domain.util.median
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class ScoringRepository
    @Inject
    constructor(
        private val workoutDao: WorkoutDao,
        private val sleepSessionDao: SleepSessionDao,
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val dailySummaryDao: DailySummaryDao,
        private val prefsRepo: UserPreferencesRepository,
    ) {
        suspend fun computeAndPersistDailySummary(targetDate: LocalDate = LocalDate.now()) {
            val zoneId = ZoneId.systemDefault()
            val dayMidnight = targetDate.atStartOfDay(zoneId).toInstant()
            val nextDayMidnight = targetDate.plusDays(1).atStartOfDay(zoneId).toInstant()
            val dayMidnightMs = dayMidnight.toEpochMilli()
            val nextDayMidnightMs = nextDayMidnight.toEpochMilli()

            val prefs = prefsRepo.userPreferences.first()
            val hrMax = if (prefs.autoCalculateMaxHr) (206.9f - (0.67f * prefs.age)) else prefs.maxHeartRate.toFloat()

            val rhrBaselineFrom = dayMidnight.minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS).toEpochMilli()
            val rhrBaselineValues = heartRateDao.getAvgSleepHrPerSession(rhrBaselineFrom)
            val rhrBaselineValue = prefs.rhrBaselineOverride
                ?: rhrBaselineValues.median().toFloat().takeIf { it > 0f }
                ?: 60f

            android.util.Log.d("ScoringRepository", "PAI CALC START [$targetDate]")

            // Calculate Daily PAI points from workouts (no sleep calibration needed)
            val totalDuration = workoutDao.getTotalDurationMinutes(dayMidnightMs, nextDayMidnightMs) ?: 0
            val weightedAvgHr = workoutDao.getWeightedAvgHr(dayMidnightMs, nextDayMidnightMs) ?: 0f

            android.util.Log.d("ScoringRepository", "Workout Data - Duration: $totalDuration, WeightedAvgHr: $weightedAvgHr, RHR Baseline: $rhrBaselineValue, HR Max: $hrMax")

            val dailyTrimpRaw = PaiCalculator.calculateDailyTrimp(
                durationMinutes = totalDuration.toFloat(),
                hrAvg = weightedAvgHr,
                rhrBaseline = rhrBaselineValue,
                hrMax = hrMax,
                gender = prefs.gender
            )
            val dailyPaiRaw = PaiCalculator.calculateDailyPai(dailyTrimpRaw, prefs.paiScalingFactor)

            android.util.Log.d("ScoringRepository", "Result - DailyTrimpRaw: $dailyTrimpRaw, DailyPaiRaw: $dailyPaiRaw")

            var summary = (dailySummaryDao.getByDate(dayMidnightMs) ?: DailySummaryEntity(dateMidnightMs = dayMidnightMs))
                .copy(paiScore = dailyPaiRaw)

            // Sleep calibration check — guard strain/sleep/readiness metrics only
            val calibrationFrom = dayMidnight.minus(ScoringConstants.CHRONIC_DAYS, ChronoUnit.DAYS).toEpochMilli()
            val isCalibrated = sleepSessionDao.countSince(calibrationFrom) >= ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION

            if (!isCalibrated) {
                // Persist PAI-only partial summary and skip sleep/strain scoring
                val zoneIdSystem = ZoneId.systemDefault()
                val previousDays = (1..6).map { i ->
                    targetDate.minusDays(i.toLong()).atStartOfDay(zoneIdSystem).toInstant().toEpochMilli()
                        .let { dailySummaryDao.getByDate(it)?.paiScore ?: 0f }
                }
                val totalPaiSoFar = previousDays.sum()
                val finalDailyPai = PaiCalculator.applyAccumulationMultiplier(dailyPaiRaw, totalPaiSoFar)
                summary = summary.copy(paiScore = finalDailyPai, totalPai = totalPaiSoFar + finalDailyPai)
                android.util.Log.d("ScoringRepository", "PAI FINAL (uncalibrated) - FinalDaily: $finalDailyPai, Total: ${totalPaiSoFar + finalDailyPai}")
                dailySummaryDao.upsert(summary)
                return
            }

            val tzOffsetMs = zoneId.rules.getOffset(dayMidnight).totalSeconds * 1000L
            val ctlFetchFrom = dayMidnight.minus(ScoringConstants.CHRONIC_DAYS - 1, ChronoUnit.DAYS).toEpochMilli()
            val dailyTrimpList = workoutDao.getDailyTrimp(ctlFetchFrom, nextDayMidnightMs, tzOffsetMs)

            val ctl = ScoringCalculator.computeCtlEma(dailyTrimpList = dailyTrimpList)
            val acuteFrom = dayMidnight.minus(ScoringConstants.ACUTE_DAYS - 1, ChronoUnit.DAYS).toEpochMilli()
            val acuteTotal = workoutDao.getTotalTrimp(acuteFrom, nextDayMidnightMs) ?: 0f
            val atl = acuteTotal / ScoringConstants.ACUTE_DAYS.toFloat()

            val sr = ScoringCalculator.computeStrainRatio(atl, ctl)
            val loadScore = ScoringCalculator.computeLoadScore(sr)
            val todayTrimp = workoutDao.getTotalTrimp(dayMidnightMs, nextDayMidnightMs) ?: 0f

            summary = summary.copy(loadScore = loadScore, strainRatio = sr, totalTrimp = todayTrimp)

            val session = sleepSessionDao.getSessionEndingInRange(dayMidnightMs, nextDayMidnightMs)
            if (session != null) {
                summary = calculateSleepMetrics(session, dayMidnight, prefs, summary, loadScore)
            }

            // After readiness is calculated, adjust PAI and calculate rolling sum
            val adjustedDailyPai = PaiCalculator.adjustForReadiness(summary.paiScore ?: 0f, summary.readinessScore)

            val zoneIdSystem = ZoneId.systemDefault()
            val previousDays = (1..6).map { i ->
                targetDate.minusDays(i.toLong()).atStartOfDay(zoneIdSystem).toInstant().toEpochMilli()
                    .let { dailySummaryDao.getByDate(it)?.paiScore ?: 0f }
            }

            val totalPaiSoFar = previousDays.sum()
            val finalDailyPai = PaiCalculator.applyAccumulationMultiplier(adjustedDailyPai, totalPaiSoFar)

            summary = summary.copy(
                paiScore = finalDailyPai,
                totalPai = totalPaiSoFar + finalDailyPai
            )

            android.util.Log.d("ScoringRepository", "PAI FINAL - Adjusted: $adjustedDailyPai, PrevTotal: $totalPaiSoFar, FinalDaily: $finalDailyPai, Total: ${totalPaiSoFar + finalDailyPai}")

            dailySummaryDao.upsert(summary)
        }

        private suspend fun calculateSleepMetrics(
            session: SleepSessionEntity,
            dayMidnight: Instant,
            prefs: UserPreferences,
            summary: DailySummaryEntity,
            loadScore: Float
        ): DailySummaryEntity {
            val baselineFrom = dayMidnight.minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS).toEpochMilli()
            val hrvValues = hrvDao.getSleepRmssdValues(baselineFrom)
            val rhrValues = heartRateDao.getAvgSleepHrPerSession(baselineFrom)
            val sessionHrvSamples = hrvDao.getSleepRmssdForSession(session.id)
            val currentHrvMean = sessionHrvSamples.mean()
            val currentNocturnalRhr = heartRateDao.getAvgSleepHr(session.id)

            val hrvBaseline = (prefs.hrvBaselineOverride ?: hrvValues.median()).roundToInt()
            val baselineRhrValue = (prefs.rhrBaselineOverride ?: rhrValues.median()).roundToInt()

            val beforeMs = prefs.restingHrBeforeMinutes * 60 * 1000L
            val afterMs = prefs.restingHrAfterMinutes * 60 * 1000L
            val minHrStartTime = session.endTime - beforeMs
            val minHrEndTime = session.endTime + afterMs
            val currentRestingHr = heartRateDao.getMinHrInRange(minHrStartTime, minHrEndTime)

            val sessions = sleepSessionDao.getSince(baselineFrom)
            // Batch-fetch all HR records covering every session's wake window in one query
            // instead of N individual getMinHrInRange calls.
            val batchWindowStart = (sessions.minOfOrNull { it.endTime } ?: session.endTime) - beforeMs
            val batchWindowEnd = (sessions.maxOfOrNull { it.endTime } ?: session.endTime) + afterMs
            val allWakeHrRecords = heartRateDao.getByTimeRange(batchWindowStart, batchWindowEnd)

            val historicRestingHrs = sessions.filter { it.id != session.id }.mapNotNull { s ->
                val start = s.endTime - beforeMs
                val end = s.endTime + afterMs
                allWakeHrRecords
                    .filter { it.timestampMs in start..end }
                    .minOfOrNull { it.beatsPerMinute }
            }
            val restingHrBaseline = if (historicRestingHrs.isNotEmpty()) historicRestingHrs.median().roundToInt() else null
            val restingHrRatio = if (currentRestingHr != null && restingHrBaseline != null && restingHrBaseline > 0) {
                currentRestingHr.toFloat() / restingHrBaseline
            } else null

            var rhrRatio: Float? = null
            var sleepScore: Float? = null
            var readinessScore: Float? = null

            if (currentNocturnalRhr != null) {
                rhrRatio = currentNocturnalRhr.toFloat() / (baselineRhrValue + 0.001f)

                val minHrTimestamp = heartRateDao.getMinHrTimestamp(session.id)
                val isLateNadir = minHrTimestamp != null && session.durationMinutes > 0 &&
                    (minHrTimestamp - session.startTime) > (session.durationMinutes * 60 * 1000L * ScoringConstants.Restoration.LATE_NADIR_THRESHOLD)

                var sRest = ScoringCalculator.computeRestorationSubScore(
                    currentHrvMean = currentHrvMean,
                    hrvValues = hrvValues,
                    currentNocturnalRhr = currentNocturnalRhr.toFloat(),
                    rhrValues = rhrValues,
                    rhrBaselineOverride = prefs.rhrBaselineOverride,
                    hrvBaselineOverride = prefs.hrvBaselineOverride
                )

                if (isLateNadir) {
                    sRest *= ScoringConstants.Restoration.LATE_NADIR_PENALTY
                }

                sleepScore = ScoringCalculator.computeSleepScore(
                    durationMinutes = session.durationMinutes,
                    efficiency = session.efficiency,
                    deepSleepMinutes = session.deepSleepMinutes,
                    remSleepMinutes = session.remSleepMinutes,
                    goalSleepHours = prefs.goalSleepHours,
                    sRest = sRest
                )

                val zHrv = if (sessionHrvSamples.isNotEmpty() && hrvValues.isNotEmpty()) {
                    val mu = prefs.hrvBaselineOverride ?: hrvValues.mean()
                    (currentHrvMean - mu) / ScoringCalculator.hrvSigma(hrvValues)
                } else null

                readinessScore = ScoringCalculator.computeReadinessScore(
                    sRest = sRest,
                    sleepScore = sleepScore,
                    loadScore = loadScore,
                    zHrv = zHrv,
                    rhrRatio = rhrRatio
                )
            }

            return summary.copy(
                sleepScore = sleepScore,
                readinessScore = readinessScore,
                nocturnalRhr = currentNocturnalRhr,
                nocturnalHrv = if (sessionHrvSamples.isNotEmpty()) currentHrvMean.roundToInt() else null,
                sleepDurationMinutes = session.durationMinutes,
                deepSleepPercent = if (session.durationMinutes > 0) session.deepSleepMinutes / session.durationMinutes.toFloat() * 100f else null,
                remSleepPercent = if (session.durationMinutes > 0) session.remSleepMinutes / session.durationMinutes.toFloat() * 100f else null,
                rhrRatio = rhrRatio,
                hrvBaseline = hrvBaseline,
                restingHeartRate = currentRestingHr,
                restingHrRatio = restingHrRatio,
                restingHrBaseline = restingHrBaseline
            )
        }
    }
