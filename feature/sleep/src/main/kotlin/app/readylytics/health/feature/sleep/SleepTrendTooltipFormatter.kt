package app.readylytics.health.feature.sleep

import androidx.compose.ui.unit.IntOffset
import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.common.DateFormatUtils
import app.readylytics.health.core.ui.components.DataPointTooltipData
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

internal data class SleepTrendTooltipStrings(
    val durationFormat: String,
    val bedtimeFormat: String,
    val napsHeading: String,
    val napItemFormat: String,
)

internal fun buildSleepTrendTooltipData(
    selectedState: SleepTrendSelectedState,
    rangeStartMs: Long,
    scoringZoneId: ZoneId,
    clockFormatter: DateFormat,
    strings: SleepTrendTooltipStrings,
    locale: Locale = Locale.getDefault(),
): DataPointTooltipData {
    val scoringClockFormatter =
        (clockFormatter.clone() as DateFormat).apply {
            timeZone = TimeZone.getTimeZone(scoringZoneId)
        }
    val date = ChartUtils.dayOffsetToLocalDate(selectedState.dayOffset, rangeStartMs, scoringZoneId)
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
