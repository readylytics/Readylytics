package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.model.TimestampedTrimp
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

class TrimpDateBucketerTest {
    @Test
    fun `buckets Berlin samples with each instant offset across DST transitions`() {
        assertTransitionBuckets(ZoneId.of("Europe/Berlin"), LocalDate.of(2025, 3, 30))
        assertTransitionBuckets(ZoneId.of("Europe/Berlin"), LocalDate.of(2025, 10, 26))
    }

    @Test
    fun `buckets New York samples with each instant offset across DST transitions`() {
        assertTransitionBuckets(ZoneId.of("America/New_York"), LocalDate.of(2025, 3, 9))
        assertTransitionBuckets(ZoneId.of("America/New_York"), LocalDate.of(2025, 11, 2))
    }

    @Test
    fun `matches per-record instant conversion for every day of DST zones`() {
        listOf(ZoneId.of("Europe/Berlin"), ZoneId.of("America/New_York")).forEach { zoneId ->
            val samples =
                generateSequence(LocalDate.of(2025, 1, 1)) { it.plusDays(1) }
                    .take(365)
                    .flatMap { date ->
                        sequenceOf(
                            timestampedTrimp(date, 0, zoneId, 1f),
                            timestampedTrimp(date, 12, zoneId, 2f),
                            timestampedTrimp(date, 23, zoneId, 3f),
                        )
                    }.toList()

            val expected =
                samples
                    .groupBy { Instant.ofEpochMilli(it.timestampMs).atZone(zoneId).toLocalDate() }
                    .mapValues { (_, values) -> values.sumOf { it.trimp.toDouble() }.toFloat() }

            assertEquals(expected, TrimpDateBucketer.bucket(samples, zoneId))
        }
    }

    private fun assertTransitionBuckets(
        zoneId: ZoneId,
        transitionDate: LocalDate,
    ) {
        val dates = (-2L..2L).map(transitionDate::plusDays)
        val samples = dates.map { timestampedTrimp(it, 0, zoneId, 1f) }

        val result = TrimpDateBucketer.bucket(samples, zoneId)

        assertEquals(dates.associateWith { 1f }, result)
    }

    private fun timestampedTrimp(
        date: LocalDate,
        hour: Int,
        zoneId: ZoneId,
        trimp: Float,
    ): TimestampedTrimp =
        TimestampedTrimp(
            timestampMs =
                date
                    .atTime(hour, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli(),
            trimp = trimp,
        )
}
