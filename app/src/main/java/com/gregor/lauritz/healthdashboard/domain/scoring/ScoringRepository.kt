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
import com.gregor.lauritz.healthdashboard.domain.util.logD
import com.gregor.lauritz.healthdashboard.domain.util.mean
import com.gregor.lauritz.healthdashboard.domain.util.median
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
            val summary = computeDailySummary(targetDate)
            dailySummaryDao.upsert(summary)
        }

        suspend fun computeDailySummary(targetDate: LocalDate = LocalDate.now()): DailySummaryEntity =
            withContext(Dispatchers.Default) {
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
                    ?: rhrBaselineValues.median().takeIf { it > 0f }
                    ?: 60f

                logD("ScoringRepository") { "PAI CALC START [$targetDate]" }

                // Calculate Daily PAI points from workouts (no sleep calibration needed)
                val totalDuration = workoutDao.getTotalDurationMinutes(dayMidnightMs, nextDayMidnightMs) ?: 0
                val weightedAvgHr = workoutDao.getWeightedAvgHr(dayMidnightMs, nextDayMidnightMs) ?: 0f

                logD("ScoringRepository") { "Workout Data - Duration: $totalDuration, WeightedAvgHr: $weightedAvgHr, RHR Baseline: $rhrBaselineValue, HR Max: $hrMax" }

                val dailyTrimpRaw = PaiCalculator.calculateDailyTrimp(
                    durationMinutes = totalDuration.toFloat(),
                    hrAvg = weightedAvgHr,
                    rhrBaseline = rhrBaselineValue,
                    hrMax = hrMax,
                    gender = prefs.gender
                )
                val dailyPaiRaw = PaiCalculator.calculateDailyPai(dailyTrimpRaw, prefs.paiScalingFactor)

                logD("ScoringRepository") { "Result - DailyTrimpRaw: $dailyTrimpRaw, DailyPaiRaw: $dailyPaiRaw" }

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
                    logD("ScoringRepository") { "PAI FINAL (uncalibrated) - FinalDaily: $finalDailyPai, Total: ${totalPaiSoFar + finalDailyPai}" }
                    return@withContext summary
                }

                val tzOffsetMs = zoneId.rules.getOffset(dayMidnight).totalSeconds * 1000L
                val ctlFetchFrom = dayMidnight.minus(ScoringConstants.CHRONIC_DAYS - 1, ChronoUnit.DAYS).toEpochMilli()
                val dailyTrimpList = workoutDao.getDailyTrimp(ctlFetchFrom, nextDayMidnightMs, tzOffsetMs)

                val ctl = ScoringCalculator.computeCtlEma(dailyTrimpList = dailyTrimpList)
                val acuteFrom = dayMidnight.minus(ScoringConstants.ACUTE_DAYS - 1, ChronoUnit.DAYS).toEpochMilli()
                val acuteTotal = workoutDao.getTotalTrimp(acuteFrom, nextDayMidnightMs) ?: 0f
                // Seed ATL = CTL during cold-start so SR initialises at 1.0 — REF: A.15 review
                val atl = if (dailyTrimpList.size < ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION) ctl
                          else acuteTotal / ScoringConstants.ACUTE_DAYS.toFloat()

                val sr = ScoringCalculator.computeStrainRatio(atl, ctl)
                val loadScore = ScoringCalculator.computeLoadScore(sr)
                val todayTrimp = workoutDao.getTotalTrimp(dayMidnightMs, nextDayMidnightMs) ?: 0f

                summary = summary.copy(loadScore = loadScore, strainRatio = sr, totalTrimp = todayTrimp)

                // Always compute and persist HRV baseline regardless of sleep session availability
                val baselineFromMs = dayMidnight.minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS).toEpochMilli()
                val hrvBaselineValues = hrvDao.getSleepRmssdValues(baselineFromMs)
                val computedHrvBaseline = (prefs.hrvBaselineOverride
                    ?: hrvBaselineValues.median().takeIf { it > 0f })
                    ?.roundToInt()
                summary = summary.copy(hrvBaseline = computedHrvBaseline)

                val session = sleepSessionDao.getSessionEndingInRange(dayMidnightMs, nextDayMidnightMs)
                if (session != null) {
                    summary = calculateSleepMetrics(session, dayMidnight, targetDate, prefs, summary, loadScore, zoneId)
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

                logD("ScoringRepository") { "PAI FINAL - Adjusted: $adjustedDailyPai, PrevTotal: $totalPaiSoFar, FinalDaily: $finalDailyPai, Total: ${totalPaiSoFar + finalDailyPai}" }

                summary
            }

        private suspend fun calculateSleepMetrics(
            session: SleepSessionEntity,
            dayMidnight: Instant,
            targetDate: LocalDate,
            prefs: UserPreferences,
            summary: DailySummaryEntity,
            loadScore: Float,
            zoneId: ZoneId,
        ): DailySummaryEntity = withContext(Dispatchers.Default) {
            val baselineFrom = dayMidnight.minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS).toEpochMilli()
            val rhrValues = heartRateDao.getAvgSleepHrPerSession(baselineFrom)

            // Fetch yesterday's Z-scores for 2-night consecutive flag confirmation
            val yesterdayMidnightMs = targetDate.minusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val yesterdaySummary = dailySummaryDao.getByDate(yesterdayMidnightMs)

            // Separate mu (7-night) and sigma (56-night) HRV history windows
            val sigmaWindowFromMs = dayMidnight.minus(ScoringConstants.HRV_SIGMA_WINDOW_DAYS.toLong(), ChronoUnit.DAYS).toEpochMilli()
            val allSleepRmssd   = hrvDao.getSleepRmssdValues(sigmaWindowFromMs)
            val muHrvHistory    = allSleepRmssd.takeLast(ScoringConstants.HRV_MU_WINDOW_DAYS)
            val sigmaHrvHistory = allSleepRmssd

            var sessionHrvSamples = hrvDao.getSleepRmssdForSession(session.id)
            logD("ScoringRepository") {
                "HRV session lookup [sessionId=${session.id}] startTime=${session.startTime} endTime=${session.endTime} samples=${sessionHrvSamples.size}"
            }
            if (sessionHrvSamples.isEmpty()) {
                sessionHrvSamples = hrvDao.getRmssdInTimeRange(session.startTime, session.endTime)
                logD("ScoringRepository") {
                    "HRV time-range fallback [start=${session.startTime} end=${session.endTime}] samples=${sessionHrvSamples.size}"
                }
            }
            val currentHrvMean = sessionHrvSamples.mean()
            val currentNocturnalRhr = heartRateDao.getAvgSleepHr(session.id)

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

            val allWakeHrRecords = withContext(Dispatchers.IO) {
                heartRateDao.getByTimeRange(batchWindowStart, batchWindowEnd)
            }
            val historicRestingHrs = withContext(Dispatchers.Default) {
                sessions.filter { it.id != session.id }.mapNotNull { s ->
                    val start = s.endTime - beforeMs
                    val end = s.endTime + afterMs
                    allWakeHrRecords
                        .filter { it.timestampMs in start..end }
                        .minOfOrNull { it.beatsPerMinute }
                }
            }
            val restingHrBaseline = if (historicRestingHrs.isNotEmpty()) historicRestingHrs.median().roundToInt() else null
            val restingHrRatio = if (currentRestingHr != null && restingHrBaseline != null && restingHrBaseline > 0) {
                currentRestingHr.toFloat() / restingHrBaseline.toFloat()
            } else null

            // Validate current night for stage plausibility
            val validation = ScoringCalculator.validateNight(
                rmssdMs         = if (sessionHrvSamples.isNotEmpty()) currentHrvMean else null,
                rhrBpm          = currentNocturnalRhr?.toFloat(),
                durationMinutes = session.durationMinutes,
                deepMinutes     = session.deepSleepMinutes,
                remMinutes      = session.remSleepMinutes,
            )
            val stagesSuspicious = !validation.stagesValid || validation.stagesSuspicious

            val sigmaPrior = prefs.physiologyProfile.lnSigmaPrior

            var rhrRatio: Float? = null
            var sleepScore: Float? = null
            var readinessScore: Float? = null
            var persistedZLnHrv: Float? = null
            var persistedZRhr: Float? = null
            var persistedFlags: String? = null

            if (currentNocturnalRhr != null) {
                rhrRatio = currentNocturnalRhr.toFloat() / (baselineRhrValue + 0.001f)

                val minHrTimestamp = heartRateDao.getMinHrTimestamp(session.id)
                val isLateNadir = minHrTimestamp != null &&
                    ScoringCalculator.isLateNadir(minHrTimestamp, session.startTime, session.durationMinutes)

                val zHrv = if (sessionHrvSamples.isNotEmpty()) {
                    ScoringCalculator.computeHrvZScore(
                        currentRmssdMs   = currentHrvMean,
                        muHistory        = muHrvHistory,
                        sigmaHistory     = sigmaHrvHistory,
                        sigmaPrior       = sigmaPrior,
                        baselineOverride = prefs.hrvBaselineOverride,
                    )
                } else null

                val zRhr = ScoringCalculator.computeRhrZScore(
                    currentRhrBpm    = currentNocturnalRhr.toFloat(),
                    rhrHistory       = rhrValues,
                    baselineOverride = prefs.rhrBaselineOverride,
                )
                val rhrDeltaBpm = currentNocturnalRhr.toFloat() - baselineRhrValue.toFloat()

                var sRest = ScoringCalculator.computeRestorationSubScore(
                    currentHrvMean      = currentHrvMean,
                    muHrvHistory        = muHrvHistory,
                    sigmaHrvHistory     = sigmaHrvHistory,
                    sigmaPrior          = sigmaPrior,
                    currentNocturnalRhr = currentNocturnalRhr.toFloat(),
                    rhrValues           = rhrValues,
                    rhrBaselineOverride = prefs.rhrBaselineOverride,
                    hrvBaselineOverride = prefs.hrvBaselineOverride,
                )

                if (isLateNadir) {
                    sRest *= ScoringConstants.Restoration.LATE_NADIR_PENALTY
                }

                sleepScore = ScoringCalculator.computeSleepScore(
                    durationMinutes  = session.durationMinutes,
                    efficiency       = session.efficiency,
                    deepSleepMinutes = session.deepSleepMinutes,
                    remSleepMinutes  = session.remSleepMinutes,
                    goalSleepHours   = prefs.goalSleepHours,
                    sRest            = sRest,
                    userAge          = prefs.age,
                )

                val recoveryFlags = ScoringCalculator.computeRecoveryFlags(
                    zLnHrv           = zHrv,
                    zRhr             = zRhr,
                    rhrDeltaBpm      = rhrDeltaBpm,
                    yesterdayZLnHrv  = yesterdaySummary?.zLnHrv,
                    yesterdayZRhr    = yesterdaySummary?.zRhr,
                    hrvMissing       = sessionHrvSamples.isEmpty(),
                    stagesSuspicious = stagesSuspicious,
                    isLateNadir      = isLateNadir,
                    isCalibrating    = false,
                )

                readinessScore = ScoringCalculator.computeReadinessScore(
                    sRest         = sRest,
                    sleepScore    = sleepScore,
                    loadScore     = loadScore,
                    recoveryFlags = recoveryFlags,
                )

                persistedZLnHrv = zHrv
                persistedZRhr   = zRhr
                persistedFlags  = if (recoveryFlags.isNotEmpty())
                    recoveryFlags.joinToString(",") { it.name } else null
            }

            return@withContext summary.copy(
                sleepScore        = sleepScore,
                readinessScore    = readinessScore,
                nocturnalRhr      = currentNocturnalRhr,
                nocturnalHrv      = if (sessionHrvSamples.isNotEmpty()) currentHrvMean.roundToInt() else null,
                sleepDurationMinutes = session.durationMinutes,
                deepSleepPercent  = if (session.durationMinutes > 0) session.deepSleepMinutes / session.durationMinutes.toFloat() * 100f else null,
                remSleepPercent   = if (session.durationMinutes > 0) session.remSleepMinutes / session.durationMinutes.toFloat() * 100f else null,
                rhrRatio          = rhrRatio,
                restingHeartRate  = currentRestingHr,
                restingHrRatio    = restingHrRatio,
                restingHrBaseline = restingHrBaseline,
                zLnHrv            = persistedZLnHrv,
                zRhr              = persistedZRhr,
                recoveryFlags     = persistedFlags,
            )
        }
    }
