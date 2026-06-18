package app.readylytics.health.ui.sleep

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.sleepDurationStatus
import app.readylytics.health.domain.repository.SleepSessionData
import app.readylytics.health.ui.common.DateFormatUtils

private const val SLEEP_TIME_GOAL_FILL_RATIO = 0.5f

data class SleepTimeGaugeData(
    val progress: Float?,
    val displayText: String,
    val status: MetricStatus,
    val deltaText: String? = null,
)

internal fun buildSleepTimeGaugeData(
    session: SleepSessionData?,
    summary: DailySummary?,
    goalSleepHours: Float,
): SleepTimeGaugeData {
    val actualMinutes = actualSleepMinutes(session)
    val goalMinutes = sleepGoalMinutes(goalSleepHours)
    val maxMinutes = sleepTimeGaugeMaxMinutes(goalMinutes)

    val deltaText =
        if (actualMinutes != null && goalMinutes > 0) {
            val diffMinutes = actualMinutes - goalMinutes
            if (diffMinutes > 0) {
                "↑ ${formatSleepDiff(diffMinutes)}"
            } else if (diffMinutes < 0) {
                "↓ ${formatSleepDiff(-diffMinutes)}"
            } else {
                "Goal met"
            }
        } else {
            null
        }

    return SleepTimeGaugeData(
        progress = actualMinutes?.let { sleepTimeGaugeProgress(it, maxMinutes) },
        displayText = formatSleepTimeGaugeDuration(actualMinutes),
        status =
            if (actualMinutes != null && goalMinutes > 0) {
                summary?.sleepDurationStatus(goalMinutes) ?: MetricStatus.CALIBRATING
            } else {
                MetricStatus.CALIBRATING
            },
        deltaText = deltaText,
    )
}

internal fun actualSleepMinutes(session: SleepSessionData?): Int? =
    session?.let { (it.durationMinutes - it.awakeMinutes).coerceAtLeast(0) }

private fun formatSleepDiff(minutes: Int): String =
    if (minutes < 60) {
        "${minutes}m"
    } else {
        val hrs = minutes / 60
        val mins = minutes % 60
        if (mins == 0) "${hrs}h" else "${hrs}h ${mins}m"
    }

private fun formatSleepTimeGaugeDuration(minutes: Int?): String {
    if (minutes == null) return DateFormatUtils.formatSleepDuration(null)

    return if (minutes < 60) {
        "${minutes}m"
    } else {
        DateFormatUtils.formatSleepDuration(minutes)
    }
}

private fun sleepGoalMinutes(goalSleepHours: Float): Int = (goalSleepHours * 60f).toInt().coerceAtLeast(0)

private fun sleepTimeGaugeMaxMinutes(goalMinutes: Int): Float =
    if (goalMinutes > 0) {
        goalMinutes / SLEEP_TIME_GOAL_FILL_RATIO
    } else {
        1f
    }

private fun sleepTimeGaugeProgress(
    actualMinutes: Int,
    maxMinutes: Float,
): Float = (actualMinutes / maxMinutes).coerceIn(0f, 1f)
