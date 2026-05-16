package com.gregor.lauritz.healthdashboard.ui.workouts.mappers

import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

object DailyPaiBreakdownMapper {
    fun mapDailyBreakdown(
        workoutDate: LocalDate,
        summaries: List<DailySummaryEntity>,
    ): List<Pair<String, Float>> {
        val summaryByDate = summaries.associateBy { it.dateMidnightMs }

        return (6 downTo 0).map { daysBack ->
            val day = workoutDate.minusDays(daysBack.toLong())
            val dayMs = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val label = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            val pai = summaryByDate[dayMs]?.paiScore ?: 0f
            label to pai
        }
    }
}
