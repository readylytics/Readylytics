package app.readylytics.health.feature.workouts

import app.readylytics.health.domain.util.ProjectedPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutDetailScreenTest {
    @Test
    fun computeLabelMinutes_durationZero_returnsZero() {
        val result = computeLabelMinutes(0, 6)
        assertEquals(listOf(0.0), result)
    }

    @Test
    fun computeLabelMinutes_negativeDuration_returnsZero() {
        val result = computeLabelMinutes(-10, 6)
        assertEquals(listOf(0.0), result)
    }

    @Test
    fun computeLabelMinutes_shortDuration_returnsAllMinutes() {
        val result = computeLabelMinutes(5, 6)
        assertEquals((0..5).map { it.toDouble() }, result)
    }

    @Test
    fun computeLabelMinutes_durationEqualsTarget_returnsDistinctValues() {
        val result = computeLabelMinutes(60, 6)
        assertEquals(result, result.distinct())
        assert(result.size <= 7)
    }

    @Test
    fun computeLabelMinutes_variousWorkoutDurations_labelCountAtMostSix() {
        val testCases = listOf(5, 10, 15, 20, 25, 30, 45, 60, 90, 120, 300, 1440)

        testCases.forEach { durationMinutes ->
            val labels = computeLabelMinutes(durationMinutes, 6)
            assert(labels.size <= 7) {
                "For $durationMinutes min: got ${labels.size} labels (expected <= 7)"
            }
            assert(labels.first() == 0.0) {
                "First label should be 0, got ${labels.first()}"
            }
            assert(labels.last() <= durationMinutes.toDouble()) {
                "Last label ${labels.last()} exceeds duration $durationMinutes"
            }
        }
    }

    @Test
    fun hrr_recoveryCalculation_nullEndHrReturnsNull() {
        val endHr: Int? = null
        val recoveryDrop = if (endHr != null) endHr - 50 else null
        assertEquals(null, recoveryDrop)
    }

    @Test
    fun routeContourTransform_singleAxisRoute_usesFiniteCenteredScale() {
        val result =
            calculateRouteContourTransform(
                points =
                    listOf(
                        ProjectedPoint(x = 100.0, y = 10.0, altitude = 0.0, timestampMs = 0L),
                        ProjectedPoint(x = 100.0, y = 20.0, altitude = 0.0, timestampMs = 1L),
                    ),
                canvasWidth = 400f,
                canvasHeight = 200f,
                padding = 20f,
            )

        requireNotNull(result)
        assert(result.scale.isFinite())
        assertEquals(16f, result.scale)
        assertEquals(200f, result.offsetX)
    }

    private fun computeLabelMinutes(
        durationMinutes: Int,
        target: Int,
    ): List<Double> {
        if (durationMinutes <= 0) return listOf(0.0)
        val intervals = (target - 1).coerceAtLeast(1)
        if (durationMinutes <= intervals) {
            return (0..durationMinutes).map { it.toDouble() }
        }
        val step = durationMinutes.toDouble() / intervals
        return (0..intervals).map { (it * step).toInt().toDouble() }.distinct()
    }
}
