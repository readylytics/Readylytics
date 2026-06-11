package app.readylytics.health.ui.common

import app.readylytics.health.domain.model.DailySummary
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

fun buildPaiBreakdown(
    endDate: LocalDate,
    summaries: List<DailySummary>,
): List<Pair<String, Float>> {
    val fmt = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
    return (6 downTo 0).map { daysBack ->
        val day = endDate.minusDays(daysBack.toLong())
        val entry = summaries.firstOrNull { it.date == day }
        day.format(fmt) to (entry?.paiScore ?: 0f)
    }
}
