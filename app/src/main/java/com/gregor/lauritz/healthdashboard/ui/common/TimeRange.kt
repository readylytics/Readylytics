package com.gregor.lauritz.healthdashboard.ui.common

import java.util.concurrent.TimeUnit

enum class TimeRange(
    val days: Int,
    val label: String,
) {
    SEVEN_DAYS(7, "7D"),
    THIRTY_DAYS(30, "30D"),
    SIX_MONTHS(180, "180D"),
    ;

    fun fromMs(): Long = System.currentTimeMillis() - TimeUnit.DAYS.toMillis((days - 1).toLong())
}
