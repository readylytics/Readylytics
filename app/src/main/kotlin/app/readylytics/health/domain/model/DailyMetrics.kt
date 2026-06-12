package app.readylytics.health.domain.model

import java.time.LocalDate

/**
 * Canonical, rounding-safe projection of a single [DailySummary] for a date.
 *
 * Produced solely by [DailyMetricsMapper] inside the repository layer. UI, ViewModels,
 * providers, and charts read the pre-rounded/display fields here — they must never
 * re-derive baselines or re-apply rounding/formatting on metric values.
 *
 * Raw fields preserve the source values (continuous Floats) for chart axis geometry.
 * `*Rounded` fields are integer display values; `*Display` strings carry non-integer
 * formats. Null in → null out (UI renders an em dash).
 */
data class DailyMetrics(
    val date: LocalDate,
    // --- Raw passthrough (continuous values, e.g. for chart axis geometry) ---
    val nocturnalRhrRaw: Int? = null,
    val nocturnalHrvRaw: Int? = null,
    val rhrBaselineRaw: Float? = null,
    val hrvBaselineMeanRaw: Float? = null,
    val hrvBaselineSdRaw: Float? = null,
    val rhrSnapshotRaw: Float? = null,
    // --- Rounded display integers ---
    val nocturnalRhrRounded: Int? = null,
    val nocturnalHrvRounded: Int? = null,
    val restingHeartRateRounded: Int? = null,
    val rhrBaselineRounded: Int? = null,
    val hrvBaselineRounded: Int? = null,
    val sleepScoreRounded: Int? = null,
    val readinessRounded: Int? = null,
    val loadScoreRounded: Int? = null,
    val restorationRounded: Int? = null,
    val trimpRounded: Int? = null,
    val paiRounded: Int? = null,
    val paiDayScoreRounded: Int? = null,
    val spo2Rounded: Int? = null,
    // --- Baseline diffs + arrows (precomputed so tooltips need no recompute) ---
    val rhrBaselineDiff: Int? = null,
    val hrvBaselineDiff: Int? = null,
    val restingHrBaselineDiff: Int? = null,
    val rhrBaselineArrow: BaselineArrow? = null,
    val hrvBaselineArrow: BaselineArrow? = null,
    val restingHrBaselineArrow: BaselineArrow? = null,
    // --- Display strings (non-integer formats) ---
    val sleepDurationDisplay: String? = null,
    val weightKgDisplay: String? = null,
    val weightLbsDisplay: String? = null,
    val bodyFatDisplay: String? = null,
    val strainRatioDisplay: String? = null,
    val zLnHrvDisplay: String? = null,
    val hrvSigmaDisplay: String? = null,
    val bloodPressureDisplay: String? = null,
    val deepSleepPercentDisplay: String? = null,
    val remSleepPercentDisplay: String? = null,
)

/**
 * Direction of a measured value relative to its baseline. Mapped once in
 * [DailyMetricsMapper]; UI renders the glyph via [symbol].
 */
enum class BaselineArrow(
    val symbol: String,
) {
    UP("↑"),
    DOWN("↓"),
    EQUAL("="),
}
