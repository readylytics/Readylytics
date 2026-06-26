package app.readylytics.health.domain.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun Long.truncateToDayMs(): Long {
    val zoneId = ZoneId.systemDefault()
    return Instant
        .ofEpochMilli(this)
        .atZone(zoneId)
        .toLocalDate()
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()
}

fun LocalDate.toMidnightEpochMilli(zoneId: ZoneId = ZoneId.systemDefault()): Long =
    this.atStartOfDay(zoneId).toInstant().toEpochMilli()
