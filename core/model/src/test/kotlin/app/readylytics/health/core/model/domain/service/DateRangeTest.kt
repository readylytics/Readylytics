package app.readylytics.health.core.model.domain.service

import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DateRangeTest {
    @Test
    fun `contains includes both endpoints and excludes outside dates`() {
        val start = LocalDate.of(2026, 6, 1)
        val end = LocalDate.of(2026, 6, 30)
        val range = DateRange(start, end)
        assertTrue(range.contains(start))
        assertTrue(range.contains(end))
        assertTrue(range.contains(LocalDate.of(2026, 6, 15)))
        assertFalse(range.contains(start.minusDays(1)))
        assertFalse(range.contains(end.plusDays(1)))
    }
}
