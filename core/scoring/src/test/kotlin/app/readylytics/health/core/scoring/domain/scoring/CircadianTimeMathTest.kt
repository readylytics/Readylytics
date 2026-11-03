package app.readylytics.health.core.scoring.domain.scoring

import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals

class CircadianTimeMathTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun `minuteOfDay converts epoch timestamp correctly`() {
        val epoch = Instant.parse("2026-06-01T07:35:00Z").toEpochMilli()
        assertEquals(7 * 60 + 35, CircadianTimeMath.minuteOfDay(epoch, zone))
    }

    @Test
    fun `circularDeviationMinutes computes shortest arc across noon and midnight`() {
        // 11:55 (715) vs 12:05 (725) -> 10 minutes apart across noon
        assertEquals(10, CircadianTimeMath.circularDeviationMinutes(715, 725))
        assertEquals(10, CircadianTimeMath.circularDeviationMinutes(725, 715))

        // 23:55 (1435) vs 00:05 (5) -> 10 minutes apart across midnight
        assertEquals(10, CircadianTimeMath.circularDeviationMinutes(1435, 5))
        assertEquals(10, CircadianTimeMath.circularDeviationMinutes(5, 1435))

        // Same time -> 0
        assertEquals(0, CircadianTimeMath.circularDeviationMinutes(720, 720))

        // Opposite times -> 720
        assertEquals(720, CircadianTimeMath.circularDeviationMinutes(0, 720))
    }

    @Test
    fun `circularSignedOffsetMinutes wraps into minus 720 to plus 720`() {
        // 12:05 (725) minus 11:55 (715) = +10
        assertEquals(10, CircadianTimeMath.circularSignedOffsetMinutes(725, 715))
        // 11:55 (715) minus 12:05 (725) = -10
        assertEquals(-10, CircadianTimeMath.circularSignedOffsetMinutes(715, 725))

        // 00:05 (5) minus 23:55 (1435) = +10
        assertEquals(10, CircadianTimeMath.circularSignedOffsetMinutes(5, 1435))
        // 23:55 (1435) minus 00:05 (5) = -10
        assertEquals(-10, CircadianTimeMath.circularSignedOffsetMinutes(1435, 5))
    }

    @Test
    fun `circularMedianMinutes correctly computes median straddling noon`() {
        // Wake times: 11:50 (710), 11:55 (715), 12:05 (725), 12:10 (730)
        // Linear median with noon-pivot broke and produced midnight (1440/0).
        // Circular median must produce 12:00 (720).
        val times = listOf(710, 715, 725, 730)
        assertEquals(720, CircadianTimeMath.circularMedianMinutes(times))
    }

    @Test
    fun `circularMedianMinutes correctly computes median straddling midnight`() {
        // Bed times: 23:50 (1430), 23:55 (1435), 00:05 (5), 00:10 (10)
        val times = listOf(1430, 1435, 5, 10)
        assertEquals(0, CircadianTimeMath.circularMedianMinutes(times))
    }

    @Test
    fun `circularMedianMinutes handles regular morning wake times`() {
        // 07:00 (420), 07:15 (435), 07:30 (450) -> median is 07:15 (435)
        assertEquals(435, CircadianTimeMath.circularMedianMinutes(listOf(420, 435, 450)))
    }

    @Test
    fun `circularMedianMinutes handles single and empty lists gracefully`() {
        assertEquals(0, CircadianTimeMath.circularMedianMinutes(emptyList()))
        assertEquals(500, CircadianTimeMath.circularMedianMinutes(listOf(500)))
    }
}
