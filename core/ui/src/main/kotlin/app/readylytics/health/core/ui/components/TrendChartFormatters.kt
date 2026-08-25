package app.readylytics.health.core.ui.components

import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.core.ui.common.bucketLengthDays
import app.readylytics.health.core.ui.common.bucketStartForDate
import app.readylytics.health.core.ui.common.periodLabelFor
import java.time.temporal.IsoFields
import java.util.Locale
import kotlin.math.roundToInt

internal fun formatTrendTooltipValue(
    value: Float?,
    decimalPlaces: Int,
    hideUnit: Boolean,
    unit: String,
): String {
    if (value == null) return "—"
    val formatted =
        if (decimalPlaces == 0) {
            value.roundToInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.${decimalPlaces}f", value)
        }
    return if (hideUnit) formatted else "$formatted $unit"
}

fun formatTrendTooltipDate(
    granularity: TrendGranularity,
    date: java.time.LocalDate,
    ordinalLabel: (Int) -> String,
    weekRangeTemplate: String = "",
): String =
    when (granularity) {
        TrendGranularity.DAILY -> ChartUtils.formatTooltipDate(date)
        TrendGranularity.EIGHT_WEEK -> {
            val bucketStart = bucketStartForDate(date, TrendGranularity.EIGHT_WEEK)
            val startWeek = bucketStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            val endWeek = startWeek + (bucketLengthDays(bucketStart, TrendGranularity.EIGHT_WEEK) / 7) - 1
            String.format(Locale.getDefault(), weekRangeTemplate, startWeek, endWeek)
        }
        else -> periodLabelFor(granularity, date, ordinalLabel)
    }

internal fun formatBaselineLegendText(
    value: Float?,
    unit: String,
    label: String,
    decimalPlaces: Int,
    unavailableValueLabel: String?,
): String? =
    when {
        value != null -> {
            val formattedValue =
                if (decimalPlaces == 0) {
                    value.roundToInt().toString()
                } else {
                    String.format(Locale.getDefault(), "%.${decimalPlaces}f", value)
                }
            "$label: $formattedValue $unit"
        }
        unavailableValueLabel != null -> "$label: $unavailableValueLabel"
        else -> null
    }

internal fun shouldProcessTrendMarker(parentScrollInProgress: Boolean): Boolean = !parentScrollInProgress

internal fun <T> shouldAssignTrendMarkerState(
    current: T,
    next: T,
): Boolean = current != next
