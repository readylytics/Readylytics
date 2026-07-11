package app.readylytics.health.domain.util

import kotlin.math.abs
import kotlin.math.sqrt

object RouteSimplifier {
    fun simplify(points: List<ProjectedPoint>, maxPoints: Int, tolerance: Double = 0.00005): List<ProjectedPoint> {
        if (points.size <= maxPoints) return points
        val keep = BooleanArray(points.size) { false }
        keep[0] = true
        keep[points.lastIndex] = true
        
        simplifyStep(points, 0, points.lastIndex, tolerance, keep)
        
        val kept = points.filterIndexed { idx, _ -> keep[idx] }
        if (kept.size > maxPoints) {
            // Hard downsampling fallback
            val step = kept.size.toDouble() / maxPoints
            return List(maxPoints) { i -> kept[(i * step).toInt().coerceIn(kept.indices)] }
        }
        return kept
    }

    private fun simplifyStep(points: List<ProjectedPoint>, start: Int, end: Int, tolerance: Double, keep: BooleanArray) {
        if (end <= start + 1) return
        var maxDist = 0.0
        var maxIdx = start

        val p1 = points[start]
        val p2 = points[end]
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        val length = sqrt(dx * dx + dy * dy)

        for (i in (start + 1) until end) {
            val p = points[i]
            val dist = if (length == 0.0) {
                sqrt((p.x - p1.x) * (p.x - p1.x) + (p.y - p1.y) * (p.y - p1.y))
            } else {
                abs(dy * p.x - dx * p.y + p2.x * p1.y - p2.y * p1.x) / length
            }
            if (dist > maxDist) {
                maxDist = dist
                maxIdx = i
            }
        }

        if (maxDist > tolerance) {
            keep[maxIdx] = true
            simplifyStep(points, start, maxIdx, tolerance, keep)
            simplifyStep(points, maxIdx, end, tolerance, keep)
        }
    }
}
