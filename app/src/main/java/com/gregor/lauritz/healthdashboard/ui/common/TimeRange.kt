package com.gregor.lauritz.healthdashboard.ui.common

import java.util.Calendar

enum class TimeRange(
    val days: Int,
    val label: String,
) {
    SEVEN_DAYS(7, "7D"),
    THIRTY_DAYS(30, "30D"),
    SIX_MONTHS(180, "180D"),
    ;

    fun fromMs(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, -days)
        return cal.timeInMillis
    }
}
