package app.readylytics.health.core.model.domain.util

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteSimplifierTest {

    private fun point(x: Float, y: Float) = ProjectedPoint(x, y, 0.0, 0.0, null, 0L)

    @Test
    fun `removes collinear intermediate points`() {
        val points = (0..100).map { point(it / 100f, it / 100f) }
        val simplified = RouteSimplifier.simplify(points)
        assertEquals(2, simplified.size)
        assertEquals(points.first(), simplified.first())
        assertEquals(points.last(), simplified.last())
    }

    @Test
    fun `preserves start and end points`() {
        val points = listOf(
            point(0f, 0f),
            point(0.1f, 0.5f),
            point(0.2f, 0.2f),
            point(0.3f, 0.9f),
            point(0.5f, 0.4f),
            point(0.8f, 0.7f),
            point(1f, 1f),
        )
        val simplified = RouteSimplifier.simplify(points)
        assertEquals(points.first(), simplified.first())
        assertEquals(points.last(), simplified.last())
        assertTrue(simplified.size in 2..points.size)
    }

    @Test
    fun `bounds output size to maxPoints`() {
        val points = buildList {
            repeat(20) { row ->
                repeat(25) { col ->
                    val x = col / 25f
                    val y = row / 20f + if ((col + row) % 2 == 0) 0.02f else 0f
                    add(point(x, y))
                }
            }
        }
        assertTrue(points.size > 100, "fixture must exceed maxPoints")
        val simplified = RouteSimplifier.simplify(points, maxPoints = 100)
        assertTrue(simplified.size <= 100, "output size ${simplified.size} exceeds maxPoints")
        assertEquals(points.first(), simplified.first())
        assertEquals(points.last(), simplified.last())
    }
}
