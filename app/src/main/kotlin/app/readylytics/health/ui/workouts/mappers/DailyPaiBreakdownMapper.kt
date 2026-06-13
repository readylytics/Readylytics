package app.readylytics.health.ui.workouts.mappers

import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.DailySummary
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

object DailyPaiBreakdownMapper {
    fun mapDailyBreakdown(
        workoutDate: LocalDate,
        summaries: List<DailySummary>,
        prefs: UserPreferences,
    ): List<Pair<String, Float>> {
        val summaryByDate = summaries.associateBy { it.date }
        val locale = Locale.getDefault()

        return (6 downTo 0).map { daysBack ->
            val day = workoutDate.minusDays(daysBack.toLong())
            val label = day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            val entry = summaryByDate[day]
            val pai = if (prefs.allDayHrStrainEnabled) entry?.dailyHrPai else entry?.paiScore
            label to (pai ?: 0f)
        }
    }
}
