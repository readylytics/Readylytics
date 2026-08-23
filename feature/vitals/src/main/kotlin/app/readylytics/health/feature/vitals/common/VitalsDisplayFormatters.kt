package app.readylytics.health.feature.vitals.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.LocalExtendedColors
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.ui.R as CoreUiR
import java.text.NumberFormat

/**
 * Formatting helpers for vital signs display values and labels.
 */
object VitalsDisplayFormatters {
    /**
     * Format a metric value with NumberFormat.getNumberInstance() to add locale-aware grouping.
     */
    fun formatNumber(value: Int): String = NumberFormat.getNumberInstance().format(value)

    /**
     * Format optional metric values as formatted string or "--" fallback.
     */
    fun formatNumberOrDash(value: Int?): String =
        value?.let { formatNumber(it) } ?: "--"
}

/**
 * Get the string resource ID for a metric status label.
 */
internal fun metricStatusLabelRes(status: MetricStatus): Int =
    when (status) {
        MetricStatus.OPTIMAL -> CoreUiR.string.metric_status_optimal
        MetricStatus.NEUTRAL -> CoreUiR.string.metric_status_neutral
        MetricStatus.WARNING -> CoreUiR.string.metric_status_warning
        MetricStatus.POOR -> CoreUiR.string.metric_status_poor
        MetricStatus.NO_DATA, MetricStatus.CALIBRATING -> CoreUiR.string.metric_status_calibrating
    }

/**
 * Get the color for a heart rate zone based on zone number.
 * Zone 0: surface variant (no activity)
 * Zone 1: secondary container (very light)
 * Zone 2: primary container (light)
 * Zone 3: tertiary container (moderate)
 * Zone 4: warning container (hard)
 * Else: error container (unknown)
 */
@Composable
internal fun zoneColor(zone: Int): Color {
    val cs = MaterialTheme.colorScheme
    val ext = LocalExtendedColors.current
    return when (zone) {
        0 -> cs.surfaceVariant
        1 -> cs.secondaryContainer
        2 -> cs.primaryContainer
        3 -> cs.tertiaryContainer
        4 -> ext.warningContainer
        else -> cs.errorContainer
    }
}

/**
 * Format a duration in milliseconds to minutes for display.
 */
fun formatDurationToMinutes(durationMs: Long): Int = (durationMs / 60_000L).toInt()

/**
 * Format a blood pressure delta value with direction indicator.
 */
@Composable
fun formatBloodPressureDelta(diff: Int): String =
    when {
        diff > 0 ->
            stringResource(CoreUiR.string.delta_up) + " $diff " +
                stringResource(app.readylytics.health.core.ui.R.string.unit_mmHg)
        diff < 0 ->
            stringResource(CoreUiR.string.delta_down) + " ${kotlin.math.abs(diff)} " +
                stringResource(app.readylytics.health.core.ui.R.string.unit_mmHg)
        else -> stringResource(CoreUiR.string.delta_no_change)
    }

/**
 * Helper to compute blood pressure delta or null if baseline is missing.
 */
fun computeBloodPressureDelta(current: Int?, baseline: Int): Int? =
    current?.let { it - baseline }
