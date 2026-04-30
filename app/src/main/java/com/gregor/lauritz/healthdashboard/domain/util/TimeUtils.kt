package com.gregor.lauritz.healthdashboard.domain.util

import java.time.Instant
import java.time.ZoneId

fun Long.truncateToDayMs(): Long {
    val zoneId = ZoneId.systemDefault()
    return Instant.ofEpochMilli(this)
        .atZone(zoneId)
        .toLocalDate()
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()
}
