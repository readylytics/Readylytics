package app.readylytics.health.core.ui.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

object ChartUtils {
    private val tooltipDateFormatters = ConcurrentHashMap<Locale, DateTimeFormatter>()

    // DateTimeFormatter is immutable; the locale set is small and reused across tooltip taps.
    internal fun getTooltipDateFormatter(locale: Locale = Locale.getDefault()): DateTimeFormatter =
        tooltipDateFormatters.getOrPut(locale) {
            DateTimeFormatter.ofPattern(DateFormatUtils.DATE_FORMAT_SHORT, locale)
        }

    fun dayOffsetToLocalDate(
        dayOffset: Int,
        rangeStartMs: Long,
    ): LocalDate = dayOffsetToLocalDate(dayOffset, rangeStartMs, ZoneId.systemDefault())

    fun dayOffsetToLocalDate(
        dayOffset: Int,
        rangeStartMs: Long,
        zoneId: ZoneId,
    ): LocalDate =
        Instant
            .ofEpochMilli(rangeStartMs)
            .atZone(zoneId)
            .toLocalDate()
            .plusDays(dayOffset.toLong())

    fun formatTooltipDate(localDate: LocalDate): String = localDate.format(getTooltipDateFormatter())

    fun formatTooltipValue(
        value: Float,
        unit: String,
    ): String {
        val intValue = value.roundToInt()
        return "$intValue $unit"
    }

    fun formatTooltipText(
        metricName: String,
        value: Float,
        unit: String,
        dateString: String,
    ): Pair<String, String> {
        val valueText = formatTooltipValue(value, unit)
        val tooltipValue = "$metricName: $valueText"
        val tooltipDate = "Date: $dateString"
        return tooltipValue to tooltipDate
    }
}
