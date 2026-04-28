package com.gregor.lauritz.healthdashboard.ui.common

import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun buildPaiBreakdown(
    endDate: LocalDate,
    summaries: List<DailySummaryEntity>,
): List<Pair<String, Float>> {
    val zoneId = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
    return (6 downTo 0).map { daysBack ->
        val day = endDate.minusDays(daysBack.toLong())
        val dayMs = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val entry = summaries.firstOrNull { it.dateMidnightMs == dayMs }
        day.format(fmt) to (entry?.paiScore ?: 0f)
    }
}
