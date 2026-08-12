package app.readylytics.health.core.ui.common

import java.time.LocalDate
import java.time.ZoneId

enum class TrendGranularity {
    DAILY,
    MONTHLY,
    EIGHT_WEEK,
}

enum class TimeRange(
    val days: Int,
    val label: String,
    val granularity: TrendGranularity,
) {
    SEVEN_DAYS(7, "7D", TrendGranularity.DAILY),
    THIRTY_DAYS(30, "30D", TrendGranularity.DAILY),
    SIX_MONTHS(180, "180D", TrendGranularity.MONTHLY),
    TWELVE_MONTHS(360, "360D", TrendGranularity.EIGHT_WEEK),
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
