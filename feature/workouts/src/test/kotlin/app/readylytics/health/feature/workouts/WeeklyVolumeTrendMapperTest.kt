package app.readylytics.health.feature.workouts

import app.readylytics.health.core.scoring.domain.workouts.weekly.DailyTrainingVolume
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeeklyVolumeTrendMapperTest {
    private val weekStart: LocalDate = LocalDate.of(2026, 8, 17) // a Monday

    /** A week where today is Thursday (offset 3): current-week data exists for offsets 0..3,
     *  offsets 4..6 (Fri..Sun) are strictly in the future and carry null current-week values. */
    private fun partialWeek(): List<DailyTrainingVolume> =
        (0 until 7).map { offset ->
            DailyTrainingVolume(
                dayOffset = offset,
                date = weekStart.plusDays(offset.toLong()),
                currentWeekDurationMinutes = if (offset <= 3) offset * TEN else null,
                previousWeekDurationMinutes = offset * FIVE,
                currentWeekCumulativeMinutes = if (offset <= 3) (0..offset).sumOf { it * TEN } else null,
                previousWeekCumulativeMinutes = (0..offset).sumOf { it * FIVE },
            )
        }

    @Test
    fun `toSeries omits current-week points strictly after today`() {
        val (current, previous) = WeeklyVolumeTrendMapper.toSeries(partialWeek())

        assertEquals(listOf(0, 1, 2, 3), current.map { it.dayOffset })
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), previous.map { it.dayOffset })
    }

    @Test
    fun `toSeries carries the cumulative minutes as the point value`() {
        val (current, previous) = WeeklyVolumeTrendMapper.toSeries(partialWeek())

        assertEquals(0f + TEN + 2 * TEN + 3 * TEN, current.last().value)
        assertEquals((0..6).sumOf { it * FIVE }.toFloat(), previous.last().value)
    }

    @Test
    fun `todayOffset is the last day with non-null current-week data`() {
        assertEquals(3, WeeklyVolumeTrendMapper.todayOffset(partialWeek()))
    }

    @Test
    fun `todayOffset is day zero when only the first day has data`() {
        val daily =
            partialWeek().map { it.copy(currentWeekDurationMinutes = null, currentWeekCumulativeMinutes = null) }
        val withFirstDay =
            daily.mapIndexed { index, day ->
                if (index == 0) day.copy(currentWeekDurationMinutes = TEN, currentWeekCumulativeMinutes = TEN) else day
            }

        assertEquals(0, WeeklyVolumeTrendMapper.todayOffset(withFirstDay))
    }

    @Test
    fun `todayOffset is day six when the full week has data`() {
        val fullWeek =
            partialWeek().map {
                it.copy(
                    currentWeekDurationMinutes = it.previousWeekDurationMinutes,
                    currentWeekCumulativeMinutes = it.previousWeekCumulativeMinutes,
                )
            }

        assertEquals(6, WeeklyVolumeTrendMapper.todayOffset(fullWeek))
    }

    @Test
    fun `todayOffset is null when the current week has no data at all`() {
        val noCurrentData =
            partialWeek().map { it.copy(currentWeekDurationMinutes = null, currentWeekCumulativeMinutes = null) }

        assertNull(WeeklyVolumeTrendMapper.todayOffset(noCurrentData))
    }

    @Test
    fun `dailyDelta is null for a future day with no current-week value`() {
        assertNull(WeeklyVolumeTrendMapper.dailyDelta(currentMinutes = null, previousMinutes = 20))
    }

    @Test
    fun `dailyDelta computes signed minutes and percent`() {
        // 40 -> 60 is a +20 minute, +50% change; both are exact in binary floating point,
        // avoiding rounding ambiguity in the assertion below.
        val result = WeeklyVolumeTrendMapper.dailyDelta(currentMinutes = 60, previousMinutes = 40)

        assertEquals(20, result?.deltaMinutes)
        assertEquals(50f, result?.percentChange)
    }

    @Test
    fun `dailyDelta percent change is null when the previous week had zero minutes`() {
        val result = WeeklyVolumeTrendMapper.dailyDelta(currentMinutes = 15, previousMinutes = 0)

        assertEquals(15, result?.deltaMinutes)
        assertNull(result?.percentChange)
    }

    private companion object {
        const val TEN = 10
        const val FIVE = 5
    }
}
