package app.readylytics.health.core.model.domain.util

import kotlin.math.abs
import kotlin.math.hypot

object RouteSimplifier {

    fun simplify(
        points: List<ProjectedPoint>,
        maxPoints: Int = 200,
    ): List<ProjectedPoint> {
        require(maxPoints >= 2) { "maxPoints must be at least 2" }
        if (points.size <= 2) return points

        val collinear = douglasPeucker(points, tolerance = 0.0)
        if (collinear.size <= maxPoints) return collinear

        var low = 0.0
        var high = 2.0
        repeat(60) {
            val mid = (low + high) / 2.0
            if (douglasPeucker(points, mid).size <= maxPoints) {
                high = mid
            } else {
                low = mid
            }
        }
        return douglasPeucker(points, high)
    }

    private fun douglasPeucker(
        points: List<ProjectedPoint>,
        tolerance: Double,
    ): List<ProjectedPoint> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            var maxDistance = 0.0
            var index = -1
            val a = points[start]
            val b = points[end]
            for (i in start + 1 until end) {
                val distance = perpendicularDistance(points[i], a, b)
                if (distance > maxDistance) {
                    maxDistance = distance
                    index = i
                }
            }
            if (maxDistance > tolerance && index != -1) {
                keep[index] = true
                stack.addLast(intArrayOf(start, index))
                stack.addLast(intArrayOf(index, end))
            }
        }
        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun perpendicularDistance(
        p: ProjectedPoint,
        a: ProjectedPoint,
        b: ProjectedPoint,
    ): Double {
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble()
        val length = hypot(dx, dy)
        if (length == 0.0) {
            return hypot((p.x - a.x).toDouble(), (p.y - a.y).toDouble())
        }
        return abs(dy * (p.x - a.x) - dx * (p.y - a.y)) / length
    }
}
