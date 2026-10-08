package app.readylytics.health.feature.dashboard

import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

// Time-of-day gating applies to the current day; history is treated as end-of-day.
internal fun nowMinutesOfDayFor(
    selectedDate: LocalDate,
    clock: Clock,
): Int =
    if (selectedDate == LocalDate.now(clock)) {
        LocalTime.now(clock).let { it.hour * 60 + it.minute }
    } else {
        1439
    }
