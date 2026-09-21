package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.components.Phase
import app.readylytics.health.core.scoring.domain.scoring.components.PhaseCalculator

/**
 * Explicit, resolved calibration/maturity state for a single scoring day (WP-12, OD-2 gate).
 *
 * This is deliberately separate from the HRV mu/sigma *statistical* windows
 * ([BaselineComputer.computeHrvWindowsBetween]/[ScoringConstants.HRV_MU_WINDOW_DAYS]/
 * [ScoringConstants.HRV_SIGMA_WINDOW_DAYS]): those stay bounded to a small rolling lookback for
 * baseline math and must not change. [observationCount] instead answers "how many eligible
 * sleep-days has the user accumulated toward calibration maturity" -- a cumulative, unbounded
 * count (see [ScoringHistoryRepository.countEligibleSleepDaysThrough]).
 *
 * - [observationCount]: `null` means unknown (never fabricated as zero, and never re-derived from
 *   an incompatible frozen count/phase pair -- see [resolveCalibrationState]).
 * - [phase]: `null` means the phase could not be resolved (unknown count, or a detected
 *   frozen-metadata inconsistency needing repair).
 * - [isCalibrating]: always explicitly set -- `true` whenever [phase] is unknown or
 *   [Phase.CALIBRATION], `false` otherwise. Never re-derived independently by a caller.
 */
data class CalibrationState(
    val observationCount: Int?,
    val phase: Phase?,
    val isCalibrating: Boolean,
)

/**
 * Resolves [CalibrationState] from a live (freshly-scanned) observation count and a persisted
 * frozen count/phase pair.
 *
 * A validated frozen snapshot always wins over a live recompute: a frozen day's phase was already
 * what the user was scored against, and re-deriving it from a possibly-incomplete live count
 * (e.g. a replay with no live history loaded) must never disagree with the stored value. "Frozen"
 * is detected from the persisted data itself ([frozenCount] and/or [frozenPhase] present) --
 * never inferred merely from a freeze timestamp.
 *
 * If the frozen count and frozen phase disagree (corrupted/inconsistent metadata), this is
 * treated as needing repair -- not as license to trust either value, and never a shortcut to
 * MATURE -- so both [CalibrationState.observationCount] and [CalibrationState.phase] resolve to
 * `null` and [CalibrationState.isCalibrating] resolves to `true` (the conservative default).
 *
 * `count == 0` is a known state (zero observations so far, definitely still calibrating) and is
 * never conflated with "unknown" (`null`).
 */
fun resolveCalibrationState(
    liveCount: Int?,
    frozenCount: Int?,
    frozenPhase: Phase?,
): CalibrationState {
    val frozen = frozenCount != null || frozenPhase != null
    val count = if (frozen) frozenCount else liveCount
    require(count == null || count >= 0) { "observation count must be non-negative, was $count" }
    val derived = count?.let(PhaseCalculator::calculatePhase)
    if (frozenPhase != null && derived != null && frozenPhase != derived) {
        return CalibrationState(observationCount = null, phase = null, isCalibrating = true)
    }
    val phase = frozenPhase ?: derived
    return CalibrationState(
        observationCount = count,
        phase = phase,
        isCalibrating = phase == null || phase == Phase.CALIBRATION,
    )
}
