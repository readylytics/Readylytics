package app.readylytics.health.core.scoring.domain.airecommendation

import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ComputeWorkoutPatternSummaryUseCaseTest {
    private val useCase = ComputeWorkoutPatternSummaryUseCase()
    private val today = LocalDate.of(2026, 8, 9)

    @Test
    fun `default lookback is three months and reported back`() {
        assertEquals(3, ScoringConstants.AiRecommendation.LOOKBACK_MONTHS)
        assertEquals(3, useCase.execute(emptyList(), today, ZoneOffset.UTC).lookbackMonths)
    }

    @Test
    fun `empty history produces zero counts and zero streak`() {
        val result = useCase.execute(emptyList(), today, ZoneOffset.UTC)

        assertEquals(0, result.totalWorkoutsInWindow)
        assertEquals(0, result.currentConsecutiveTrainingDayStreak)
        assertEquals(0, result.mostRecentRestDayGapDays)
        assertTrue(result.exerciseTypeBreakdown.isEmpty())
    }

    @Test
    fun `workouts outside lookback window are excluded`() {
        val beforeWindow = workoutOn(LocalDate.of(2026, 5, 8), trimp = 100f)

        val result = useCase.execute(listOf(beforeWindow), today, ZoneOffset.UTC)

        assertEquals(0, result.totalWorkoutsInWindow)
    }

    @Test
    fun `workout exactly on window start is included`() {
        val onStart = workoutOn(LocalDate.of(2026, 5, 9), trimp = 50f)

        val result = useCase.execute(listOf(onStart), today, ZoneOffset.UTC)

        assertEquals(1, result.totalWorkoutsInWindow)
    }

    @Test
    fun `workout on today is included`() {
        val result = useCase.execute(listOf(workoutOn(today, trimp = 50f)), today, ZoneOffset.UTC)

        assertEquals(1, result.totalWorkoutsInWindow)
    }

    @Test
    fun `multiple exercise types have independent averages and preferred weekdays`() {
        val runMonday = workoutOn(LocalDate.of(2026, 8, 3), exerciseType = "Run", trimp = 100f, duration = 30)
        val runWednesday = workoutOn(LocalDate.of(2026, 8, 5), exerciseType = "Run", trimp = 200f, duration = 50)
        val cycleTuesday = workoutOn(LocalDate.of(2026, 8, 4), exerciseType = "Cycle", trimp = 150f, duration = 60)

        val result = useCase.execute(listOf(runMonday, runWednesday, cycleTuesday), today, ZoneOffset.UTC)

        assertEquals(3, result.totalWorkoutsInWindow)
        assertEquals(listOf("Run", "Cycle"), result.exerciseTypeBreakdown.map { it.exerciseType })

        val run = result.exerciseTypeBreakdown.first()
        assertEquals(150f, run.averageTrimp!!, 0.001f)
        assertEquals(40f, run.averageDurationMinutes!!, 0.001f)
        assertEquals(listOf("Monday", "Wednesday"), run.preferredDaysOfWeek)

        val cycle = result.exerciseTypeBreakdown.last()
        assertEquals(150f, cycle.averageTrimp!!, 0.001f)
        assertEquals(listOf("Tuesday"), cycle.preferredDaysOfWeek)
    }

    @Test
    fun `preferred weekdays order by descending count then weekday order`() {
        val monday1 = workoutOn(LocalDate.of(2026, 7, 6), exerciseType = "Run", trimp = 100f)
        val monday2 = workoutOn(LocalDate.of(2026, 7, 13), exerciseType = "Run", trimp = 100f)
        val wednesday = workoutOn(LocalDate.of(2026, 7, 8), exerciseType = "Run", trimp = 100f)

        val result = useCase.execute(listOf(monday1, monday2, wednesday), today, ZoneOffset.UTC)

        assertEquals(listOf("Monday", "Wednesday"), result.exerciseTypeBreakdown.single().preferredDaysOfWeek)
    }

    @Test
    fun `rest gap and current training streak walk calendar days from today`() {
        val result =
            useCase.execute(
                listOf(
                    workoutOn(LocalDate.of(2026, 8, 7), trimp = 100f),
                    workoutOn(LocalDate.of(2026, 8, 8), trimp = 100f),
                ),
                today,
                ZoneOffset.UTC,
            )

        assertEquals(0, result.mostRecentRestDayGapDays)
        assertEquals(0, result.currentConsecutiveTrainingDayStreak)
    }

    @Test
    fun `gap and streak count backwards from a training day`() {
        val monday = LocalDate.of(2026, 8, 10)
        val result =
            useCase.execute(
                listOf(
                    workoutOn(LocalDate.of(2026, 8, 8), trimp = 100f),
                    workoutOn(LocalDate.of(2026, 8, 9), trimp = 100f),
                    workoutOn(monday, trimp = 100f),
                ),
                monday,
                ZoneOffset.UTC,
            )

        assertEquals(3, result.mostRecentRestDayGapDays)
        assertEquals(3, result.currentConsecutiveTrainingDayStreak)
    }

    @Test
    fun `all days without workouts produce full rest-day average`() {
        val result = useCase.execute(emptyList(), today, ZoneOffset.UTC)

        assertEquals(7f, result.restDaysPerWeekAverage, 0.001f)
    }

    @Test
    fun `rest-day average scales with a single training day`() {
        val result = useCase.execute(listOf(workoutOn(today, trimp = 50f)), today, ZoneOffset.UTC)

        val windowDays = 93
        val expected = (windowDays - 1).toFloat() / windowDays * 7f
        assertEquals(expected, result.restDaysPerWeekAverage, 0.001f)
    }

    @Test
    fun `breakdown ordering is descending count then case-insensitive name`() {
        val result =
            useCase.execute(
                listOf(
                    workoutOn(LocalDate.of(2026, 8, 3), exerciseType = "cycle", trimp = 50f),
                    workoutOn(LocalDate.of(2026, 8, 4), exerciseType = "cycle", trimp = 50f),
                    workoutOn(LocalDate.of(2026, 8, 5), exerciseType = "Run", trimp = 50f),
                ),
                today,
                ZoneOffset.UTC,
            )

        assertEquals(listOf("cycle", "Run"), result.exerciseTypeBreakdown.map { it.exerciseType })
    }

    @Test
    fun `day bucketing follows the caller's zone, not UTC`() {
        val la = ZoneId.of("America/Los_Angeles")

        val lateEveningLocal = LocalDate.of(2026, 8, 9).atTime(20, 0)

        val crossesUtc = lateEveningLocal.atZone(la).toInstant().toEpochMilli()

        val result =
            useCase.execute(
                listOf(workoutAt(crossesUtc, trimp = 100f)),
                today,
                la,
            )

        assertEquals(1, result.totalWorkoutsInWindow)
        assertEquals(1, result.currentConsecutiveTrainingDayStreak)
        assertEquals(1, result.mostRecentRestDayGapDays)
    }

    @Test
    fun `UTC bucketing would misclassify a late evening workout in a west-of-UTC zone`() {
        val la = ZoneId.of("America/Los_Angeles")

        val lateEveningLocal = LocalDate.of(2026, 8, 9).atTime(20, 0)

        val crossesUtc = lateEveningLocal.atZone(la).toInstant().toEpochMilli()

        val resultUtc = useCase.execute(listOf(workoutAt(crossesUtc, trimp = 100f)), today, ZoneOffset.UTC)
        val resultLa = useCase.execute(listOf(workoutAt(crossesUtc, trimp = 100f)), today, la)

        assertEquals(0, resultUtc.currentConsecutiveTrainingDayStreak)
        assertEquals(1, resultLa.currentConsecutiveTrainingDayStreak)
    }

    private fun workoutAt(
        epochMillis: Long,
        trimp: Float = 100f,
        exerciseType: String = "Run",
        duration: Int = 45,
    ): WorkoutData =
        WorkoutData(
            id = "$exerciseType-$epochMillis-$trimp-$duration",
            startTime = epochMillis,
            endTime = epochMillis + 60_000L,
            exerciseType = exerciseType,
            durationMinutes = duration,
            zone1Minutes = 0f,
            zone2Minutes = 0f,
            zone3Minutes = 0f,
            zone4Minutes = 0f,
            zone5Minutes = 0f,
            trimp = trimp,
            avgHr = 140f,
        )

    private fun workoutOn(
        date: LocalDate,
        exerciseType: String = "Run",
        trimp: Float = 100f,
        duration: Int = 45,
    ): WorkoutData =
        WorkoutData(
            id = "$exerciseType-$date-$trimp-$duration",
            startTime = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            endTime = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 60_000L,
            exerciseType = exerciseType,
            durationMinutes = duration,
            zone1Minutes = 0f,
            zone2Minutes = 0f,
            zone3Minutes = 0f,
            zone4Minutes = 0f,
            zone5Minutes = 0f,
            trimp = trimp,
            avgHr = 140f,
        )
}
