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
            val baselineFromMs =
                dayMidnight
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
        ): Int = (rhrBaselineOverride ?: rhrValues.median()).roundToInt()

        /**
         * Computes the RHR baseline using intra-session adaptive percentiles.
         * Finds the true physiological nadir rather than the session-average median.
         * Percentile adapts to sensor data density to avoid noise artifacts.
         * Falls back to [ScoringConstants.DEFAULT_RHR_BPM] when data is insufficient.
         *
         * Optimization (Phase 1.3): Uses batch query instead of per-session queries.
         * Before: 1 + N*2 queries (1 for session IDs + 2 per session)
         * After:  2 queries (1 for sessions + 1 for all HR samples batched)
         * Performance: 5-10x faster for 30-day window (30 sessions)
         */
        suspend fun computeAdaptiveBaselineRhrBpm(
            dayMidnight: Instant,
            rhrBaselineOverride: Float?,
        ): Float {
            if (rhrBaselineOverride != null) return rhrBaselineOverride

            val baselineFromMs =
                dayMidnight
                    .minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS)
                    .toEpochMilli()
            val sessions = sleepSessionDao.getSince(baselineFromMs)
            val validIds = filterValidBaselineSessions(sessions)

            if (validIds.isEmpty()) {
                return ScoringConstants.DEFAULT_RHR_BPM
            }

            // OPTIMIZATION: Fetch all HR samples in single batch query
            val allHrSamples = heartRateDao.getSleepHrSamplesForSessions(validIds)

            // Group samples by session in memory (fast)
            val samplesBySession = allHrSamples.groupBy { it.sessionId }

            // Compute nadirs from grouped samples (no more DB queries)
            val nadirs =
                validIds.mapNotNull { sessionId ->
                    val samples = samplesBySession[sessionId] ?: return@mapNotNull null
                    val count = samples.size

                    if (count < 10) return@mapNotNull null

                    val idx =
                        when {
                            count >= 300 -> (count * 0.05).toInt()
                            count >= 150 -> (count * 0.08).toInt()
                            count >= 75 -> (count * 0.10).toInt()
                            else -> (count * 0.15).toInt()
                        }.coerceIn(0, count - 1)

                    // Index into in-memory list (no DB query)
                    samples[idx].beatsPerMinute.toFloat()
                }

            return if (nadirs.isEmpty()) {
                ScoringConstants.DEFAULT_RHR_BPM
            } else {
                nadirs.map { it.roundToInt() }.median()
            }
        }

        /**
         * Computed HRV baseline (median of valid-night RMSSD values within
         * [ScoringConstants.BASELINE_DAYS]) honoring user override.
         * Returns null when no valid samples and no override exist.
         */
        suspend fun computeHrvBaseline(
            dayMidnight: Instant,
            hrvBaselineOverride: Float?,
        ): Int? {
            val baselineFromMs =
                dayMidnight
                    .minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS)
                    .toEpochMilli()
            val historicalSessions = sleepSessionDao.getSince(baselineFromMs)
            val validIds = filterValidBaselineSessions(historicalSessions)
            val hrvBaselineValues = hrvDao.getSleepRmssdValuesForSessions(validIds)
            return (
                hrvBaselineOverride
                    ?: hrvBaselineValues.median().takeIf { it > 0f }
            )?.roundToInt()
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
            val sigmaWindowFromMs =
                dayMidnight
                    .minus(ScoringConstants.HRV_SIGMA_WINDOW_DAYS.toLong(), ChronoUnit.DAYS)
                    .toEpochMilli()
            val historicalSessions =
                sleepSessionDao
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
        ): List<String> {
            if (sessions.isEmpty()) return emptyList()

            val sessionIds = sessions.map { it.id }
            val hrvMap = hrvDao.getSleepRmssdForSessionsMap(sessionIds)
            val hrMap = heartRateDao.getAvgSleepHrForSessions(sessionIds)

            return sessions
                .filter { s ->
                    val samples = hrvMap[s.id] ?: emptyList()
                    val avgHr = hrMap[s.id]

                    val validation =
                        if (assumeCoverageValid) {
                            scoringCalculator.validateNight(
                                rmssdMs = if (samples.isNotEmpty()) samples.mean() else null,
                                rhrBpm = avgHr?.toFloat(),
                                durationMinutes = s.durationMinutes,
                                deepMinutes = s.deepSleepMinutes,
                                remMinutes = s.remSleepMinutes,
                                hrCoverageValid = true,
                            )
                        } else {
                            scoringCalculator.validateNight(
                                rmssdMs = if (samples.isNotEmpty()) samples.mean() else null,
                                rhrBpm = avgHr?.toFloat(),
                                durationMinutes = s.durationMinutes,
                                deepMinutes = s.deepSleepMinutes,
                                remMinutes = s.remSleepMinutes,
                            )
                        }

                    validation.canContributeToBaseline
                }.map { it.id }
        }

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
