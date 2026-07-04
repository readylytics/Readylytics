package app.readylytics.health.feature.vitals.heartrate

import app.readylytics.health.core.ui.model.HrSample
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HrTimelineChartStateTest {
    @Test
    fun testSplitIntoSegments() {
        val samples =
            listOf(
                HrSample(1000L, 70, 0),
                HrSample(2000L, 72, 0),
                // Gap > 10s (e.g. threshold = 5s)
                HrSample(8000L, 75, 0),
                HrSample(9000L, 71, 0),
            )
        val segments = HrChartHelper.splitIntoSegments(samples, 5000L)
        assertEquals(2, segments.size)
        assertEquals(2, segments[0].size)
        assertEquals(2, segments[1].size)
        assertEquals(1000L, segments[0][0].timeMs)
        assertEquals(8000L, segments[1][0].timeMs)
    }

    @Test
    fun testGenerateHourLabelsNormalDay() {
        val zone = ZoneId.of("Europe/Berlin")
        val date = LocalDate.of(2026, 6, 20)
        val startMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endExclusiveMs =
            date
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()

        val labels = HrChartHelper.generateHourLabels(startMs, endExclusiveMs, zone)
        // Should have 00:00, 04:00, 08:00, 12:00, 16:00, 20:00
        assertEquals(6, labels.size)
        assertEquals("00:00", labels[0].second)
        assertEquals("04:00", labels[1].second)
        assertEquals("08:00", labels[2].second)
        assertEquals("12:00", labels[3].second)
        assertEquals("16:00", labels[4].second)
        assertEquals("20:00", labels[5].second)
    }

    @Test
    fun testGenerateHourLabelsSpringForward() {
        val zone = ZoneId.of("Europe/Berlin")
        val date = LocalDate.of(2026, 3, 29) // Spring forward DST
        val startMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endExclusiveMs =
            date
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()

        val labels = HrChartHelper.generateHourLabels(startMs, endExclusiveMs, zone)
        assertEquals(6, labels.size)
        assertEquals("00:00", labels[0].second)
        assertEquals("04:00", labels[1].second)
    }

    @Test
    fun testGenerateHourLabelsFallBack() {
        val zone = ZoneId.of("Europe/Berlin")
        val date = LocalDate.of(2026, 10, 25) // Fall back DST
        val startMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endExclusiveMs =
            date
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()

        val labels = HrChartHelper.generateHourLabels(startMs, endExclusiveMs, zone)
        assertEquals(6, labels.size)
        assertEquals("00:00", labels[0].second)
        assertEquals("04:00", labels[1].second)
    }
}
