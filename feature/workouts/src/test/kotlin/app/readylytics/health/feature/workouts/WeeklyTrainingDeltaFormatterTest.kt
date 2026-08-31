package app.readylytics.health.feature.workouts

import org.junit.Assert.assertEquals
import org.junit.Test

class WeeklyTrainingDeltaFormatterTest {
    @Test
    fun `duration formats minutes below an hour`() {
        assertEquals("42m", WeeklyTrainingDeltaFormatter.formatDuration(42))
    }

    @Test
    fun `duration formats hours and minutes`() {
        assertEquals("3h 42m", WeeklyTrainingDeltaFormatter.formatDuration(222))
    }

    @Test
    fun `duration formats whole hours without minutes`() {
        assertEquals("2h", WeeklyTrainingDeltaFormatter.formatDuration(120))
    }

    @Test
    fun `zero duration formats as zero minutes`() {
        assertEquals("0m", WeeklyTrainingDeltaFormatter.formatDuration(0))
    }

    @Test
    fun `duration delta shows sign and rounded percent`() {
        assertEquals("+24m (+12%)", WeeklyTrainingDeltaFormatter.formatDurationDelta(24, 12.4f))
    }

    @Test
    fun `duration delta over an hour formats hours`() {
        assertEquals("+1h 5m (+30%)", WeeklyTrainingDeltaFormatter.formatDurationDelta(65, 30f))
    }

    @Test
    fun `duration delta omits percent when previous week was zero`() {
        assertEquals("+24m", WeeklyTrainingDeltaFormatter.formatDurationDelta(24, null))
    }

    @Test
    fun `duration delta shows negative sign on both absolute and percent`() {
        assertEquals("-30m (-25%)", WeeklyTrainingDeltaFormatter.formatDurationDelta(-30, -25f))
    }

    @Test
    fun `count delta shows sign`() {
        assertEquals("+1", WeeklyTrainingDeltaFormatter.formatCountDelta(1))
        assertEquals("-2", WeeklyTrainingDeltaFormatter.formatCountDelta(-2))
        assertEquals("0", WeeklyTrainingDeltaFormatter.formatCountDelta(0))
    }
}
