package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.model.RouteState
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.WorkoutDisplayMetrics
import io.mockk.coEvery
import java.time.LocalDate
import java.time.ZoneId

object WorkoutTestHelpers {
    fun createTestWorkoutData(
        id: String,
        startMs: Long,
        exerciseType: String = "running",
        durationMinutes: Int = 30,
        routeState: RouteState = RouteState.IMPORTED,
        elevationGainMeters: Float = 100f,
        trimp: Float = 60f,
        avgHr: Float = 150f,
    ): WorkoutData =
        WorkoutData(
            id = id,
            startTime = startMs,
            endTime = startMs + durationMinutes * 60 * 1000L,
            exerciseType = exerciseType,
            durationMinutes = durationMinutes,
            zone1Minutes = 5f,
            zone2Minutes = 10f,
            zone3Minutes = 10f,
            zone4Minutes = 5f,
            zone5Minutes = 0f,
            trimp = trimp,
            avgHr = avgHr,
            elevationGainMeters = elevationGainMeters,
            routeState = routeState,
        )

    fun createTestDate(
        year: Int = 2026,
        month: Int = 6,
        day: Int = 9,
    ): LocalDate = LocalDate.of(year, month, day)

    fun createMidnightMs(
        date: LocalDate,
        hoursOffset: Long = 0,
        minutesOffset: Long = 0,
    ): Long =
        date
            .atStartOfDay(ZoneId.systemDefault())
            .plusHours(hoursOffset)
            .plusMinutes(minutesOffset)
            .toInstant()
            .toEpochMilli()

    fun createTestRoutePoints(
        workoutId: String,
        startMs: Long,
        count: Int = 2,
        baseAltitude: Double = 45.0,
    ): List<WorkoutRoutePoint> {
        val points = mutableListOf<WorkoutRoutePoint>()
        repeat(count) { i ->
            points.add(
                WorkoutRoutePoint(
                    workoutId = workoutId,
                    latitude = 52.5200 + i * 0.001,
                    longitude = 13.4050 + i * 0.001,
                    altitude = baseAltitude + i * 5,
                    timestampMs = startMs + i * 10_000L,
                ),
            )
        }
        return points
    }

    fun setupDisplayMetricsMock(
        useCase: GetWorkoutDisplayMetricsUseCase,
        preciseTrimp: Float = 60f,
        computedTrimp: Int = 60,
    ) {
        coEvery {
            useCase.execute(
                workout = any(),
                samples = any(),
            )
        } returns
            WorkoutDisplayMetrics(
                preciseTrimp = preciseTrimp,
                computedTrimp = computedTrimp,
                trimpDisplay = "$computedTrimp",
                gainedStrain = 0.2f,
                gainedStrainDisplay = "0.2",
                classification = null,
            )
    }
}
