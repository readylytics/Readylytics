package app.readylytics.health.core.ui.common

import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ChartUtilsTest {
    @Test
    fun `tooltip date formatter is reused for the same locale`() {
        assertSame(
            ChartUtils.getTooltipDateFormatter(Locale.US),
            ChartUtils.getTooltipDateFormatter(Locale.US),
        )
    }

    @Test
    fun `tooltip date formatter is distinct for different locales`() {
        assertNotSame(
            ChartUtils.getTooltipDateFormatter(Locale.US),
            ChartUtils.getTooltipDateFormatter(Locale.GERMANY),
        )
    }

    @Test
    fun `day offset date uses the supplied scoring zone`() {
        val scoringZone = ZoneId.of("Pacific/Kiritimati")
        val rangeStart =
            LocalDate
                .of(2026, 8, 2)
                .atStartOfDay(scoringZone)
                .toInstant()
                .toEpochMilli()

        assertEquals(
            LocalDate.of(2026, 8, 3),
            ChartUtils.dayOffsetToLocalDate(dayOffset = 1, rangeStartMs = rangeStart, zoneId = scoringZone),
        )
    }
}
