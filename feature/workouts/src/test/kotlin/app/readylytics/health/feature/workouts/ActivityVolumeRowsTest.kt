package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType
import app.readylytics.health.core.scoring.domain.workouts.weekly.ActivityMetricType
import app.readylytics.health.core.scoring.domain.workouts.weekly.ComputeWeeklyTrainingStatsUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset

/** Row selection for the Activity volume section: current-week types ranked by duration share,
 *  joined with their like-for-like volume comparison. */
class ActivityVolumeRowsTest {
    private val useCase = ComputeWeeklyTrainingStatsUseCase()

    // Thursday, June 4 2026 — Monday-start current week = Jun 1..4.
    private val today = LocalDate.of(2026, 6, 4)

    @Test
    fun `rows ranked by this week's training duration descending`() {
        val stats =
            stats(
                workoutOn(LocalDate.of(2026, 6, 2), exerciseType = "running", duration = 60, distanceMeters = 10_000f),
                workoutOn(LocalDate.of(2026, 6, 1), exerciseType = "strength", duration = 30),
            )

        val rows = buildActivityVolumeRows(stats)

        assertEquals(
            listOf(WorkoutLayoutType.RUNNING, WorkoutLayoutType.STRENGTH),
            rows.map { it.activityType },
        )
    }

    @Test
    fun `distance type and duration type both present with their own metrics`() {
        val stats =
            stats(
                workoutOn(LocalDate.of(2026, 6, 2), exerciseType = "running", duration = 60, distanceMeters = 10_000f),
                workoutOn(LocalDate.of(2026, 6, 1), exerciseType = "strength", duration = 30),
            )

        val rows = buildActivityVolumeRows(stats)

        val running = rows.single { it.activityType == WorkoutLayoutType.RUNNING }
        val strength = rows.single { it.activityType == WorkoutLayoutType.STRENGTH }
        assertEquals(ActivityMetricType.DISTANCE, running.metricType)
        assertEquals(ActivityMetricType.DURATION, strength.metricType)
    }

    @Test
    fun `previous-week-only activity type is not shown`() {
        val stats = stats(workoutOn(LocalDate.of(2026, 5, 26), exerciseType = "rowing", duration = 45))

        assertTrue(buildActivityVolumeRows(stats).isEmpty())
    }

    @Test
    fun `empty week yields no rows`() {
        assertTrue(buildActivityVolumeRows(stats()).isEmpty())
    }

    private fun stats(vararg workouts: WorkoutData) =
        useCase.execute(workouts.toList(), today, today, DayOfWeek.MONDAY, ZoneOffset.UTC)

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
