package app.readylytics.health.core.scoring.domain.workouts.weekly

import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Tests for Activity Volume and Training Mix aggregation — the [WeeklyActivityBreakdown]-driven
 * portions of [ComputeWeeklyTrainingStatsUseCase]. Extracted from the main use-case test to keep
 * both files under the 400-line target.
 */
class WeeklyActivityBreakdownTest {
    private val useCase = ComputeWeeklyTrainingStatsUseCase()

    // Thursday, June 4 2026 — Monday-start current week = Jun 1..4, Sunday-start = May 31..Jun 4.
    private val today = LocalDate.of(2026, 6, 4)

    // region Activity volume

    @Test
    fun `distance-based activity sums distance across current and previous week`() {
        val workouts =
            listOf(
                workoutOn(LocalDate.of(2026, 6, 1), exerciseType = "running", distanceMeters = 1000f),
                workoutOn(LocalDate.of(2026, 6, 2), exerciseType = "running", distanceMeters = 1500f),
                workoutOn(LocalDate.of(2026, 5, 25), exerciseType = "running", distanceMeters = 2000f),
            )

        val volumes = useCase.execute(workouts, today, DayOfWeek.MONDAY, ZoneOffset.UTC).activityVolumes
        val running = volumes.single { it.activityType == WorkoutLayoutType.RUNNING }

        assertEquals(ActivityMetricType.DISTANCE, running.metricType)
        assertEquals(2500f, running.currentWeekValue, 0.001f)
        assertEquals(2000f, running.previousWeekValue, 0.001f)
        assertEquals(500f, running.absoluteChange, 0.001f)
        assertEquals(25f, running.percentChange!!, 0.001f)
    }

    @Test
    fun `duration-based activity sums duration, not distance`() {
        val workouts =
            listOf(
                workoutOn(LocalDate.of(2026, 6, 1), exerciseType = "strength", duration = 30),
                workoutOn(LocalDate.of(2026, 6, 2), exerciseType = "strength", duration = 45),
            )

        val volumes = useCase.execute(workouts, today, DayOfWeek.MONDAY, ZoneOffset.UTC).activityVolumes
        val strength = volumes.single { it.activityType == WorkoutLayoutType.STRENGTH }

        assertEquals(ActivityMetricType.DURATION, strength.metricType)
        assertEquals(75f, strength.currentWeekValue, 0.001f)
    }

    @Test
    fun `multiple health connect exercise type ids mapping to one category aggregate together`() {
        val workouts =
            listOf(
                workoutOn(LocalDate.of(2026, 6, 1), exerciseType = "73", distanceMeters = 500f), // pool swimming
                workoutOn(LocalDate.of(2026, 6, 2), exerciseType = "74", distanceMeters = 700f), // open water swimming
            )

        val volumes = useCase.execute(workouts, today, DayOfWeek.MONDAY, ZoneOffset.UTC).activityVolumes
        val swimming = volumes.filter { it.activityType == WorkoutLayoutType.SWIMMING }

        assertEquals(1, swimming.size)
        assertEquals(1200f, swimming.single().currentWeekValue, 0.001f)
    }

    @Test
    fun `missing distance on a distance-based activity contributes zero without failing`() {
        val workouts = listOf(workoutOn(LocalDate.of(2026, 6, 1), exerciseType = "running", distanceMeters = null))

        val volumes = useCase.execute(workouts, today, DayOfWeek.MONDAY, ZoneOffset.UTC).activityVolumes

        assertEquals(0f, volumes.single { it.activityType == WorkoutLayoutType.RUNNING }.currentWeekValue, 0.001f)
    }

    @Test
    fun `zero duration on a duration-based activity still appears with a zero value`() {
        val workouts = listOf(workoutOn(LocalDate.of(2026, 6, 1), exerciseType = "strength", duration = 0))

        val volumes = useCase.execute(workouts, today, DayOfWeek.MONDAY, ZoneOffset.UTC).activityVolumes

        assertEquals(0f, volumes.single { it.activityType == WorkoutLayoutType.STRENGTH }.currentWeekValue, 0.001f)
    }

    // endregion

    // region Training mix

    @Test
    fun `single activity type yields one hundred percent`() {
        val workouts = listOf(workoutOn(LocalDate.of(2026, 6, 1), exerciseType = "running", duration = 60))

        val mix = useCase.execute(workouts, today, DayOfWeek.MONDAY, ZoneOffset.UTC).trainingMix

        assertEquals(1, mix.size)
        assertEquals(100f, mix.single().percentage, 0.001f)
    }

    @Test
    fun `multiple activity types split percentages proportionally and sum to one hundred`() {
        val workouts =
            listOf(
                workoutOn(LocalDate.of(2026, 6, 1), exerciseType = "running", duration = 30),
                workoutOn(LocalDate.of(2026, 6, 2), exerciseType = "strength", duration = 90),
            )

        val mix = useCase.execute(workouts, today, DayOfWeek.MONDAY, ZoneOffset.UTC).trainingMix

        assertEquals(25f, mix.single { it.activityType == WorkoutLayoutType.RUNNING }.percentage, 0.001f)
        assertEquals(75f, mix.single { it.activityType == WorkoutLayoutType.STRENGTH }.percentage, 0.001f)
        assertEquals(100f, mix.sumOf { it.percentage.toDouble() }.toFloat(), 0.001f)
    }

    @Test
    fun `empty week yields an empty training mix`() {
        val mix = useCase.execute(emptyList(), today, DayOfWeek.MONDAY, ZoneOffset.UTC).trainingMix

        assertTrue(mix.isEmpty())
    }

    // endregion

    private fun workoutOn(
        date: LocalDate,
        exerciseType: String = "running",
        duration: Int = 30,
        distanceMeters: Float? = null,
    ): WorkoutData {
        val epochMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return WorkoutData(
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
}
