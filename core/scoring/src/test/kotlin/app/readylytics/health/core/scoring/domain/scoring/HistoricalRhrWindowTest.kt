package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class HistoricalRhrWindowTest {
    private fun night(day: LocalDate) = HistoricalSleepDay(day, listOf(day.toString()), 45f, 50f, 52, true)

    @Test
    fun `RHR window strictly excludes current day D, future nights and expired history`() {
        val d = LocalDate.of(2026, 3, 29)
        val lower = d.minusDays(ScoringConstants.BASELINE_DAYS)
        val input =
            listOf(
                night(lower.minusDays(1)),
                night(lower),
                night(d.minusDays(1)),
                night(d),
                night(d.plusDays(1)),
            )
        assertEquals(listOf(lower, d.minusDays(1)), historicalRhrWindow(input, d).map { it.scoreDay })
    }

    @Test
    fun `empty input yields empty window`() {
        val d = LocalDate.of(2026, 3, 29)
        assertEquals(emptyList<HistoricalSleepDay>(), historicalRhrWindow(emptyList(), d))
    }

    @Test
    fun `Europe Berlin spring DST boundary day is included by date, not by elapsed wall-clock hours`() {
        // 2026-03-29 is the German DST transition (clocks jump 02:00 -> 03:00).
        // Membership keys off scoreDay dates, strictly before scoreDay D.
        val d = LocalDate.of(2026, 3, 29)
        val lower = d.minusDays(ScoringConstants.BASELINE_DAYS)
        val input = listOf(night(lower), night(lower.plusDays(1)), night(d))
        assertEquals(listOf(lower, lower.plusDays(1)), historicalRhrWindow(input, d).map { it.scoreDay })
    }
}
