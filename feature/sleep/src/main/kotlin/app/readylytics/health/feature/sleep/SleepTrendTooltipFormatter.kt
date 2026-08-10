package app.readylytics.health.feature.sleep

import androidx.compose.ui.unit.IntOffset
import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.common.DateFormatUtils
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.core.ui.components.DataPointTooltipData
import java.text.DateFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

internal data class SleepTrendTooltipStrings(
    val durationFormat: String,
    val bedtimeFormat: String,
    val napsHeading: String,
    val napItemFormat: String,
    val avgDurationFormat: String,
    val avgBedtimeFormat: String,
    val quarterLabelFormat: String,
)

internal fun buildSleepTrendTooltipData(
    selectedState: SleepTrendSelectedState,
    rangeStartMs: Long,
    scoringZoneId: ZoneId,
    clockFormatter: DateFormat,
    strings: SleepTrendTooltipStrings,
    locale: Locale = Locale.getDefault(),
    granularity: TrendGranularity = TrendGranularity.DAILY,
): DataPointTooltipData {
    val scoringClockFormatter =
        (clockFormatter.clone() as DateFormat).apply {
            timeZone = TimeZone.getTimeZone(scoringZoneId)
        }
    val date = ChartUtils.dayOffsetToLocalDate(selectedState.dayOffset, rangeStartMs, scoringZoneId)

    if (granularity != TrendGranularity.DAILY) {
        val periodLabel = when (granularity) {
            TrendGranularity.MONTHLY -> date.format(DateTimeFormatter.ofPattern("MMM", locale))
            TrendGranularity.QUARTERLY -> String.format(locale, strings.quarterLabelFormat, ((date.monthValue - 1) / 3) + 1)
            TrendGranularity.DAILY -> ChartUtils.formatTooltipDate(date)
        }
        val duration = DateFormatUtils.formatSleepDuration(
            ((selectedState.actualDurationValue ?: 0f) * 60f).roundToInt(),
        )
        val avgDurationText = String.format(locale, strings.avgDurationFormat, duration)
        val avgBedtimeText = buildAvgBedtimeString(selectedState.startOffsetValue, locale)

        return DataPointTooltipData(
            valueText = periodLabel,
            dateText = avgDurationText,
            preDateLines = listOf(avgBedtimeText),
            offset =
                IntOffset(
                    selectedState.canvasX.toInt(),
                    (selectedState.lineCanvasY ?: selectedState.barCanvasYTop ?: 0f).toInt(),
                ),
        )
    }

    val duration =
        DateFormatUtils.formatSleepDuration(
            ((selectedState.actualDurationValue ?: 0f) * 60f).roundToInt(),
        )

    fun formatClock(epochMs: Long?): String = epochMs?.let { scoringClockFormatter.format(Date(it)) } ?: "—"

    val detailLines =
        buildList {
            add(
                String.format(
                    locale,
                    strings.bedtimeFormat,
                    formatClock(selectedState.coreStartTimeMs),
                    formatClock(selectedState.coreEndTimeMs),
                ),
            )
            if (selectedState.naps.isNotEmpty()) {
                add(strings.napsHeading)
                selectedState.naps.forEach { nap ->
                    add(
                        String.format(
                            locale,
                            strings.napItemFormat,
                            formatClock(nap.startTimeMs),
                            formatClock(nap.endTimeMs),
                            DateFormatUtils.formatSleepDuration(nap.durationMinutes),
                        ),
                    )
                }
            }
        }

    return DataPointTooltipData(
        valueText = ChartUtils.formatTooltipDate(date),
        dateText = String.format(locale, strings.durationFormat, duration),
        preDateLines = detailLines,
        offset =
            IntOffset(
                selectedState.canvasX.toInt(),
                (selectedState.lineCanvasY ?: selectedState.barCanvasYTop ?: 0f).toInt(),
            ),
    )
}

private fun buildAvgBedtimeString(startOffsetValue: Float?, locale: Locale): String {
    if (startOffsetValue == null) return "—"
    val totalHours = 12f + startOffsetValue
    val hour = (totalHours.toInt() % 24).let { if (it == 0) 12 else if (it > 12) it - 12 else it }
    val minute = ((totalHours - totalHours.toInt()) * 60).roundToInt()
    val amPm = if (totalHours.toInt() % 24 >= 12) "PM" else "AM"
    return String.format(locale, "%d:%02d %s", hour, minute, amPm)
}
