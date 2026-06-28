package app.readylytics.health.feature.workouts.mappers

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.domain.scoring.LoadSourceMode
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

object DailyRasBreakdownMapper {
    fun mapDailyBreakdown(
        workoutDate: LocalDate,
        summaries: List<DailySummary>,
        rasSourceMode: LoadSourceMode,
    ): List<Pair<String, Float>> {
        val summaryByDate = summaries.associateBy { it.date }
        val locale = Locale.getDefault()

        return (6 downTo 0).map { daysBack ->
            val day = workoutDate.minusDays(daysBack.toLong())
            val label = day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            val ras = summaryByDate[day]?.let { LoadSourceSelector.selectDailyRas(it, rasSourceMode) } ?: 0f
            label to ras
        }
    }
}
