package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutDetailItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutDetailItemAvailabilityTest {
    private fun input(
        hasDistance: Boolean = false,
        hasSpeed: Boolean = false,
        hasElevationGain: Boolean = false,
        routeState: RouteDataState = RouteDataState.NotAvailable,
        hasPaceSpeedChartData: Boolean = false,
        hasElevationChartData: Boolean = false,
        hasHrChartData: Boolean = false,
        hasRecoveryData: Boolean = false,
    ) = WorkoutDetailAvailabilityInput(
        hasDistance = hasDistance,
        hasSpeed = hasSpeed,
        hasElevationGain = hasElevationGain,
        routeState = routeState,
        hasPaceSpeedChartData = hasPaceSpeedChartData,
        hasElevationChartData = hasElevationChartData,
        hasHrChartData = hasHrChartData,
        hasRecoveryData = hasRecoveryData,
    )

    @Test
    fun `score tiles are always available`() {
        val available = WorkoutDetailItemAvailability.available(input())
        assertTrue(WorkoutDetailItemId.TRAINING_LOAD in available)
        assertTrue(WorkoutDetailItemId.AVG_PULSE in available)
        assertTrue(WorkoutDetailItemId.GAINED_STRAIN in available)
        assertTrue(WorkoutDetailItemId.RAS in available)
        assertTrue(WorkoutDetailItemId.OVERALL_LOAD in available)
        assertTrue(WorkoutDetailItemId.INTENSITY in available)
        assertTrue(WorkoutDetailItemId.ZONE_BREAKDOWN in available)
    }

    @Test
    fun `gps tiles drop out for a workout without gps`() {
        val available = WorkoutDetailItemAvailability.available(input())
        assertFalse(WorkoutDetailItemId.DISTANCE in available)
        assertFalse(WorkoutDetailItemId.AVG_PACE_SPEED in available)
        assertFalse(WorkoutDetailItemId.ELEVATION_GAIN in available)
    }

    @Test
    fun `gps tiles appear independently of one another`() {
        val available = WorkoutDetailItemAvailability.available(input(hasDistance = true))
        assertTrue(WorkoutDetailItemId.DISTANCE in available)
        assertFalse(WorkoutDetailItemId.AVG_PACE_SPEED in available)
    }

    @Test
    fun `route stays available when permission is required so the CTA is reachable`() {
        val available = WorkoutDetailItemAvailability.available(input(routeState = RouteDataState.PermissionRequired))
        assertTrue(WorkoutDetailItemId.ROUTE_CONTOUR in available)
    }

    @Test
    fun `route drops out only when route data is not available`() {
        assertFalse(
            WorkoutDetailItemId.ROUTE_CONTOUR in
                WorkoutDetailItemAvailability.available(input(routeState = RouteDataState.NotAvailable)),
        )
        assertTrue(
            WorkoutDetailItemId.ROUTE_CONTOUR in
                WorkoutDetailItemAvailability.available(input(routeState = RouteDataState.Available)),
        )
    }

    @Test
    fun `charts and recovery follow their data`() {
        val empty = WorkoutDetailItemAvailability.available(input())
        assertFalse(WorkoutDetailItemId.PACE_SPEED_CHART in empty)
        assertFalse(WorkoutDetailItemId.ELEVATION_CHART in empty)
        assertFalse(WorkoutDetailItemId.TRIMP_BREAKDOWN in empty)
        assertFalse(WorkoutDetailItemId.RECOVERY_HRR in empty)

        val full =
            WorkoutDetailItemAvailability.available(
                input(
                    hasPaceSpeedChartData = true,
                    hasElevationChartData = true,
                    hasHrChartData = true,
                    hasRecoveryData = true,
                ),
            )
        assertTrue(WorkoutDetailItemId.PACE_SPEED_CHART in full)
        assertTrue(WorkoutDetailItemId.ELEVATION_CHART in full)
        assertTrue(WorkoutDetailItemId.TRIMP_BREAKDOWN in full)
        assertTrue(WorkoutDetailItemId.RECOVERY_HRR in full)
    }

    private fun workout(
        totalDistanceMeters: Float? = null,
        avgSpeedKmh: Float? = null,
        elevationGainMeters: Float? = null,
    ) = WorkoutData(
        id = "run-1",
        startTime = 0L,
        endTime = 60_000L,
        exerciseType = "running",
        durationMinutes = 1,
        zone1Minutes = 0f,
        zone2Minutes = 0f,
        zone3Minutes = 0f,
        zone4Minutes = 0f,
        zone5Minutes = 0f,
        trimp = 0f,
        avgHr = 0f,
        totalDistanceMeters = totalDistanceMeters,
        avgSpeedKmh = avgSpeedKmh,
        elevationGainMeters = elevationGainMeters,
    )

    @Test
    fun `inputFrom maps a missing workout to false gps flags`() {
        val input = WorkoutDetailItemAvailability.inputFrom(WorkoutDetailUiState())
        assertFalse(input.hasDistance)
        assertFalse(input.hasSpeed)
        assertFalse(input.hasElevationGain)
        assertEquals(RouteDataState.NotAvailable, input.routeState)
        assertFalse(input.hasPaceSpeedChartData)
        assertFalse(input.hasElevationChartData)
        assertFalse(input.hasHrChartData)
        assertFalse(input.hasRecoveryData)
    }

    @Test
    fun `inputFrom treats a zero distance as present`() {
        val input =
            WorkoutDetailItemAvailability.inputFrom(
                WorkoutDetailUiState(workout = workout(totalDistanceMeters = 0f)),
            )
        assertTrue(input.hasDistance)
    }

    @Test
    fun `inputFrom requires strictly positive speed`() {
        val zero = WorkoutDetailItemAvailability.inputFrom(WorkoutDetailUiState(workout = workout(avgSpeedKmh = 0f)))
        assertFalse(zero.hasSpeed)
        val positive =
            WorkoutDetailItemAvailability.inputFrom(
                WorkoutDetailUiState(workout = workout(avgSpeedKmh = 5f)),
            )
        assertTrue(positive.hasSpeed)
    }

    @Test
    fun `inputFrom falls back to workout elevation gain when the display value is absent`() {
        val input =
            WorkoutDetailItemAvailability.inputFrom(
                WorkoutDetailUiState(
                    workout = workout(elevationGainMeters = 10f),
                    displayElevationGainMeters = null,
                ),
            )
        assertTrue(input.hasElevationGain)
    }

    @Test
    fun `inputFrom maps route chart and recovery flags through`() {
        val input =
            WorkoutDetailItemAvailability.inputFrom(
                WorkoutDetailUiState(
                    routeUiState = RouteUiState(state = RouteDataState.Available),
                    paceSpeedChartData = listOf(1.0 to 2.0),
                    elevationChartData = listOf(1.0 to 2.0),
                    hrChartData = listOf(1.0 to 2.0),
                    hrr1Min = null,
                    hrr2Min = 5,
                    hrr3Min = null,
                ),
            )
        assertEquals(RouteDataState.Available, input.routeState)
        assertTrue(input.hasPaceSpeedChartData)
        assertTrue(input.hasElevationChartData)
        assertTrue(input.hasHrChartData)
        assertTrue(input.hasRecoveryData)
    }
}
