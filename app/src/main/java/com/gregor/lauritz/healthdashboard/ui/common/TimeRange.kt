package com.gregor.lauritz.healthdashboard.ui.common

import java.time.LocalDate
import java.time.ZoneId

enum class TimeRange(
    val days: Int,
    val label: String,
) {
    SEVEN_DAYS(7, "7D"),
    THIRTY_DAYS(30, "30D"),
    SIX_MONTHS(180, "180D"),
    ;

    fun fromMs(baseDate: LocalDate): Long {
        val zoneId = ZoneId.systemDefault()
        return baseDate
            .atStartOfDay(zoneId)
            .minusDays(days.toLong() - 1)
            .toInstant()
            .toEpochMilli()
    }
}
