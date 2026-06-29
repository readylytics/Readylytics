package app.readylytics.health.core.ui.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateFormatUtils {
    const val DATE_FORMAT_SHORT = "dd.MM"

    fun getWorkoutTimeFormatter(): DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm").withLocale(Locale.getDefault())

    fun getWorkoutDateFormatter(): DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE, d MMMM").withLocale(Locale.getDefault())

    fun epochMilliToTimeString(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(getWorkoutTimeFormatter())

    fun nightSectionLabel(
        date: LocalDate,
        today: LocalDate,
    ): String =
        when (date) {
            today -> "Last Night"
            today.minusDays(1) -> "Night of Yesterday"
            else -> {
                val pattern = DateTimeFormatter.ofPattern("EEE MMM d", Locale.getDefault())
                "Night of ${date.format(pattern)}"
            }
        }

    fun formatSleepDuration(minutes: Int?): String {
        if (minutes == null) return "—"
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0) "${h}h" else "${h}h ${m}m"
    }

    fun truncateToDayMs(ms: Long): Long =
        Instant
            .ofEpochMilli(ms)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
