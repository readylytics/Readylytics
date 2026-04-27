package com.gregor.lauritz.healthdashboard.ui.common

import java.time.format.DateTimeFormatter
import java.util.Locale

object DateFormatUtils {
    const val DATE_FORMAT_SHORT = "dd.MM"

    val WORKOUT_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm").withLocale(Locale.getDefault())

    val WORKOUT_DATE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE, d MMMM").withLocale(Locale.getDefault())
}
