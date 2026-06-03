package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.domain.util.logD
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
 *
 * Freeze enforcement (US-B6): [computeHrvWindows] and [computeAdaptiveBaselineRhrBpm]
 * return null when the DailySummary for [dayMidnight] already has a frozen baseline
 * (i.e. baselineCalculatedAtDate is set). Callers must interpret null as "use stored
 * baseline, do not recompute."
 */
@Singleton
class BaselineComputer
    @Inject
    constructor(
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val sleepSessionDao: SleepSessionDao,
        private val scoringCalculator: ScoringCalculator,
        private val dailySummaryDao: DailySummaryDao,
    ) {
        companion object {
            private const val TAG = "BaselineComputer"
        }

        suspend fun rhrHistory(
            dayMidnight: Instant,
            percentile: Int,
        ): List<Int> {
            val baselineFromMs =
                dayMidnight
                    .minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS)
                    .toEpochMilli()
            val sessions = sleepSessionDao.getSince(baselineFromMs)
            val sessionIds = sessions.map { it.id }
            if (sessionIds.isEmpty()) return emptyList()

            val allHrRecords = heartRateDao.getSleepHrProjectionForSessions(sessionIds)
            val samplesBySession = allHrRecords.groupBy { it.sessionId }

            return sessions.mapNotNull { session ->
                val samples = samplesBySession[session.id] ?: return@mapNotNull null
                if (samples.isEmpty()) return@mapNotNull null
                val index = Math.round((percentile / 100.0) * (samples.size - 1)).toInt().coerceIn(0, samples.size - 1)
                samples[index].beatsPerMinute
            }
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
         * Computes RHR baseline bounded to [fromMs, toMs] (for backfill: no look-ahead).
         */
        suspend fun computeAdaptiveBaselineRhrBpmBetween(
            fromMs: Long,
            toMs: Long,
            percentile: Int,
        ): Float? {
            val frozenSummary = dailySummaryDao.getByDate(fromMs)
            if (frozenSummary?.baselineCalculatedAtDate != null) {
                logD(TAG) { "Baseline frozen; skipping RHR recompute" }
                return null
            }
            val baselineFromMs =
                Instant
                    .ofEpochMilli(
                        fromMs,
                    ).minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS)
                    .toEpochMilli()
            val sessions = sleepSessionDao.getBetween(baselineFromMs.coerceAtLeast(0), toMs)
            val validIds = filterValidBaselineSessions(sessions)
            if (validIds.isEmpty()) {
                return ScoringConstants.DEFAULT_RHR_BPM
            }
            val allHrSamples = heartRateDao.getSleepHrProjectionForSessions(validIds)
            val samplesBySession = allHrSamples.groupBy { it.sessionId }
            val nadirs =
                validIds.mapNotNull { sessionId ->
                    val samples = samplesBySession[sessionId] ?: return@mapNotNull null
                    if (samples.size < 10) return@mapNotNull null
                    val idx =
                        Math
                            .round(
                                (percentile / 100.0) * (samples.size - 1),
                            ).toInt()
                            .coerceIn(0, samples.size - 1)
                    samples[idx].beatsPerMinute.toFloat()
                }
            return if (nadirs.isEmpty()) {
                ScoringConstants.DEFAULT_RHR_BPM
            } else {
                nadirs.map { it.roundToInt() }.median()
            }
        }

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
         *
         * Freeze enforcement (US-B6): Returns null if the DailySummary for [dayMidnight]
         * already has a frozen baseline. Callers must use the stored baseline value instead.
         */
        suspend fun computeAdaptiveBaselineRhrBpm(
            dayMidnight: Instant,
            rhrBaselineOverride: Float?,
            percentile: Int,
        ): Float? {
            if (rhrBaselineOverride != null) return rhrBaselineOverride

            val frozenSummary = dailySummaryDao.getByDate(dayMidnight.toEpochMilli())
            if (frozenSummary?.baselineCalculatedAtDate != null) {
                logD(TAG) {
                    "Baseline frozen for date=${frozenSummary.baselineCalculatedAtDate}; " +
                        "rhrBpm=${frozenSummary.rhrBpm} — skipping RHR recompute"
                }
                return null
            }

            val baselineFromMs =
                dayMidnight
                    .minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS)
                    .toEpochMilli()
            val sessions = sleepSessionDao.getSince(baselineFromMs)
            val validIds = filterValidBaselineSessions(sessions)

            if (validIds.isEmpty()) {
                return ScoringConstants.DEFAULT_RHR_BPM
            }

            // OPTIMIZATION: Fetch all HR samples in single batch query using lightweight projection
            val allHrSamples = heartRateDao.getSleepHrProjectionForSessions(validIds)

            // Group samples by session in memory (fast)
            val samplesBySession = allHrSamples.groupBy { it.sessionId }

            // Compute nadirs from grouped samples (no more DB queries)
            val nadirs =
                validIds.mapNotNull { sessionId ->
                    val samples = samplesBySession[sessionId] ?: return@mapNotNull null
                    val count = samples.size

                    if (count < 10) return@mapNotNull null

                    val idx = Math.round((percentile / 100.0) * (count - 1)).toInt().coerceIn(0, count - 1)

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
         * Computed HRV baseline (median of valid-night RMSSD daily averages within
         * [ScoringConstants.BASELINE_DAYS]) honoring user override.
         * Returns null when no valid samples and no override exist.
         */
        suspend fun computeHrvBaseline(
            dayMidnight: Instant,
            hrvBaselineOverride: Float?,
        ): Int? {
            if (hrvBaselineOverride != null) return hrvBaselineOverride.roundToInt()
            val baselineFromMs =
                dayMidnight
                    .minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS)
                    .toEpochMilli()
            val historicalSessions = sleepSessionDao.getSince(baselineFromMs)
            val validIds = filterValidBaselineSessions(historicalSessions)
            if (validIds.isEmpty()) return null
            val hrvMap = hrvDao.getSleepRmssdForSessionsMap(validIds)
            val nightlyAverages =
                validIds.mapNotNull { sessionId ->
                    val samples = hrvMap[sessionId] ?: return@mapNotNull null
                    if (samples.isEmpty()) return@mapNotNull null
                    samples.mean()
                }
            return if (nightlyAverages.isEmpty()) {
                null
            } else {
                nightlyAverages.median().roundToInt()
            }
        }

        /**
         * Computes the 30-day HRV baseline (median of nightly RMSSD averages) point-in-time correctly.
         */
        suspend fun computeHrvBaselineBetween(
            fromMs: Long,
            toMs: Long,
            hrvBaselineOverride: Float?,
        ): Int? {
            if (hrvBaselineOverride != null) return hrvBaselineOverride.roundToInt()
            val frozenSummary = dailySummaryDao.getByDate(fromMs)
            if (frozenSummary?.baselineCalculatedAtDate != null) {
                return frozenSummary.hrvBaseline
            }
            val baselineFromMs =
                Instant
                    .ofEpochMilli(
                        fromMs,
                    ).minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS)
                    .toEpochMilli()
            val historicalSessions =
                sleepSessionDao
                    .getBetween(
                        baselineFromMs.coerceAtLeast(0),
                        toMs,
                    )
            val validIds = filterValidBaselineSessions(historicalSessions, assumeCoverageValid = true)
            if (validIds.isEmpty()) return null
            val hrvMap = hrvDao.getSleepRmssdForSessionsMap(validIds)
            val nightlyAverages =
                validIds.mapNotNull { sessionId ->
                    val samples = hrvMap[sessionId] ?: return@mapNotNull null
                    if (samples.isEmpty()) return@mapNotNull null
                    samples.mean()
                }
            return if (nightlyAverages.isEmpty()) {
                null
            } else {
                nightlyAverages.median().roundToInt()
            }
        }

        /**
         * Computes HRV windows bounded to [fromMs, toMs] (for backfill: no look-ahead).
         * Used by historical baseline backfill to enforce point-in-time correctness.
         */
        suspend fun computeHrvWindowsBetween(
            fromMs: Long,
            toMs: Long,
            excludeSessionId: String?,
        ): HrvWindows? {
            val frozenSummary = dailySummaryDao.getByDate(fromMs)
            if (frozenSummary?.baselineCalculatedAtDate != null) {
                logD(
                    TAG,
                ) {
                    "Baseline frozen for date=${frozenSummary.baselineCalculatedAtDate}; skipping HRV window recompute"
                }
                return null
            }
            val sigmaWindowFromMs =
                Instant
                    .ofEpochMilli(
                        fromMs,
                    ).minus(ScoringConstants.HRV_SIGMA_WINDOW_DAYS.toLong(), ChronoUnit.DAYS)
                    .toEpochMilli()
            val historicalSessions =
                sleepSessionDao
                    .getBetween(
                        sigmaWindowFromMs.coerceAtLeast(0),
                        toMs,
                    ).filter { it.id != excludeSessionId }
            val validIds = filterValidBaselineSessions(historicalSessions, assumeCoverageValid = true)
            val hrvMap = hrvDao.getSleepRmssdForSessionsMap(validIds)
            val sigmaHistory =
                validIds.mapNotNull { sessionId ->
                    val samples = hrvMap[sessionId] ?: return@mapNotNull null
                    if (samples.isEmpty()) return@mapNotNull null
                    samples.mean()
                }
            val muHistory = sigmaHistory.takeLast(ScoringConstants.HRV_MU_WINDOW_DAYS)
            return HrvWindows(
                muHistory = muHistory,
                sigmaHistory = sigmaHistory,
                historicalSessions = historicalSessions,
                validHistoricalSessionIds = validIds,
            )
        }

        /**
         * Builds the (mu, sigma) HRV history windows used by sleep-metric scoring.
         *
         * - sigma window: last [ScoringConstants.HRV_SIGMA_WINDOW_DAYS] days of valid nights
         * - mu window:    last [ScoringConstants.HRV_MU_WINDOW_DAYS] entries of the sigma window
         *
         * Excludes [excludeSessionId] (typically the current night) and pollution
         * from invalid nights (gating via [ScoringCalculator.validateNight]).
         *
         * Freeze enforcement (US-B6): Returns null if the DailySummary for [dayMidnight]
         * already has a frozen baseline. Callers must use the stored baseline values instead.
         */
        suspend fun computeHrvWindows(
            dayMidnight: Instant,
            excludeSessionId: String?,
        ): HrvWindows? {
            val frozenSummary = dailySummaryDao.getByDate(dayMidnight.toEpochMilli())
            if (frozenSummary?.baselineCalculatedAtDate != null) {
                logD(TAG) {
                    "Baseline frozen for date=${frozenSummary.baselineCalculatedAtDate}; " +
                        "hrvMu=${frozenSummary.hrvMuMssd}, hrvSigma=${frozenSummary.hrvSigmaMssd} — skipping HRV window recompute"
                }
                return null
            }
            val sigmaWindowFromMs =
                dayMidnight
                    .minus(ScoringConstants.HRV_SIGMA_WINDOW_DAYS.toLong(), ChronoUnit.DAYS)
                    .toEpochMilli()
            val historicalSessions =
                sleepSessionDao
                    .getSince(sigmaWindowFromMs)
                    .filter { it.id != excludeSessionId }
            val validIds = filterValidBaselineSessions(historicalSessions, assumeCoverageValid = true)
            val hrvMap = hrvDao.getSleepRmssdForSessionsMap(validIds)
            val sigmaHistory =
                validIds.mapNotNull { sessionId ->
                    val samples = hrvMap[sessionId] ?: return@mapNotNull null
                    if (samples.isEmpty()) return@mapNotNull null
                    samples.mean()
                }
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
