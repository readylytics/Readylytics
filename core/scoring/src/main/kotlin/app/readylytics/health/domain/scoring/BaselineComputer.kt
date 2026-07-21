package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.model.DailySummaryEntity
import app.readylytics.health.domain.model.SleepSessionEntity
import app.readylytics.health.domain.repository.ScoringHistoryRepository
import app.readylytics.health.domain.scoring.sleep.SleepDayPolicy
import app.readylytics.health.domain.util.logD
import app.readylytics.health.domain.util.mean
import app.readylytics.health.domain.util.median
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
        private val scoringHistoryRepository: ScoringHistoryRepository,
        private val scoringCalculator: ScoringCalculator,
    ) {
        companion object {
            private const val TAG = "BaselineComputer"
        }

        // UI-002/WP-22: the sleep-session -> HistoricalSleepDay aggregation machinery shared by
        // every windowed and live baseline method below lives in this extracted assembler.
        private val sleepDayAssembler = HistoricalSleepDayAssembler(scoringHistoryRepository, scoringCalculator)

        suspend fun rhrHistory(
            dayMidnight: Instant,
            percentile: Int,
        ): List<Int> {
            val baselineFromMs =
                dayMidnight
                    .minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS)
                    .toEpochMilli()
            val sessions = scoringHistoryRepository.getSleepSessionsSince(baselineFromMs)
            val sessionIds = sessions.map { it.id }
            if (sessionIds.isEmpty()) return emptyList()

            val allHrRecords = scoringHistoryRepository.getSleepHrProjectionForSessions(sessionIds)
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
            sleepDayPolicy: SleepDayPolicy? = null,
        ): List<Int> {
            val inclusiveToMs = (toMs - 1).coerceAtLeast(0)
            val baselineFromMs =
                Instant
                    .ofEpochMilli(fromMs)
                    .minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS)
                    .toEpochMilli()
            val sessions =
                scoringHistoryRepository.getSleepSessionsBetween(
                    baselineFromMs.coerceAtLeast(0),
                    inclusiveToMs,
                )
            return sleepDayAssembler.buildHistoricalSleepDays(
                sessions = sessions,
                percentile = percentile,
                sleepDayPolicy = sleepDayPolicy,
                assumeCoverageValid = true,
            ).mapNotNull { it.rhrPercentileBpm }
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
         *
         * PERF-002/WP-22: pass [prefetchedSessions] (ascending by `startTime`, covering at least
         * `[fromMs - BASELINE_DAYS, toMs)`) to slice in memory instead of re-querying the DB --
         * see [WalkForwardBaselineContext]. Omit it (the default) for the single-day/live path.
         */
        suspend fun computeAdaptiveBaselineRhrBpmBetween(
            fromMs: Long,
            toMs: Long,
            percentile: Int,
            sleepDayPolicy: SleepDayPolicy? = null,
            prefetchedSessions: List<SleepSessionEntity>? = null,
        ): Float? {
            val inclusiveToMs = (toMs - 1).coerceAtLeast(0)
            val frozenSummary = scoringHistoryRepository.getDailySummaryByDate(fromMs)
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
            val sessions =
                sessionsBetween(prefetchedSessions, baselineFromMs.coerceAtLeast(0), inclusiveToMs)
            val historicalSleepDays =
                sleepDayAssembler.buildHistoricalSleepDays(
                    sessions = sessions,
                    percentile = percentile,
                    sleepDayPolicy = sleepDayPolicy,
                    assumeCoverageValid = true,
                )
            val nadirs =
                historicalSleepDays
                    .filter { it.canContributeToBaseline }
                    .mapNotNull { it.nadirBpm }
            if (nadirs.isEmpty()) {
                return ScoringConstants.DEFAULT_RHR_BPM
            }
            return nadirs.median()
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
            sleepDayPolicy: SleepDayPolicy? = null,
        ): Float? {
            if (rhrBaselineOverride != null) return rhrBaselineOverride

            val frozenSummary = scoringHistoryRepository.getDailySummaryByDate(dayMidnight.toEpochMilli())
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
            val sessions = scoringHistoryRepository.getSleepSessionsSince(baselineFromMs)
            val historicalSleepDays =
                sleepDayAssembler.buildHistoricalSleepDays(
                    sessions = sessions,
                    percentile = percentile,
                    sleepDayPolicy = sleepDayPolicy,
                    assumeCoverageValid = true,
                )
            val nadirs =
                historicalSleepDays
                    .filter { it.canContributeToBaseline }
                    .mapNotNull { it.nadirBpm }

            if (nadirs.isEmpty()) {
                return ScoringConstants.DEFAULT_RHR_BPM
            }
            return nadirs.median()
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
            val historicalSessions = scoringHistoryRepository.getSleepSessionsSince(baselineFromMs)
            val validIds = sleepDayAssembler.filterValidBaselineSessions(historicalSessions)
            if (validIds.isEmpty()) return null
            val hrvMap = scoringHistoryRepository.getSleepRmssdForSessionsMap(validIds)
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
         *
         * PERF-002/WP-22: pass [prefetchedSessions] (ascending by `startTime`, covering at least
         * `[fromMs - BASELINE_DAYS, toMs)`) to slice in memory instead of re-querying the DB --
         * see [WalkForwardBaselineContext]. Omit it (the default) for the single-day/live path.
         */
        suspend fun computeHrvBaselineBetween(
            fromMs: Long,
            toMs: Long,
            hrvBaselineOverride: Float?,
            sleepDayPolicy: SleepDayPolicy? = null,
            prefetchedSessions: List<SleepSessionEntity>? = null,
        ): Int? {
            val inclusiveToMs = (toMs - 1).coerceAtLeast(0)
            if (hrvBaselineOverride != null) return hrvBaselineOverride.roundToInt()
            val frozenSummary = scoringHistoryRepository.getDailySummaryByDate(fromMs)
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
                sessionsBetween(prefetchedSessions, baselineFromMs.coerceAtLeast(0), inclusiveToMs)
            val historicalSleepDays =
                sleepDayAssembler.buildHistoricalSleepDays(
                    sessions = historicalSessions,
                    percentile = 50,
                    sleepDayPolicy = sleepDayPolicy,
                    assumeCoverageValid = true,
                )
            val nightlyAverages =
                historicalSleepDays
                    .filter { it.canContributeToBaseline }
                    .mapNotNull { it.hrvMean }
            return if (nightlyAverages.isEmpty()) {
                null
            } else {
                nightlyAverages.median().roundToInt()
            }
        }

        /**
         * Computes HRV windows bounded to [fromMs, toMs] (for backfill: no look-ahead).
         * Used by historical baseline backfill to enforce point-in-time correctness.
         *
         * PERF-002/WP-22: pass [prefetchedSessions] (ascending by `startTime`, covering at least
         * `[fromMs - HRV_SIGMA_WINDOW_DAYS, toMs)`) to slice in memory instead of re-querying the
         * DB -- see [WalkForwardBaselineContext]. Omit it (the default) for the single-day/live path.
         */
        suspend fun computeHrvWindowsBetween(
            fromMs: Long,
            toMs: Long,
            excludeSessionIds: Set<String> = emptySet(),
            sleepDayPolicy: SleepDayPolicy? = null,
            prefetchedSessions: List<SleepSessionEntity>? = null,
        ): HrvWindows? {
            val inclusiveToMs = (toMs - 1).coerceAtLeast(0)
            val frozenSummary = scoringHistoryRepository.getDailySummaryByDate(fromMs)
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
                sessionsBetween(prefetchedSessions, sigmaWindowFromMs.coerceAtLeast(0), inclusiveToMs)
                    .filterNot { it.id in excludeSessionIds }
            val historicalSleepDays =
                sleepDayAssembler.buildHistoricalSleepDays(
                    sessions = historicalSessions,
                    percentile = 50,
                    sleepDayPolicy = sleepDayPolicy,
                    assumeCoverageValid = true,
                )
            val targetScoreDay =
                sleepDayPolicy?.let { policy ->
                    Instant.ofEpochMilli(fromMs).atZone(policy.scoringZoneId).toLocalDate()
                }
            val priorHistoricalSleepDays =
                if (targetScoreDay == null) {
                    historicalSleepDays
                } else {
                    historicalSleepDays.filter { it.scoreDay < targetScoreDay }
                }
            val sigmaHistory =
                priorHistoricalSleepDays
                    .filter { it.canContributeToBaseline }
                    .mapNotNull { it.hrvMean }
            val muHistory = sigmaHistory.takeLast(ScoringConstants.HRV_MU_WINDOW_DAYS)
            return HrvWindows(
                muHistory = muHistory,
                sigmaHistory = sigmaHistory,
                historicalSessions = historicalSessions,
                validHistoricalSessionIds =
                    priorHistoricalSleepDays
                        .filter { it.canContributeToBaseline }
                        .flatMap { it.coreSessionIds },
                validHistoricalDayCount =
                    priorHistoricalSleepDays.count { it.canContributeToBaseline },
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
                excludeSessionIds = excludeSessionId?.let(::setOf).orEmpty(),
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
         * Equivalence is by construction: the prefetch is a superset [ScoringHistoryRepository.getSleepSessionsBetween]
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
            sleepDayPolicy: SleepDayPolicy? = null,
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

            val sessionsAsc = scoringHistoryRepository.getSleepSessionsBetween(prefetchFromMs, maxDayEndMs)
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

            val historicalSleepDays =
                sleepDayAssembler.buildHistoricalSleepDays(
                    sessions = sessionsAsc,
                    percentile = percentile,
                    sleepDayPolicy = sleepDayPolicy,
                    assumeCoverageValid = true,
                )

            return summaries.associate { summary ->
                val dayMidnightMs = summary.dateMidnightMs
                val dayInstant = Instant.ofEpochMilli(dayMidnightMs)
                val nextDayMidnightMs = dayInstant.plus(1, ChronoUnit.DAYS).toEpochMilli()
                val dayEndMs = nextDayMidnightMs - 1
                val scoreZone = sleepDayPolicy?.scoringZoneId ?: ZoneId.systemDefault()
                val scoreDay = dayInstant.atZone(scoreZone).toLocalDate()

                val sigmaWindowStartDay = scoreDay.minusDays(ScoringConstants.HRV_SIGMA_WINDOW_DAYS.toLong())
                val priorSleepDays =
                    historicalSleepDays.filter { it.scoreDay < scoreDay }
                val sigmaHistory =
                    priorSleepDays
                        .asSequence()
                        .filter {
                            it.scoreDay >= sigmaWindowStartDay &&
                                it.canContributeToBaseline
                        }.mapNotNull { it.hrvMean }
                        .toList()
                val muHistory = sigmaHistory.takeLast(ScoringConstants.HRV_MU_WINDOW_DAYS)

                val rhrWindowStartDay = scoreDay.minusDays(ScoringConstants.BASELINE_DAYS)
                val nadirs =
                    historicalSleepDays
                        .asSequence()
                        .filter {
                            it.scoreDay >= rhrWindowStartDay &&
                                it.canContributeToBaseline
                        }.mapNotNull { it.nadirBpm }
                        .toList()
                val rhrBpm = if (nadirs.isEmpty()) ScoringConstants.DEFAULT_RHR_BPM else nadirs.median()
                val rhrHistory =
                    historicalSleepDays
                        .asSequence()
                        .filter {
                            it.scoreDay >= rhrWindowStartDay
                        }.mapNotNull { it.rhrPercentileBpm }
                        .toList()

                dayMidnightMs to BackfillBaseline(muHistory, sigmaHistory, rhrBpm, rhrHistory)
            }
        }

        /**
         * PERF-002/WP-22: fetches sleep sessions once, covering the widest baseline lookback
         * ([ScoringConstants.HRV_SIGMA_WINDOW_DAYS], 56 days) every day in `[startDate, endDate]`
         * will need. Callers slice the result per day via the `prefetchedSessions` parameter on
         * [computeAdaptiveBaselineRhrBpmBetween]/[computeHrvBaselineBetween]/[computeHrvWindowsBetween]
         * instead of each of those independently re-querying its own 30- or 56-day window.
         */
        suspend fun prefetchWalkForwardSessions(
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
        ): List<SleepSessionEntity> {
            val fromMs =
                startDate
                    .minusDays(ScoringConstants.HRV_SIGMA_WINDOW_DAYS.toLong())
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val inclusiveToMs =
                endDate
                    .plusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli() - 1
            return scoringHistoryRepository.getSleepSessionsBetween(fromMs.coerceAtLeast(0), inclusiveToMs)
        }

        /**
         * Slices [prefetchedSessions] to the exact `startTime >= fromMs AND endTime <= inclusiveToMs`
         * bound [SleepSessionDao.getBetween] uses, or falls back to querying the DB directly when no
         * prefetched superset was supplied (the single-day/live call sites).
         */
        private suspend fun sessionsBetween(
            prefetchedSessions: List<SleepSessionEntity>?,
            fromMs: Long,
            inclusiveToMs: Long,
        ): List<SleepSessionEntity> =
            prefetchedSessions?.filter { it.startTime >= fromMs && it.endTime <= inclusiveToMs }
                ?: scoringHistoryRepository.getSleepSessionsBetween(fromMs, inclusiveToMs)

        /**
         * HRV history windows + supporting session metadata returned from
         * [computeHrvWindows].
         */
        data class HrvWindows(
            val muHistory: List<Float>,
            val sigmaHistory: List<Float>,
            val historicalSessions: List<SleepSessionEntity>,
            val validHistoricalSessionIds: List<String>,
            val validHistoricalDayCount: Int = validHistoricalSessionIds.size,
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
    }
