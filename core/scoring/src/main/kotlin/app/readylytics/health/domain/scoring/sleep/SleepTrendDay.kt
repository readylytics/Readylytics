package app.readylytics.health.domain.scoring.sleep

import java.time.LocalDate

data class SleepTrendNap(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMinutes: Int,
)

data class SleepTrendDay(
    val scoreDay: LocalDate,
    val coreStartTimeMs: Long?,
    val coreEndTimeMs: Long?,
    val totalDurationMinutes: Int?,
    val naps: List<SleepTrendNap>,
)
