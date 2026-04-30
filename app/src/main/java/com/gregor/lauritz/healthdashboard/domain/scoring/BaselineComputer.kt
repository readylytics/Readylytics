package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.domain.util.mean
import com.gregor.lauritz.healthdashboard.domain.util.median
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Computes physiological rolling-window baselines used by the scoring pipeline.
 *
 * Extracted from [ScoringRepository] to isolate baseline-window construction
 * (RHR median, HRV mu/sigma windows) from orchestration concerns.
 * REF: plan_scoring.md §15 — Extract BaselineComputer.
 */
@Singleton
class BaselineComputer
    @Inject
    constructor(
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val sleepSessionDao: SleepSessionDao,
        private val scoringCalculator: ScoringCalculator,
    ) {
        /**
         * Per-session average sleep HR values within the [ScoringConstants.BASELINE_DAYS]
         * window ending at [dayMidnight]. Used as the personal RHR history for z-scores.
         */
        suspend fun rhrHistory(dayMidnight: Instant): List<Int> {
            val baselineFromMs = dayMidnight
                .minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS)
                .toEpochMilli()
            return heartRateDao.getAvgSleepHrPerSession(baselineFromMs)
        }

        /**
         * Resolves the baseline RHR scalar used for TRIMP/PAI calculations.
         * Honors [rhrBaselineOverride] then falls back to median of [rhrValues],
         * else [ScoringConstants.DEFAULT_RHR_BPM].
         */
        fun resolveBaselineRhrBpm(
            rhrValues: List<Int>,
            rhrBaselineOverride: Float?,
        ): Float =
            rhrBaselineOverride
                ?: rhrValues.median().takeIf { it > 0f }
                ?: ScoringConstants.DEFAULT_RHR_BPM

        /**
         * Resolves the baseline RHR (rounded) for sleep-metric calculations,
         * preferring [rhrBaselineOverride] over the personal median.
         */
        fun resolveBaselineRhrRounded(
            rhrValues: List<Int>,
            rhrBaselineOverride: Float?,
        ): Int =
            (rhrBaselineOverride ?: rhrValues.median()).roundToInt()

        /**
         * Computed HRV baseline (median of valid-night RMSSD values within
         * [ScoringConstants.BASELINE_DAYS]) honoring user override.
         * Returns null when no valid samples and no override exist.
         */
        suspend fun computeHrvBaseline(
            dayMidnight: Instant,
            hrvBaselineOverride: Float?,
        ): Int? {
            val baselineFromMs = dayMidnight
                .minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS)
                .toEpochMilli()
            val historicalSessions = sleepSessionDao.getSince(baselineFromMs)
            val validIds = filterValidBaselineSessions(historicalSessions)
            val hrvBaselineValues = hrvDao.getSleepRmssdValuesForSessions(validIds)
            return (hrvBaselineOverride
                ?: hrvBaselineValues.median().takeIf { it > 0f })
                ?.roundToInt()
        }

        /**
         * Builds the (mu, sigma) HRV history windows used by sleep-metric scoring.
         *
         * - sigma window: last [ScoringConstants.HRV_SIGMA_WINDOW_DAYS] days of valid nights
         * - mu window:    last [ScoringConstants.HRV_MU_WINDOW_DAYS] entries of the sigma window
         *
         * Excludes [excludeSessionId] (typically the current night) and pollution
         * from invalid nights (gating via [ScoringCalculator.validateNight]).
         */
        suspend fun computeHrvWindows(
            dayMidnight: Instant,
            excludeSessionId: String?,
        ): HrvWindows {
            val sigmaWindowFromMs = dayMidnight
                .minus(ScoringConstants.HRV_SIGMA_WINDOW_DAYS.toLong(), ChronoUnit.DAYS)
                .toEpochMilli()
            val historicalSessions = sleepSessionDao
                .getSince(sigmaWindowFromMs)
                .filter { it.id != excludeSessionId }
            val validIds = filterValidBaselineSessions(historicalSessions, assumeCoverageValid = true)
            val sigmaHistory = hrvDao.getSleepRmssdValuesForSessions(validIds)
            val muHistory = sigmaHistory.takeLast(ScoringConstants.HRV_MU_WINDOW_DAYS)
            return HrvWindows(
                muHistory = muHistory,
                sigmaHistory = sigmaHistory,
                historicalSessions = historicalSessions,
                validHistoricalSessionIds = validIds,
            )
        }

        private suspend fun filterValidBaselineSessions(
            sessions: List<SleepSessionEntity>,
            assumeCoverageValid: Boolean = false,
        ): List<String> =
            sessions.filter { s ->
                val samples = hrvDao.getSleepRmssdForSession(s.id)
                val validation = if (assumeCoverageValid) {
                    scoringCalculator.validateNight(
                        rmssdMs         = if (samples.isNotEmpty()) samples.mean() else null,
                        rhrBpm          = heartRateDao.getAvgSleepHr(s.id)?.toFloat(),
                        durationMinutes = s.durationMinutes,
                        deepMinutes     = s.deepSleepMinutes,
                        remMinutes      = s.remSleepMinutes,
                        hrCoverageValid = true,
                    )
                } else {
                    scoringCalculator.validateNight(
                        rmssdMs         = if (samples.isNotEmpty()) samples.mean() else null,
                        rhrBpm          = heartRateDao.getAvgSleepHr(s.id)?.toFloat(),
                        durationMinutes = s.durationMinutes,
                        deepMinutes     = s.deepSleepMinutes,
                        remMinutes      = s.remSleepMinutes,
                    )
                }

                validation.canContributeToBaseline
            }.map { it.id }

        /**
         * HRV history windows + supporting session metadata returned from
         * [computeHrvWindows].
         */
        data class HrvWindows(
            val muHistory: List<Float>,
            val sigmaHistory: List<Float>,
            val historicalSessions: List<SleepSessionEntity>,
            val validHistoricalSessionIds: List<String>,
        )
    }
