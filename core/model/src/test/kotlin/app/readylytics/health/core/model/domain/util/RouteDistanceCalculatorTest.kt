package app.readylytics.health.core.model.domain.util

import app.readylytics.health.domain.model.DomainRouteLocation
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteDistanceCalculatorTest {

    @Test
    fun `haversine computes approximate distance between two coordinates`() {
        val meters = RouteDistanceCalculator.haversineMeters(48.8566, 2.3522, 48.8566, 2.3622)
        assertTrue(meters in 700.0..800.0, "expected ~731m but was $meters")
    }

    @Test
    fun `path distance sums consecutive segment distances`() {
        val route =
            listOf(
                DomainRouteLocation(0.0, 0.0, null, Instant.EPOCH, null, null),
                DomainRouteLocation(0.001, 0.0, null, Instant.EPOCH.plusSeconds(1), null, null),
                DomainRouteLocation(0.002, 0.0, null, Instant.EPOCH.plusSeconds(2), null, null),
            )
        val total = RouteDistanceCalculator.pathDistanceMeters(route)
        assertEquals(222.4, total, 2.0)
    }

    @Test
    fun `path distance is zero for empty or single point routes`() {
        assertEquals(0.0, RouteDistanceCalculator.pathDistanceMeters(emptyList()), 0.001)
        assertEquals(
            0.0,
            RouteDistanceCalculator.pathDistanceMeters(
                listOf(DomainRouteLocation(1.0, 2.0, null, Instant.EPOCH, null, null)),
            ),
            0.001,
        )
    }
}
