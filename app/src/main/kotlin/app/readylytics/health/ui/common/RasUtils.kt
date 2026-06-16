package app.readylytics.health.ui.common

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.domain.scoring.LoadSourceMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

fun buildRasBreakdown(
    endDate: LocalDate,
    summaries: List<DailySummary>,
    rasSourceMode: LoadSourceMode,
): List<Pair<String, Float>> {
    val fmt = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
    return (6 downTo 0).map { daysBack ->
        val day = endDate.minusDays(daysBack.toLong())
        val entry = summaries.firstOrNull { it.date == day }
        val ras = entry?.let { LoadSourceSelector.selectDailyRas(it, rasSourceMode) }
        day.format(fmt) to (ras ?: 0f)
    }
}
