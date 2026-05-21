package com.gregor.lauritz.healthdashboard.ui.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object ChartUtils {
    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern(DateFormatUtils.DATE_FORMAT_SHORT, Locale.getDefault())

    fun dayOffsetToLocalDate(
        dayOffset: Int,
        rangeStartMs: Long,
    ): LocalDate =
        Instant
            .ofEpochMilli(rangeStartMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .plusDays(dayOffset.toLong())

    fun formatTooltipDate(localDate: LocalDate): String = localDate.format(dateFormatter)

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
