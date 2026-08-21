package app.readylytics.health.feature.sleep

import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepTrendNap
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class SleepTrendTooltipFormatterTest {
    @Test
    fun `tooltip formats core nap and score day in the configured scoring zone`() {
        val scoringZone = ZoneId.of("Pacific/Kiritimati")
        val scoreDay = LocalDate.of(2026, 8, 2)
        val rangeStartMs = scoreDay.atStartOfDay(scoringZone).toInstant().toEpochMilli()
        val coreStart =
            scoreDay
                .minusDays(1)
                .atTime(23, 15)
                .atZone(scoringZone)
                .toInstant()
                .toEpochMilli()
        val coreEnd =
            scoreDay
                .atTime(7, 15)
                .atZone(scoringZone)
                .toInstant()
                .toEpochMilli()
        val napStart =
            scoreDay
                .atTime(13, 5)
                .atZone(scoringZone)
                .toInstant()
                .toEpochMilli()
        val napEnd =
            scoreDay
                .atTime(13, 40)
                .atZone(scoringZone)
                .toInstant()
                .toEpochMilli()

        val result =
            buildSleepTrendTooltipData(
                selectedState =
                    SleepTrendSelectedState(
                        dayOffset = 0,
                        startOffsetValue = 11.25f,
                        durationSpanValue = 8f,
                        actualDurationValue = 515f / 60f,
                        canvasX = 40f,
                        barCanvasYTop = 20f,
                        barCanvasYBottom = 80f,
                        lineCanvasY = 15f,
                        coreStartTimeMs = coreStart,
                        coreEndTimeMs = coreEnd,
                        naps = listOf(SleepTrendNap(napStart, napEnd, 35)),
                    ),
                rangeStartMs = rangeStartMs,
                scoringZoneId = scoringZone,
                clockFormatter = SimpleDateFormat("HH:mm", Locale.US),
                strings =
                    SleepTrendTooltipStrings(
                        durationFormat = "Duration: %1\$s",
                        bedtimeFormat = "Bedtime: %1\$s - %2\$s",
                        napsHeading = "Naps:",
                        napItemFormat = "• %1\$s – %2\$s (%3\$s)",
                        avgDurationFormat = "Avg. Duration: %1\$s",
                        avgBedtimeFormat = "Avg. Bedtime: %1\$s",
                        quarterLabelFormat = "Q%d",
                    ),
                locale = Locale.US,
            )

        assertEquals("02.08", result.valueText)
        assertEquals("Duration: 8h 35m", result.dateText)
        assertEquals(
            listOf(
                "Bedtime: 23:15 - 07:15",
                "Naps:",
                "• 13:05 – 13:40 (0h 35m)",
            ),
            result.preDateLines,
        )
        assertEquals(null, result.extraLine)
    }
}
