package app.readylytics.health.core.ui.common

import app.readylytics.health.core.ui.R

/**
 * How a metric's value should be read: whether an increase is favourable ([HIGHER_IS_BETTER],
 * e.g. HRV) or unfavourable ([LOWER_IS_BETTER], e.g. resting heart rate). [NEUTRAL] opts out of
 * directional assessment (e.g. body temperature).
 */
enum class DeltaDirection {
    HIGHER_IS_BETTER,
    LOWER_IS_BETTER,
    NEUTRAL,
}

/** Directional interpretation of a delta against a [DeltaDirection]. */
enum class DeltaOutcome {
    IMPROVED,
    WORSENED,
    NEUTRAL,
}

/**
 * Classifies the rounded delta between [currentRounded] and [previousRounded] against
 * [direction]. Returns null when either value is missing.
 */
fun assessDeltaOutcome(
    currentRounded: Int?,
    previousRounded: Int?,
    direction: DeltaDirection,
): DeltaOutcome? {
    if (currentRounded == null || previousRounded == null) return null
    val diff = currentRounded - previousRounded
    return when {
        diff == 0 || direction == DeltaDirection.NEUTRAL -> DeltaOutcome.NEUTRAL
        diff > 0 ->
            if (direction == DeltaDirection.HIGHER_IS_BETTER) {
                DeltaOutcome.IMPROVED
            } else {
                DeltaOutcome.WORSENED
            }
        else ->
            if (direction == DeltaDirection.HIGHER_IS_BETTER) {
                DeltaOutcome.WORSENED
            } else {
                DeltaOutcome.IMPROVED
            }
    }
}

fun formatRoundedScoreDelta(
    currentRounded: Int?,
    previousRounded: Int?,
): UiText? {
    if (currentRounded == null || previousRounded == null) {
        return null
    }

    val diff = currentRounded - previousRounded
    return when {
        diff > 0 -> UiText.Compound(listOf(UiText.StringRes(R.string.delta_up), UiText.RawString(" $diff")))
        diff < 0 ->
            UiText.Compound(
                listOf(UiText.StringRes(R.string.delta_down), UiText.RawString(" ${kotlin.math.abs(diff)}")),
            )
        else -> UiText.StringRes(R.string.delta_no_change)
    }
}
