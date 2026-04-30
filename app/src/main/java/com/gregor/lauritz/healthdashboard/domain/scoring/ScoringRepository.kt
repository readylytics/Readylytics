package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.util.HeartRateFormulas
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
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
        private val scoringCalculator: ScoringCalculator,
        private val baselineComputer: BaselineComputer =
            BaselineComputer(heartRateDao, hrvDao, sleepSessionDao, scoringCalculator),
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
                val hrMax = if (prefs.autoCalculateMaxHr) HeartRateFormulas.estimateMaxHr(prefs.age).toFloat() else prefs.maxHeartRate.toFloat()

                val rhrBaselineValues = baselineComputer.rhrHistory(dayMidnight)
                val rhrBaselineValue = baselineComputer.resolveBaselineRhrBpm(
                    rhrValues = rhrBaselineValues,
                    rhrBaselineOverride = prefs.rhrBaselineOverride,
                )

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
                // ACWR uncoupling: CTL uses days 8–42, ATL uses days 1–7 (non-overlapping)
                // Fetch days 8-42 for CTL (skip last 7 days)
                val ctlFetchFrom = dayMidnight.minus(ScoringConstants.CHRONIC_DAYS - 1, ChronoUnit.DAYS)
                    .plus(ScoringConstants.ACUTE_DAYS, ChronoUnit.DAYS).toEpochMilli()
                val dailyTrimpList = workoutDao.getDailyTrimp(ctlFetchFrom, nextDayMidnightMs, tzOffsetMs)

                val ctl = scoringCalculator.computeCtlEma(dailyTrimpList = dailyTrimpList)
                val acuteFrom = dayMidnight.minus(ScoringConstants.ACUTE_DAYS - 1, ChronoUnit.DAYS).toEpochMilli()
                val acuteTotal = workoutDao.getTotalTrimp(acuteFrom, nextDayMidnightMs) ?: 0f
                // Seed ATL = CTL during cold-start so SR initialises at 1.0 — REF: A.15 review
                val atl = if (dailyTrimpList.size < ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION) ctl
                          else acuteTotal / ScoringConstants.ACUTE_DAYS.toFloat()

                val sr = scoringCalculator.computeStrainRatio(atl, ctl)
                val loadScore = scoringCalculator.computeLoadScore(sr)
                val todayTrimp = workoutDao.getTotalTrimp(dayMidnightMs, nextDayMidnightMs) ?: 0f

                summary = summary.copy(loadScore = loadScore, strainRatio = sr, totalTrimp = todayTrimp)

                // Always compute and persist HRV baseline regardless of sleep session availability
                // Only include samples from valid nights to avoid baseline pollution — REF: A.5 review
                val computedHrvBaseline = baselineComputer.computeHrvBaseline(
                    dayMidnight = dayMidnight,
                    hrvBaselineOverride = prefs.hrvBaselineOverride,
                )
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
            val rhrValues = baselineComputer.rhrHistory(dayMidnight)

            // Fetch yesterday's Z-scores for 2-night consecutive flag confirmation
            val yesterdayMidnightMs = targetDate.minusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val yesterdaySummary = dailySummaryDao.getByDate(yesterdayMidnightMs)

            // Separate mu (7-night) and sigma (56-night) HRV history windows
            // Only include samples from valid nights to avoid baseline pollution — REF: A.5 review
            val hrvWindows = baselineComputer.computeHrvWindows(
                dayMidnight = dayMidnight,
                excludeSessionId = session.id,
            )
            val historicalSessions = hrvWindows.historicalSessions
            val validHistoricalSessionIds = hrvWindows.validHistoricalSessionIds
            val sigmaHrvHistory = hrvWindows.sigmaHistory
            val muHrvHistory    = hrvWindows.muHistory

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
            val currentHrvMean = if (sessionHrvSamples.isNotEmpty()) {
                sessionHrvSamples.mean()
            } else {
                // HRV missing fallback: use 7-day rolling mean of historical nocturnalHrv values
                val dayMidnightMs = dayMidnight.toEpochMilli()
                val sevenDaysAgoMs = dayMidnightMs - TimeUnit.DAYS.toMillis(7L)
                val recentSummaries = dailySummaryDao.getSince(sevenDaysAgoMs)
                    .filter { it.dateMidnightMs < dayMidnightMs }
                    .mapNotNull { it.nocturnalHrv?.toFloat() }
                if (recentSummaries.isNotEmpty()) recentSummaries.mean() else 0f
            }
            val currentNocturnalRhr = heartRateDao.getAvgSleepHr(session.id)

            val baselineRhrValue = baselineComputer.resolveBaselineRhrRounded(
                rhrValues = rhrValues,
                rhrBaselineOverride = prefs.rhrBaselineOverride,
            )

            val beforeMs = prefs.restingHrBeforeMinutes * 60 * 1000L
            val afterMs = prefs.restingHrAfterMinutes * 60 * 1000L
            val minHrStartTime = session.endTime - beforeMs
            val minHrEndTime = session.endTime + afterMs

            val currentRestingHr = heartRateDao.getMinHrInRange(minHrStartTime, minHrEndTime)

            val baselineFrom = dayMidnight.minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS).toEpochMilli()
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

            // Validate current night for stage plausibility using pre-fetched batch HR data
            val currentHrCoverage = isHrCoverageValidUsingRecords(session.startTime, session.endTime, session.durationMinutes, allWakeHrRecords)
            val validation = scoringCalculator.validateNight(
                rmssdMs         = if (sessionHrvSamples.isNotEmpty()) currentHrvMean else null,
                rhrBpm          = currentNocturnalRhr?.toFloat(),
                durationMinutes = session.durationMinutes,
                deepMinutes     = session.deepSleepMinutes,
                remMinutes      = session.remSleepMinutes,
                hrCoverageValid = currentHrCoverage,
            )
            val stagesSuspicious = !validation.stagesValid || validation.stagesSuspicious

            val sigmaPrior = prefs.physiologyProfile.lnSigmaPrior

            var rhrRatio: Float? = null
            var sleepScore: Float? = null
            var readinessScore: Float? = null
            var persistedZLnHrv: Float? = null
            var persistedZRhr: Float? = null
            var persistedFlags: String? = null
            // ReadinessResult diagnostics + contributors — REF: plan_scoring.md §3
            var readinessResult: ReadinessResult = ReadinessResult.EMPTY

            val lnSigmaHistory = sigmaHrvHistory.map { ln(it.coerceAtLeast(0.001f)) }
            val hrvSigma = if (sessionHrvSamples.isNotEmpty()) {
                scoringCalculator.hrvSigma(lnSigmaHistory, sigmaPrior)
            } else null

            if (currentNocturnalRhr != null) {
                rhrRatio = currentNocturnalRhr.toFloat() / (baselineRhrValue + 0.001f)

                // Timezone jump detection — travel often shifts physiological nadir.
                // REF: A.12 review — detect significant offset shifts vs. previous session.
                val currentOffset = session.endZoneOffsetSeconds
                val previousSession = historicalSessions.maxByOrNull { it.endTime }
                val previousOffset = previousSession?.endZoneOffsetSeconds
                val isTimezoneJump = currentOffset != null && previousOffset != null &&
                                     kotlin.math.abs(currentOffset - previousOffset) > ScoringConstants.TIMEZONE_JUMP_THRESHOLD_SECONDS

                val minHrTimestamp = heartRateDao.getMinHrTimestamp(session.id)
                val isLateNadirRaw = minHrTimestamp != null &&
                    scoringCalculator.isLateNadir(minHrTimestamp, session.startTime, session.durationMinutes)
                val isLateNadir = isLateNadirRaw && !isTimezoneJump

                val zHrv = if (sessionHrvSamples.isNotEmpty()) {
                    scoringCalculator.computeHrvZScore(
                        currentRmssdMs   = currentHrvMean,
                        muHistory        = muHrvHistory,
                        sigmaHistory     = sigmaHrvHistory,
                        sigmaPrior       = sigmaPrior,
                        baselineOverride = prefs.hrvBaselineOverride,
                    )
                } else null

                val zRhr = scoringCalculator.computeRhrZScore(
                    currentRhrBpm    = currentNocturnalRhr.toFloat(),
                    rhrHistory       = rhrValues,
                    baselineOverride = prefs.rhrBaselineOverride,
                )
                val rhrDeltaBpm = currentNocturnalRhr.toFloat() - baselineRhrValue.toFloat()

                var sRest = scoringCalculator.computeRestorationSubScore(
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

                sleepScore = scoringCalculator.computeSleepScore(
                    durationMinutes  = session.durationMinutes,
                    efficiency       = session.efficiency,
                    deepSleepMinutes = session.deepSleepMinutes,
                    remSleepMinutes  = session.remSleepMinutes,
                    goalSleepHours   = prefs.goalSleepHours,
                    sRest            = sRest,
                    userAge          = prefs.age,
                    stagesSuspicious = stagesSuspicious,
                )

                val totalValidHrvNights = validHistoricalSessionIds.size +
                                          (if (validation.canContributeToBaseline) 1 else 0)
                val isCalibrating = totalValidHrvNights < ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION

                val recoveryFlags = scoringCalculator.computeRecoveryFlags(
                    zLnHrv           = zHrv,
                    zRhr             = zRhr,
                    rhrDeltaBpm      = rhrDeltaBpm,
                    yesterdayZLnHrv  = yesterdaySummary?.zLnHrv,
                    yesterdayZRhr    = yesterdaySummary?.zRhr,
                    hrvMissing       = sessionHrvSamples.isEmpty(),
                    stagesSuspicious = stagesSuspicious,
                    isLateNadir      = isLateNadir,
                    isCalibrating    = isCalibrating,
                )

                readinessScore = scoringCalculator.computeReadinessScore(
                    sRest         = sRest,
                    sleepScore    = sleepScore,
                    loadScore     = loadScore,
                    recoveryFlags = recoveryFlags,
                )

                persistedZLnHrv = zHrv
                persistedZRhr   = zRhr
                persistedFlags  = if (recoveryFlags.isNotEmpty())
                    recoveryFlags.joinToString(",") { it.name } else null

                // Bundle diagnostics + contributors into ReadinessResult — REF: plan_scoring.md §3
                val rollingMu = if (muHrvHistory.isNotEmpty())
                    muHrvHistory.map { ln(it.coerceAtLeast(0.001f)) }.mean() else null
                val durationSubScore = scoringCalculator.computeDurationSubScore(
                    durationMinutes = session.durationMinutes,
                    efficiency      = session.efficiency,
                    goalSleepHours  = prefs.goalSleepHours,
                )
                val archSubScore = scoringCalculator.computeArchSubScore(
                    deepSleepMinutes = session.deepSleepMinutes,
                    remSleepMinutes  = session.remSleepMinutes,
                    durationMinutes  = session.durationMinutes,
                    userAge          = prefs.age,
                )
                readinessResult = ReadinessResult(
                    readinessScore = readinessScore,
                    sleepScore     = sleepScore,
                    loadScore      = loadScore,
                    sRest          = sRest,
                    recoveryFlags  = recoveryFlags,
                    contributors = ReadinessResult.Contributors(
                        hrvScore             = zHrv?.let { scoringCalculator.computeHrvScore(it) },
                        rhrScore             = zRhr?.let { (50f - 25f * it).coerceIn(0f, 100f) },
                        durationScore        = durationSubScore,
                        architectureScore    = archSubScore,
                        loadContribution     = loadScore,
                    ),
                    diagnostics = ReadinessResult.Diagnostics(
                        zLnHrv          = zHrv,
                        zRhr            = zRhr,
                        lnSigma         = hrvSigma,
                        rollingMu       = rollingMu,
                        rhrDeltaBpm     = rhrDeltaBpm,
                        isCalibrating   = isCalibrating,
                        stagesSuspicious = stagesSuspicious,
                        lateNadir       = isLateNadir,
                        hrvMissing      = sessionHrvSamples.isEmpty(),
                        timezoneJump    = isTimezoneJump,
                    ),
                )
            }

            val diag = readinessResult.diagnostics
            val contrib = readinessResult.contributors
            // Preserve original semantics: hrvSigma is populated whenever HRV samples exist,
            // independent of RHR availability — REF: plan_scoring.md §3
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
                hrvSigma          = hrvSigma,
                // ReadinessResult persistence via @Embedded — REF: plan_scoring.md §3
                diagnostics       = readinessResult.diagnostics,
                contributors      = readinessResult.contributors,
                sRest             = readinessResult.sRest,
            )
        }

        /**
         * Reconstruct a [ReadinessResult] from a persisted [DailySummaryEntity] for
         * downstream consumers (UI, debug overlay, exports).
         * REF: plan_scoring.md §3
         */
        fun toReadinessResult(summary: DailySummaryEntity): ReadinessResult {
            val flags: Set<RecoveryFlag> = summary.recoveryFlags
                ?.split(',')
                ?.mapNotNull { token ->
                    runCatching { RecoveryFlag.valueOf(token.trim()) }.getOrNull()
                }
                ?.toSet()
                ?: emptySet()
            return ReadinessResult(
                readinessScore = summary.readinessScore,
                sleepScore     = summary.sleepScore,
                loadScore      = summary.loadScore,
                sRest          = summary.sRest,
                recoveryFlags  = flags,
                contributors   = summary.contributors,
                diagnostics    = summary.diagnostics,
            )
        }


        private suspend fun isHrCoverageValid(
            sessionStartMs: Long,
            sessionEndMs: Long,
            durationMinutes: Int,
        ): Boolean {
            if (durationMinutes < ScoringConstants.MIN_VALID_SLEEP_DURATION_MINUTES) return false
            val hrRecords = heartRateDao.getByTimeRange(sessionStartMs, sessionEndMs)
            if (hrRecords.isEmpty()) return false

            val sleepDurationMs = sessionEndMs - sessionStartMs
            val coverageMs = hrRecords.fold(0L) { acc, record ->
                val nextTime = (hrRecords.dropWhile { it.timestampMs != record.timestampMs }
                    .drop(1).firstOrNull()?.timestampMs) ?: (record.timestampMs + 60000L)
                val coverage = minOf(nextTime - record.timestampMs, sleepDurationMs)
                acc + coverage
            }
            val coveragePercent = (coverageMs.toFloat() / sleepDurationMs.toFloat()) * 100f
            return coveragePercent >= 70f
        }

        private fun isHrCoverageValidUsingRecords(
            sessionStartMs: Long,
            sessionEndMs: Long,
            durationMinutes: Int,
            hrRecords: List<HeartRateRecordEntity>,
        ): Boolean {
            if (durationMinutes < ScoringConstants.MIN_VALID_SLEEP_DURATION_MINUTES) return false
            val filtered = hrRecords.filter { it.timestampMs in sessionStartMs..sessionEndMs }
            if (filtered.isEmpty()) return false

            val sleepDurationMs = sessionEndMs - sessionStartMs
            val coverageMs = if (filtered.size > 1) {
                filtered.zipWithNext { current, next -> next.timestampMs - current.timestampMs }.sum()
            } else {
                0L
            }
            // Assuming the last record covers a 1-minute interval, matching the original logic's fallback.
            val totalCoverage = (coverageMs + if (filtered.isNotEmpty()) 60000L else 0L).coerceAtMost(sleepDurationMs)
            val coveragePercent = (coverageMs.toFloat() / sleepDurationMs.toFloat()) * 100f
            return coveragePercent >= 70f
        }

    }
