package app.readylytics.health.feature.workouts

import app.readylytics.health.domain.workouts.detail.WorkoutDetailItemId
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
}
