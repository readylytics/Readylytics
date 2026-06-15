package app.readylytics.health.ui.workouts.mappers

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.domain.scoring.LoadSourceMode
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

object DailyPaiBreakdownMapper {
    fun mapDailyBreakdown(
        workoutDate: LocalDate,
        summaries: List<DailySummary>,
        paiSourceMode: LoadSourceMode,
    ): List<Pair<String, Float>> {
        val summaryByDate = summaries.associateBy { it.date }
        val locale = Locale.getDefault()

        return (6 downTo 0).map { daysBack ->
            val day = workoutDate.minusDays(daysBack.toLong())
            val label = day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            val pai = summaryByDate[day]?.let { LoadSourceSelector.selectDailyPai(it, paiSourceMode) } ?: 0f
            label to pai
        }
    }
}
