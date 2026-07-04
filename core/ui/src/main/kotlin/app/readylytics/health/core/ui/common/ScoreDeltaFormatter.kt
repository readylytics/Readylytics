package app.readylytics.health.core.ui.common

import app.readylytics.health.core.ui.R

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
