package app.readylytics.health.ui.common

internal fun formatRoundedScoreDelta(
    currentRounded: Int?,
    previousRounded: Int?,
): String {
    if (currentRounded == null || previousRounded == null) {
        return "—"
    }

    val diff = currentRounded - previousRounded
    return when {
        diff > 0 -> "↑ $diff"
        diff < 0 -> "↓ ${kotlin.math.abs(diff)}"
        else -> "—"
    }
}
