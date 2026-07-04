package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.model.TimestampedTrimp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object TrimpDateBucketer {
    fun bucket(
        points: List<TimestampedTrimp>,
        zoneId: ZoneId,
    ): Map<LocalDate, Float> =
        points
            .groupBy { Instant.ofEpochMilli(it.timestampMs).atZone(zoneId).toLocalDate() }
            .mapValues { (_, values) -> values.sumOf { it.trimp.toDouble() }.toFloat() }
            .toSortedMap()
}
