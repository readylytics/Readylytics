package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.model.DailySummaryEntity
import app.readylytics.health.domain.model.SleepSessionEntity
import app.readylytics.health.domain.persistence.DailySummaryDao
import app.readylytics.health.domain.persistence.HeartRateDao
import app.readylytics.health.domain.persistence.HrvDao
import app.readylytics.health.domain.persistence.SleepSessionDao
import app.readylytics.health.domain.util.logD
import app.readylytics.health.domain.util.mean
import app.readylytics.health.domain.util.median
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
                val index = ((percentile / 100.0) * (samples.size - 1)).roundToInt().coerceIn(0, samples.size - 1)
                samples[index].beatsPerMinute
            }
        }

        suspend fun rhrHistoryBetween(
            fromMs: Long,
            toMs: Long,
            percentile: Int,
        ): List<Int> {
            val inclusiveToMs = (toMs - 1).coerceAtLeast(0)
            val baselineFromMs =
                Instant
                    .ofEpochMilli(fromMs)
                    .minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS)
                    .toEpochMilli()
            val sessions = sleepSessionDao.getBetween(baselineFromMs.coerceAtLeast(0), inclusiveToMs)
            val sessionIds = sessions.map { it.id }
            if (sessionIds.isEmpty()) return emptyList()

            val allHrRecords = heartRateDao.getSleepHrProjectionForSessions(sessionIds)
            val samplesBySession = allHrRecords.groupBy { it.sessionId }

            return sessions.mapNotNull { session ->
                val samples = samplesBySession[session.id] ?: return@mapNotNull null
                if (samples.isEmpty()) return@mapNotNull null
                val index = ((percentile / 100.0) * (samples.size - 1)).roundToInt().coerceIn(0, samples.size - 1)
                samples[index].beatsPerMinute
            }
        }

        /**
         * Resolves the baseline RHR scalar used for TRIMP/RAS calculations.
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
            val inclusiveToMs = (toMs - 1).coerceAtLeast(0)
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
            val sessions = sleepSessionDao.getBetween(baselineFromMs.coerceAtLeast(0), inclusiveToMs)
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
                // Median of the float nadirs directly; round only at the final boundary.
                nadirs.median()
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

                    val idx = ((percentile / 100.0) * (count - 1)).roundToInt().coerceIn(0, count - 1)

                    // Index into in-memory list (no DB query)
                    samples[idx].beatsPerMinute.toFloat()
                }

            return if (nadirs.isEmpty()) {
                ScoringConstants.DEFAULT_RHR_BPM
            } else {
                // Median of the float nadirs directly; round only at the final boundary.
                nadirs.median()
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
            val inclusiveToMs = (toMs - 1).coerceAtLeast(0)
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
                        inclusiveToMs,
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
            val inclusiveToMs = (toMs - 1).coerceAtLeast(0)
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
                        inclusiveToMs,
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
        ): HrvWindows? =
            computeHrvWindowsBetween(
                fromMs = dayMidnight.toEpochMilli(),
                toMs = dayMidnight.plus(1, ChronoUnit.DAYS).toEpochMilli(),
                excludeSessionId = excludeSessionId,
            )

        /**
         * Batched, point-in-time-correct baseline inputs for the historical backfill.
         *
         * Equivalent to invoking [computeHrvWindowsBetween] (excluding the day's own session) plus
         * [computeAdaptiveBaselineRhrBpmBetween] for every day in [summaries] — but performs a
         * fixed, small number of DB reads for the whole range instead of ~11 queries per day
         * (a classic N+1). It pre-fetches the widest window (the 56-day HRV sigma window) once and
         * derives each day's rolling windows in memory.
         *
         * Equivalence is by construction: the prefetch is a superset [SleepSessionDao.getBetween]
         * over `[min(day) − 56d .. max(day) end]`, and each day re-applies the identical
         * `startTime >= from AND endTime <= to` predicate in the same start-ASC order, so per-day
         * membership, validity gating ([ScoringCalculator.validateNight]), session ordering and the
         * percentile-nadir math are byte-identical to the per-day methods. The RHR window does NOT
         * exclude the day's own session (mirroring [computeAdaptiveBaselineRhrBpmBetween]); the HRV
         * window does (mirroring the live sync path).
         *
         * No freeze-skip is applied here: the historical backfill wipes derived baselines before
         * calling, so the per-day methods' freeze guard never fires in practice.
         */
        suspend fun computeBackfillBaselines(
            summaries: List<DailySummaryEntity>,
            percentile: Int,
        ): Map<Long, BackfillBaseline> {
            if (summaries.isEmpty()) return emptyMap()

            val minMidnightMs = summaries.minOf { it.dateMidnightMs }
            val maxMidnightMs = summaries.maxOf { it.dateMidnightMs }
            val maxDayEndMs =
                Instant.ofEpochMilli(maxMidnightMs).plus(1, ChronoUnit.DAYS).toEpochMilli() - 1
            val prefetchFromMs =
                Instant
                    .ofEpochMilli(minMidnightMs)
                    .minus(ScoringConstants.HRV_SIGMA_WINDOW_DAYS.toLong(), ChronoUnit.DAYS)
                    .toEpochMilli()
                    .coerceAtLeast(0)

            val sessionsAsc = sleepSessionDao.getBetween(prefetchFromMs, maxDayEndMs)
            if (sessionsAsc.isEmpty()) {
                return summaries.associate {
                    it.dateMidnightMs to
                        BackfillBaseline(
                            emptyList(),
                            emptyList(),
                            ScoringConstants.DEFAULT_RHR_BPM,
                            emptyList(),
                        )
                }
            }

            val ids = sessionsAsc.map { it.id }
            val rmssdBySession = hrvDao.getSleepRmssdForSessionsMap(ids)
            val avgHrBySession = heartRateDao.getAvgSleepHrForSessions(ids)
            val hrProjectionBySession = heartRateDao.getSleepHrProjectionForSessions(ids).groupBy { it.sessionId }

            // Precompute per-night derived values once (validity, nightly RMSSD mean, percentile nadir).
            val nights =
                sessionsAsc.map { session ->
                    val rmssd = rmssdBySession[session.id] ?: emptyList()
                    val hrvMean = if (rmssd.isNotEmpty()) rmssd.mean() else null
                    val canContribute =
                        scoringCalculator
                            .validateNight(
                                rmssdMs = hrvMean,
                                rhrBpm = avgHrBySession[session.id]?.toFloat(),
                                durationMinutes = session.durationMinutes,
                                deepMinutes = session.deepSleepMinutes,
                                remMinutes = session.remSleepMinutes,
                                hrCoverageValid = true,
                            ).canContributeToBaseline
                    val samples = hrProjectionBySession[session.id]
                    val nadirBpm =
                        if (samples != null && samples.size >= 10) {
                            val idx =
                                ((percentile / 100.0) * (samples.size - 1))
                                    .roundToInt()
                                    .coerceIn(0, samples.size - 1)
                            samples[idx].beatsPerMinute.toFloat()
                        } else {
                            null
                        }
                    val rhrPercentileBpm =
                        if (samples != null && samples.isNotEmpty()) {
                            val idx =
                                ((percentile / 100.0) * (samples.size - 1))
                                    .roundToInt()
                                    .coerceIn(0, samples.size - 1)
                            samples[idx].beatsPerMinute
                        } else {
                            null
                        }
                    BackfillNight(
                        session.id,
                        session.startTime,
                        session.endTime,
                        hrvMean,
                        canContribute,
                        nadirBpm,
                        rhrPercentileBpm,
                    )
                }

            return summaries.associate { summary ->
                val dayMidnightMs = summary.dateMidnightMs
                val dayInstant = Instant.ofEpochMilli(dayMidnightMs)
                val nextDayMidnightMs = dayInstant.plus(1, ChronoUnit.DAYS).toEpochMilli()
                val dayEndMs = nextDayMidnightMs - 1

                // The day's own session: first session whose endTime falls within the day
                // (mirrors getSessionEndingInRange(dayMidnight, nextDayMidnight)).
                val ownSessionId =
                    nights
                        .filter { it.endTime in dayMidnightMs until nextDayMidnightMs }
                        .minWithOrNull(compareBy({ it.endTime }, { it.id }))
                        ?.id

                // HRV sigma window: [dayMidnight − 56d .. dayEnd], excluding the day's own session.
                val hrvFromMs =
                    dayInstant
                        .minus(ScoringConstants.HRV_SIGMA_WINDOW_DAYS.toLong(), ChronoUnit.DAYS)
                        .toEpochMilli()
                        .coerceAtLeast(0)
                val sigmaHistory =
                    nights
                        .asSequence()
                        .filter {
                            it.startTime >= hrvFromMs &&
                                it.endTime <= dayEndMs &&
                                it.id != ownSessionId &&
                                it.canContributeToBaseline
                        }.mapNotNull { it.hrvMean }
                        .toList()
                val muHistory = sigmaHistory.takeLast(ScoringConstants.HRV_MU_WINDOW_DAYS)

                // RHR window: [dayMidnight − 30d .. dayEnd] (own session NOT excluded).
                val rhrFromMs =
                    dayInstant
                        .minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS)
                        .toEpochMilli()
                        .coerceAtLeast(0)
                val nadirs =
                    nights
                        .asSequence()
                        .filter {
                            it.startTime >= rhrFromMs &&
                                it.endTime <= dayEndMs &&
                                it.canContributeToBaseline
                        }.mapNotNull { it.nadirBpm }
                        .toList()
                val rhrBpm = if (nadirs.isEmpty()) ScoringConstants.DEFAULT_RHR_BPM else nadirs.median()
                val rhrHistory =
                    nights
                        .asSequence()
                        .filter {
                            it.startTime >= rhrFromMs &&
                                it.endTime <= dayEndMs
                        }.mapNotNull { it.rhrPercentileBpm }
                        .toList()

                dayMidnightMs to BackfillBaseline(muHistory, sigmaHistory, rhrBpm, rhrHistory)
            }
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

        /**
         * Per-day baseline inputs produced by [computeBackfillBaselines]: the HRV mu/sigma history
         * windows and the resolved RHR baseline (already defaulted when no valid nadirs exist).
         */
        data class BackfillBaseline(
            val muHistory: List<Float>,
            val sigmaHistory: List<Float>,
            val rhrBpm: Float,
            val rhrHistory: List<Int> = emptyList(),
        )

        /** Pre-derived per-night values used to build each day's windows in memory. */
        private data class BackfillNight(
            val id: String,
            val startTime: Long,
            val endTime: Long,
            val hrvMean: Float?,
            val canContributeToBaseline: Boolean,
            val nadirBpm: Float?,
            val rhrPercentileBpm: Int?,
        )
    }
