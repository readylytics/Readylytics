package app.readylytics.health.core.ui.components

import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

class DayTimelineScaleTest {
    @Test
    fun testNormal24HourDay() {
        val zone = ZoneId.of("Europe/Berlin")
        val date = LocalDate.of(2026, 6, 20)
        val startMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endExclusiveMs =
            date
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()

        val scale = DayTimelineScale(startMs, endExclusiveMs)
        assertEquals(24L * 60 * 60 * 1000, scale.durationMs)
        assertEquals(0f, scale.fraction(startMs))
        assertEquals(0.5f, scale.fraction(startMs + 12L * 60 * 60 * 1000))
        assertEquals(1f, scale.fraction(endExclusiveMs))
    }

    @Test
    fun testSpringForward23HourDay() {
        val zone = ZoneId.of("Europe/Berlin")
        val date = LocalDate.of(2026, 3, 29) // Spring forward DST
        val startMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endExclusiveMs =
            date
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()

        val scale = DayTimelineScale(startMs, endExclusiveMs)
        assertEquals(23L * 60 * 60 * 1000, scale.durationMs)
        assertEquals(0f, scale.fraction(startMs))
        assertEquals(0.5f, scale.fraction(startMs + 23L * 60 * 60 * 1000 / 2)) // 11.5 hours
        assertEquals(1f, scale.fraction(endExclusiveMs))
    }

    @Test
    fun testFallBack25HourDay() {
        val zone = ZoneId.of("Europe/Berlin")
        val date = LocalDate.of(2026, 10, 25) // Fall back DST
        val startMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endExclusiveMs =
            date
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()

        val scale = DayTimelineScale(startMs, endExclusiveMs)
        assertEquals(25L * 60 * 60 * 1000, scale.durationMs)
        assertEquals(0f, scale.fraction(startMs))
        assertEquals(0.5f, scale.fraction(startMs + 25L * 60 * 60 * 1000 / 2)) // 12.5 hours
        assertEquals(1f, scale.fraction(endExclusiveMs))
    }
}
