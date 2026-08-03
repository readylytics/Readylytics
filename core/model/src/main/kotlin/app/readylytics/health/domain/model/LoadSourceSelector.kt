package app.readylytics.health.domain.model

import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.scoring.LoadCoverageConfidence
import app.readylytics.health.domain.scoring.LoadSourceMode
import java.time.LocalDate

/**
 * Pure projection of the dual-variant (`*WorkoutOnly`/`*EverydayHr`) columns on [DailySummary]
 * into a single value based on the user's selected [LoadSourceMode] for strain/load metrics
 * ([UserPreferences.strainLoadSourceMode]) and RAS metrics ([UserPreferences.rasSourceMode]).
 *
 * Zero Android dependencies. No scoring formulas — selection/projection only.
 */
object LoadSourceSelector {
    fun selectTrimp(
        summary: DailySummary,
        mode: LoadSourceMode,
    ): Float? =
        when (mode) {
            LoadSourceMode.WORKOUT_ONLY -> summary.trimpWorkoutOnly
            LoadSourceMode.EVERYDAY_HEART_RATE -> summary.trimpEverydayHr
        }

    fun selectAtl(
        summary: DailySummary,
        mode: LoadSourceMode,
    ): Float? =
        when (mode) {
            LoadSourceMode.WORKOUT_ONLY -> summary.atlWorkoutOnly
            LoadSourceMode.EVERYDAY_HEART_RATE -> summary.atlEverydayHr
        }

    fun selectCtl(
        summary: DailySummary,
        mode: LoadSourceMode,
    ): Float? =
        when (mode) {
            LoadSourceMode.WORKOUT_ONLY -> summary.ctlWorkoutOnly
            LoadSourceMode.EVERYDAY_HEART_RATE -> summary.ctlEverydayHr
        }

    fun selectStrainRatio(
        summary: DailySummary,
        mode: LoadSourceMode,
    ): Float? =
        when (mode) {
            LoadSourceMode.WORKOUT_ONLY -> summary.strainRatioWorkoutOnly
            LoadSourceMode.EVERYDAY_HEART_RATE -> summary.strainRatioEverydayHr
        }

    fun selectLoadScore(
        summary: DailySummary,
        mode: LoadSourceMode,
    ): Float? =
        when (mode) {
            LoadSourceMode.WORKOUT_ONLY -> summary.loadScoreWorkoutOnly
            LoadSourceMode.EVERYDAY_HEART_RATE -> summary.loadScoreEverydayHr
        }

    fun selectReadiness(
        summary: DailySummary,
        mode: LoadSourceMode,
    ): Float? =
        when (mode) {
            LoadSourceMode.WORKOUT_ONLY -> summary.readinessWorkoutOnly
            LoadSourceMode.EVERYDAY_HEART_RATE -> summary.readinessEverydayHr
        }

    fun selectDailyRas(
        summary: DailySummary,
        mode: LoadSourceMode,
    ): Float? =
        when (mode) {
            LoadSourceMode.WORKOUT_ONLY -> summary.rasWorkoutOnly
            LoadSourceMode.EVERYDAY_HEART_RATE -> summary.rasEverydayHr
        }

    fun selectTotalRas(
        summary: DailySummary,
        mode: LoadSourceMode,
    ): Float? =
        when (mode) {
            LoadSourceMode.WORKOUT_ONLY -> summary.totalRasWorkoutOnly
            LoadSourceMode.EVERYDAY_HEART_RATE -> summary.totalRasEverydayHr
        }

    /**
     * Earliest date with usable [LoadSourceMode.EVERYDAY_HEART_RATE] data, derived from
     * [summaries] already being observed by the caller rather than a separate DB round trip.
     * Sound because daily summaries exist densely (one row per calendar day the app has
     * ingested), so a caller-fetched window is a safe proxy for true earliest history — unlike
     * workout events, which are sparse and require an unbounded DB query for correct tenure
     * (see `WorkoutRepository.getEarliestWorkoutTimestamp`, used directly by
     * [LoadSourceMode.WORKOUT_ONLY] callers instead of this function). Only counts rows whose
     * everyday-HR TRIMP has actually been computed — a row that exists but hasn't been
     * backfilled yet (see [needsRecalc]) isn't usable data.
     */
    fun selectEarliestDataDate(summaries: List<DailySummary>): LocalDate? =
        summaries
            .filter { selectTrimp(it, LoadSourceMode.EVERYDAY_HEART_RATE) != null }
            .minOfOrNull { it.date }

    /**
     * True when the user selected [LoadSourceMode.EVERYDAY_HEART_RATE] for strain/load or RAS,
     * but the corresponding everyday-HR variant column hasn't been computed/persisted yet for
     * this row.
     */
    fun needsRecalc(
        summary: DailySummary,
        prefs: UserPreferences,
    ): Boolean {
        val strainNeedsRecalc =
            prefs.strainLoadSourceMode == LoadSourceMode.EVERYDAY_HEART_RATE &&
                (
                    selectTrimp(summary, LoadSourceMode.EVERYDAY_HEART_RATE) == null ||
                        selectLoadScore(summary, LoadSourceMode.EVERYDAY_HEART_RATE) == null
                )
        val paiNeedsRecalc =
            prefs.rasSourceMode == LoadSourceMode.EVERYDAY_HEART_RATE &&
                (
                    selectDailyRas(summary, LoadSourceMode.EVERYDAY_HEART_RATE) == null ||
                        selectTotalRas(summary, LoadSourceMode.EVERYDAY_HEART_RATE) == null
                )
        return strainNeedsRecalc || paiNeedsRecalc
    }

    /**
     * True only when the user selected [LoadSourceMode.EVERYDAY_HEART_RATE] for strain/load AND
     * the row's [DailySummary.everydayLoadConfidence] is below [LoadCoverageConfidence.HIGH]
     * (i.e. `NONE`, `LOW`, or `MEDIUM`). A `null`/unparseable confidence under
     * EVERYDAY_HEART_RATE is treated as not-yet-computed (covered by [needsRecalc]), not as
     * low-confidence, so it returns `false` here.
     */
    fun readinessLowConfidence(
        summary: DailySummary,
        prefs: UserPreferences,
    ): Boolean {
        if (prefs.strainLoadSourceMode != LoadSourceMode.EVERYDAY_HEART_RATE) return false
        val confidence =
            summary.everydayLoadConfidence?.let {
                runCatching {
                    LoadCoverageConfidence.valueOf(
                        it,
                    )
                }.getOrNull()
            }
        return confidence != null && confidence != LoadCoverageConfidence.HIGH
    }
}
