package app.readylytics.health.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

class BenchmarkSeedDataFactoryTest {
    private val today = LocalDate.of(2026, 10, 26)
    private val zoneId = ZoneId.of("Europe/Berlin")

    @Test
    fun `builds exactly 180 stable rows for each table`() {
        val first = buildBenchmarkSeedData(today, zoneId)
        val second = buildBenchmarkSeedData(today, zoneId)

        assertEquals(BENCHMARK_SEED_DAYS, first.summaries.size)
        assertEquals(BENCHMARK_SEED_DAYS, first.sleepSessions.size)
        assertEquals(first, second)
        assertEquals(
            BENCHMARK_SEED_DAYS,
            first.summaries
                .map { it.dateMidnightMs }
                .toSet()
                .size,
        )
        assertEquals(
            BENCHMARK_SEED_DAYS,
            first.sleepSessions
                .map { it.id }
                .toSet()
                .size,
        )
    }

    @Test
    fun `summary dates cover today through day 179 with non-zero load`() {
        val rows = buildBenchmarkSeedData(today, zoneId).summaries

        assertEquals(today.atStartOfDay(zoneId).toInstant().toEpochMilli(), rows.first().dateMidnightMs)
        assertEquals(
            today
                .minusDays(179)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli(),
            rows.last().dateMidnightMs,
        )
        assertTrue(rows.all { (it.trimpWorkoutOnly ?: 0f) > 0f })
        assertTrue(rows.all { (it.trimpEverydayHr ?: 0f) > 0f })
    }

    @Test
    fun `sleep rows end on represented dates and contain valid durations`() {
        val sessions = buildBenchmarkSeedData(today, zoneId).sleepSessions

        sessions.forEachIndexed { index, session ->
            val representedDate = today.minusDays(index.toLong())
            val start = java.time.Instant.ofEpochMilli(session.startTime)
            val end = java.time.Instant.ofEpochMilli(session.endTime)

            assertEquals("benchmark-sleep-$representedDate", session.id)
            assertEquals(representedDate, end.atZone(zoneId).toLocalDate())
            assertEquals(session.durationMinutes.toLong(), Duration.between(start, end).toMinutes())
            assertEquals(
                session.durationMinutes,
                session.deepSleepMinutes +
                    session.remSleepMinutes +
                    session.lightSleepMinutes +
                    session.awakeMinutes,
            )
            assertTrue(session.startTime < session.endTime)
            assertTrue(session.awakeMinutes in 0 until session.durationMinutes)
            assertTrue(session.efficiency in 0f..100f)
        }
    }
}
