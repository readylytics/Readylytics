package app.readylytics.health.core.scoring.domain.workouts.weekly

import app.readylytics.health.core.model.domain.repository.WorkoutData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ComputeWeeklyTrainingStatsUseCaseTest {
    private val useCase = ComputeWeeklyTrainingStatsUseCase()

    // Thursday, June 4 2026 — Monday-start current week = Jun 1..4, Sunday-start = May 31..Jun 4.
    private val today = LocalDate.of(2026, 6, 4)

    // region Weekly totals / comparison

    @Test
    fun `identical weeks produce zero comparison deltas`() {
        val current =
            listOf(
                workoutOn(LocalDate.of(2026, 6, 1), duration = 30),
                workoutOn(LocalDate.of(2026, 6, 2), duration = 30),
                workoutOn(LocalDate.of(2026, 6, 3), duration = 30),
                workoutOn(LocalDate.of(2026, 6, 4), duration = 30),
            )
        val previous =
            listOf(
                workoutOn(LocalDate.of(2026, 5, 25), duration = 30),
                workoutOn(LocalDate.of(2026, 5, 26), duration = 30),
                workoutOn(LocalDate.of(2026, 5, 27), duration = 30),
                workoutOn(LocalDate.of(2026, 5, 28), duration = 30),
            )

        val result = useCase.execute(current + previous, today, today, DayOfWeek.MONDAY, ZoneOffset.UTC)

        assertEquals(0, result.comparison.durationDeltaMinutes)
        assertEquals(0f, result.comparison.durationPercentChange!!, 0.001f)
        assertEquals(0, result.comparison.workoutCountDelta)
        assertEquals(0, result.comparison.activeDaysDelta)
    }

    @Test
    fun `more training this week yields a positive delta and percent change`() {
        val workouts =
            listOf(
                workoutOn(LocalDate.of(2026, 6, 1), duration = 200),
                workoutOn(LocalDate.of(2026, 5, 25), duration = 100),
            )

        val result = useCase.execute(workouts, today, today, DayOfWeek.MONDAY, ZoneOffset.UTC)

        assertEquals(100, result.comparison.durationDeltaMinutes)
        assertEquals(100f, result.comparison.durationPercentChange!!, 0.001f)
    }

    @Test
    fun `less training this week yields a negative delta and percent change`() {
        val workouts =
            listOf(
                workoutOn(LocalDate.of(2026, 6, 1), duration = 50),
                workoutOn(LocalDate.of(2026, 5, 25), duration = 100),
            )

        val result = useCase.execute(workouts, today, today, DayOfWeek.MONDAY, ZoneOffset.UTC)

        assertEquals(-50, result.comparison.durationDeltaMinutes)
        assertEquals(-50f, result.comparison.durationPercentChange!!, 0.001f)
    }

    @Test
    fun `no previous-week workouts yields a null percent change`() {
        val workouts = listOf(workoutOn(LocalDate.of(2026, 6, 1), duration = 100))

        val result = useCase.execute(workouts, today, today, DayOfWeek.MONDAY, ZoneOffset.UTC)

        assertEquals(0, result.previousWeek.totalDurationMinutes)
        assertNull(result.comparison.durationPercentChange)
        assertEquals(100, result.comparison.durationDeltaMinutes)
        assertEquals(1, result.comparison.workoutCountDelta)
        assertEquals(1, result.comparison.activeDaysDelta)
    }

    @Test
    fun `no current-week workouts yields zero current totals and a negative comparison`() {
        val workouts =
            listOf(
                workoutOn(LocalDate.of(2026, 5, 25), duration = 60),
                workoutOn(LocalDate.of(2026, 5, 26), duration = 40),
            )

        val result = useCase.execute(workouts, today, today, DayOfWeek.MONDAY, ZoneOffset.UTC)

        assertEquals(0, result.currentWeek.totalDurationMinutes)
        assertEquals(0, result.currentWeek.workoutCount)
        assertEquals(0, result.currentWeek.activeDays)
        assertEquals(-100, result.comparison.durationDeltaMinutes)
        assertEquals(-100f, result.comparison.durationPercentChange!!, 0.001f)
        assertEquals(-2, result.comparison.workoutCountDelta)
    }

    @Test
    fun `previous-week totals use the like-for-like same-elapsed-days window`() {
        // Today is Thursday; the previous week's Friday workout falls outside the like-for-like
        // window (previous Mon..Thu) and must not inflate the comparison.
        val workouts =
            listOf(
                workoutOn(LocalDate.of(2026, 6, 1), duration = 30), // current Mon
                workoutOn(LocalDate.of(2026, 5, 25), duration = 30), // previous Mon
                workoutOn(LocalDate.of(2026, 5, 29), duration = 60), // previous Fri — outside window
            )

        val result = useCase.execute(workouts, today, today, DayOfWeek.MONDAY, ZoneOffset.UTC)

        assertEquals(30, result.previousWeek.totalDurationMinutes)
        assertEquals(1, result.previousWeek.workoutCount)
        assertEquals(0, result.comparison.durationDeltaMinutes)
    }

    @Test
    fun `anchor on the configured week start yields one-day comparison windows`() {
        val monday = LocalDate.of(2026, 6, 1)
        val workouts =
            listOf(
                workoutOn(LocalDate.of(2026, 6, 1), duration = 40), // current Monday
                workoutOn(LocalDate.of(2026, 5, 25), duration = 10), // previous Monday
                workoutOn(LocalDate.of(2026, 5, 26), duration = 90), // previous Tue — outside 1-day window
            )

        val result = useCase.execute(workouts, monday, monday, DayOfWeek.MONDAY, ZoneOffset.UTC)

        assertEquals(40, result.currentWeek.totalDurationMinutes)
        assertEquals(10, result.previousWeek.totalDurationMinutes)
        assertEquals(30, result.comparison.durationDeltaMinutes)
    }

    @Test
    fun `sunday start compares the same elapsed days of the previous sunday-start week`() {
        val workouts =
            listOf(
                workoutOn(LocalDate.of(2026, 5, 31), duration = 20), // current Sunday (week start)
                workoutOn(LocalDate.of(2026, 6, 1), duration = 30), // current Monday
                workoutOn(LocalDate.of(2026, 5, 24), duration = 15), // previous Sunday
                workoutOn(LocalDate.of(2026, 5, 29), duration = 99), // previous Fri — outside window
            )

        val result = useCase.execute(workouts, today, today, DayOfWeek.SUNDAY, ZoneOffset.UTC)

        assertEquals(50, result.currentWeek.totalDurationMinutes)
        assertEquals(15, result.previousWeek.totalDurationMinutes)
        assertEquals(35, result.comparison.durationDeltaMinutes)
    }

    @Test
    fun `partial current week excludes workouts dated after today`() {
        val partialToday = LocalDate.of(2026, 6, 2) // Tuesday: second day of a Monday-start week.
        val workouts =
            listOf(
                workoutOn(LocalDate.of(2026, 6, 1), duration = 10),
                workoutOn(LocalDate.of(2026, 6, 2), duration = 20),
                workoutOn(LocalDate.of(2026, 6, 3), duration = 999), // Future relative to partialToday.
            )

        val result = useCase.execute(workouts, partialToday, partialToday, DayOfWeek.MONDAY, ZoneOffset.UTC)

        assertEquals(30, result.currentWeek.totalDurationMinutes)
        assertEquals(2, result.currentWeek.workoutCount)
    }

    @Test
    fun `different configured week starts change which workouts count toward the current week`() {
        val workout = listOf(workoutOn(LocalDate.of(2026, 5, 31), duration = 60)) // Sunday

        val mondayResult = useCase.execute(workout, today, today, DayOfWeek.MONDAY, ZoneOffset.UTC)
        val sundayResult = useCase.execute(workout, today, today, DayOfWeek.SUNDAY, ZoneOffset.UTC)

        assertEquals(0, mondayResult.currentWeek.totalDurationMinutes)
        assertEquals(60, sundayResult.currentWeek.totalDurationMinutes)
    }

    // endregion

    // region Daily cumulative training

    @Test
    fun `single workout produces the correct day entry and running cumulative total`() {
        val workouts = listOf(workoutOn(LocalDate.of(2026, 6, 2), duration = 45))

        val days = useCase.execute(workouts, today, today, DayOfWeek.MONDAY, ZoneOffset.UTC).cumulativeDailyTraining

        assertEquals(7, days.size)
        assertEquals(LocalDate.of(2026, 6, 1), days[0].date)
        assertEquals(45, days[1].currentWeekDurationMinutes)
        assertEquals(45, days[1].currentWeekCumulativeMinutes)
        assertEquals(45, days[3].currentWeekCumulativeMinutes) // Today (Thursday) carries the total forward.
        assertNull(days[4].currentWeekDurationMinutes) // Friday: strictly after today.
        assertNull(days[4].currentWeekCumulativeMinutes)
    }

    @Test
    fun `multiple workouts on one day sum into that day's duration`() {
        val workouts =
            listOf(
                workoutOn(LocalDate.of(2026, 6, 1), duration = 20),
                workoutOn(LocalDate.of(2026, 6, 1), duration = 25),
            )

        val days = useCase.execute(workouts, today, today, DayOfWeek.MONDAY, ZoneOffset.UTC).cumulativeDailyTraining

        assertEquals(45, days[0].currentWeekDurationMinutes)
    }

    @Test
    fun `rest days are represented as explicit zero, not omitted or null`() {
        val days = useCase.execute(emptyList(), today, today, DayOfWeek.MONDAY, ZoneOffset.UTC).cumulativeDailyTraining

        // Monday..Thursday (today) have elapsed; all are explicit zeros, not null.
        assertEquals(listOf(0, 0, 0, 0), days.take(4).map { it.currentWeekDurationMinutes })
        // Friday..Sunday haven't happened yet.
        assertEquals(listOf(null, null, null), days.takeLast(3).map { it.currentWeekDurationMinutes })
    }

    @Test
    fun `sunday start cumulative chart begins on the configured sunday`() {
        val workouts = listOf(workoutOn(LocalDate.of(2026, 5, 31), duration = 10))

        val days = useCase.execute(workouts, today, today, DayOfWeek.SUNDAY, ZoneOffset.UTC).cumulativeDailyTraining

        assertEquals(LocalDate.of(2026, 5, 31), days[0].date)
        assertEquals(10, days[0].currentWeekDurationMinutes)
        assertEquals(10, days[0].currentWeekCumulativeMinutes)
    }

    @Test
    fun `previous week values are always present even when both weeks are empty`() {
        val days = useCase.execute(emptyList(), today, today, DayOfWeek.MONDAY, ZoneOffset.UTC).cumulativeDailyTraining

        assertTrue(days.all { it.previousWeekDurationMinutes == 0 })
        assertTrue(days.all { it.previousWeekCumulativeMinutes == 0 })
    }

    @Test
    fun `zero-duration workout contributes zero volume but still counts toward totals`() {
        val workouts = listOf(workoutOn(LocalDate.of(2026, 6, 1), duration = 0))

        val result = useCase.execute(workouts, today, today, DayOfWeek.MONDAY, ZoneOffset.UTC)

        assertEquals(0, result.cumulativeDailyTraining[0].currentWeekDurationMinutes)
        assertEquals(1, result.currentWeek.workoutCount)
        assertEquals(1, result.currentWeek.activeDays)
    }

    // endregion

    // Activity volume and training mix tests live in WeeklyActivityBreakdownTest.kt.


    // region Cross-model consistency

    @Test
    fun `training mix, weekly totals, and the final cumulative day agree on the same total duration`() {
        val workouts =
            listOf(
                workoutOn(LocalDate.of(2026, 6, 1), exerciseType = "running", duration = 30, distanceMeters = 1000f),
                workoutOn(LocalDate.of(2026, 6, 2), exerciseType = "strength", duration = 45),
                workoutOn(LocalDate.of(2026, 6, 4), exerciseType = "running", duration = 20, distanceMeters = 500f),
            )

        val result = useCase.execute(workouts, today, today, DayOfWeek.MONDAY, ZoneOffset.UTC)

        val mixTotal = result.trainingMix.sumOf { it.durationMinutes }
        val lastPopulatedDay = result.cumulativeDailyTraining.last { it.currentWeekCumulativeMinutes != null }
        val finalCumulative = lastPopulatedDay.currentWeekCumulativeMinutes

        assertEquals(95, result.currentWeek.totalDurationMinutes)
        assertEquals(result.currentWeek.totalDurationMinutes, mixTotal)
        assertEquals(result.currentWeek.totalDurationMinutes, finalCumulative)
    }

    @Test
    fun `previous-week totals cover the like-for-like window while the chart covers the full week`() {
        val workouts =
            listOf(
                workoutOn(LocalDate.of(2026, 5, 25), duration = 30),
                workoutOn(LocalDate.of(2026, 5, 29), duration = 60), // Friday of the previous week.
                workoutOn(LocalDate.of(2026, 6, 1), duration = 45),
            )

        val result = useCase.execute(workouts, today, today, DayOfWeek.MONDAY, ZoneOffset.UTC)

        assertEquals(30, result.previousWeek.totalDurationMinutes)
        assertEquals(90, result.cumulativeDailyTraining.last().previousWeekCumulativeMinutes)
    }

    // endregion

    // region Timezone handling

    @Test
    fun `day bucketing for the cumulative chart follows the caller's zone, not UTC`() {
        val la = ZoneId.of("America/Los_Angeles")
        // 8pm LA on Jun 3 is already 3am UTC on Jun 4 (LA is UTC-7 under DST in June).
        val crossesUtc = LocalDate.of(2026, 6, 3).atTime(20, 0).atZone(la).toInstant().toEpochMilli()
        val workout = listOf(workoutAt(crossesUtc, duration = 45))

        val utcDays = useCase.execute(workout, today, today, DayOfWeek.MONDAY, ZoneOffset.UTC).cumulativeDailyTraining
        val laDays = useCase.execute(workout, today, today, DayOfWeek.MONDAY, la).cumulativeDailyTraining

        assertEquals(45, utcDays[3].currentWeekDurationMinutes) // Thursday Jun 4 in UTC.
        assertEquals(0, utcDays[2].currentWeekDurationMinutes)
        assertEquals(45, laDays[2].currentWeekDurationMinutes) // Wednesday Jun 3 in LA.
        assertEquals(0, laDays[3].currentWeekDurationMinutes)
    }

    // endregion

    private fun workoutOn(
        date: LocalDate,
        exerciseType: String = "running",
        duration: Int = 30,
        distanceMeters: Float? = null,
    ): WorkoutData {
        val epochMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return workoutAt(epochMillis, exerciseType, duration, distanceMeters)
    }

    private fun workoutAt(
        epochMillis: Long,
        exerciseType: String = "running",
        duration: Int = 30,
        distanceMeters: Float? = null,
    ): WorkoutData =
        WorkoutData(
            id = "$exerciseType-$epochMillis-$duration-$distanceMeters",
            startTime = epochMillis,
            endTime = epochMillis + duration * 60_000L,
            exerciseType = exerciseType,
            durationMinutes = duration,
            zone1Minutes = 0f,
            zone2Minutes = 0f,
            zone3Minutes = 0f,
            zone4Minutes = 0f,
            zone5Minutes = 0f,
            trimp = 0f,
            avgHr = 0f,
            totalDistanceMeters = distanceMeters,
        )
}
