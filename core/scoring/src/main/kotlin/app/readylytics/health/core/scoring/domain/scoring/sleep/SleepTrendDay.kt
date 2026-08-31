package app.readylytics.health.core.scoring.domain.scoring.sleep

import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepTrendDay

import java.time.LocalDate

data class SleepTrendNap(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMinutes: Int,
)

data class SleepTrendDay(
    val dayOffset: Int,
    val scoreDay: LocalDate,
    val coreStartTimeMs: Long?,
    val coreEndTimeMs: Long?,
    val totalDurationMinutes: Int?,
    val naps: List<SleepTrendNap>,
)
