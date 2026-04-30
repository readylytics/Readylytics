package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.model.ReadinessResult
import com.gregor.lauritz.healthdashboard.domain.model.RecoveryFlag
import com.gregor.lauritz.healthdashboard.domain.util.logD
import com.gregor.lauritz.healthdashboard.domain.util.mean
import com.gregor.lauritz.healthdashboard.domain.util.median
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
class ComputeSleepMetricsUseCase @Inject constructor(
    private val baselineComputer: BaselineComputer,
    private val dailySummaryDao: DailySummaryDao,
    private val hrvDao: HrvDao,
    private val heartRateDao: HeartRateDao,
    private val sleepSessionDao: SleepSessionDao,
    private val scoringCalculator: ScoringCalculator,
) {
    suspend operator fun invoke(
        session: SleepSessionEntity,
        dayMidnight: Instant,
        targetDate: LocalDate,
        prefs: UserPreferences,
        summary: DailySummaryEntity,
        loadScore: Float,
        zoneId: ZoneId,
    ): DailySummaryEntity {
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

        return summary.copy(
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
