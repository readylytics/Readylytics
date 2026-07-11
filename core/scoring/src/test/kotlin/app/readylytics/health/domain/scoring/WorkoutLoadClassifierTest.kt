package app.readylytics.health.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutLoadClassifierTest {
    private val classifier = WorkoutLoadClassifier()

    @Test
    fun `classifies base-load boundaries exactly`() {
        assertEquals(WorkoutLoadLevel.VERY_LIGHT, classifier.classifyBaseLoad(29.999))
        assertEquals(WorkoutLoadLevel.LIGHT, classifier.classifyBaseLoad(30.0))
        assertEquals(WorkoutLoadLevel.LIGHT, classifier.classifyBaseLoad(69.999))
        assertEquals(WorkoutLoadLevel.MODERATE, classifier.classifyBaseLoad(70.0))
        assertEquals(WorkoutLoadLevel.MODERATE, classifier.classifyBaseLoad(139.999))
        assertEquals(WorkoutLoadLevel.HARD, classifier.classifyBaseLoad(140.0))
        assertEquals(WorkoutLoadLevel.HARD, classifier.classifyBaseLoad(199.999))
        assertEquals(WorkoutLoadLevel.VERY_HARD, classifier.classifyBaseLoad(200.0))
    }

    @Test
    fun `classifies intensity boundaries exactly`() {
        assertEquals(WorkoutIntensityLevel.VERY_LIGHT, classifier.classifyIntensity(0.749))
        assertEquals(WorkoutIntensityLevel.LIGHT, classifier.classifyIntensity(0.75))
        assertEquals(WorkoutIntensityLevel.LIGHT, classifier.classifyIntensity(1.249))
        assertEquals(WorkoutIntensityLevel.MODERATE, classifier.classifyIntensity(1.25))
        assertEquals(WorkoutIntensityLevel.MODERATE, classifier.classifyIntensity(1.749))
        assertEquals(WorkoutIntensityLevel.HARD, classifier.classifyIntensity(1.75))
        assertEquals(WorkoutIntensityLevel.HARD, classifier.classifyIntensity(2.249))
        assertEquals(WorkoutIntensityLevel.VERY_HARD, classifier.classifyIntensity(2.25))
    }

    @Test
    fun `applies expected promotions and never demotes`() {
        data class Case(
            val totalTrimp: Double,
            val trimpPerMinute: Double?,
            val base: WorkoutLoadLevel,
            val final: WorkoutLoadLevel,
        )

        listOf(
            Case(20.0, 2.50, WorkoutLoadLevel.VERY_LIGHT, WorkoutLoadLevel.VERY_LIGHT),
            Case(35.0, 2.30, WorkoutLoadLevel.LIGHT, WorkoutLoadLevel.LIGHT),
            Case(69.0, 2.50, WorkoutLoadLevel.LIGHT, WorkoutLoadLevel.LIGHT),
            Case(70.0, 2.00, WorkoutLoadLevel.MODERATE, WorkoutLoadLevel.MODERATE),
            Case(89.99, 2.00, WorkoutLoadLevel.MODERATE, WorkoutLoadLevel.MODERATE),
            Case(90.0, 1.74, WorkoutLoadLevel.MODERATE, WorkoutLoadLevel.MODERATE),
            Case(90.0, 1.75, WorkoutLoadLevel.MODERATE, WorkoutLoadLevel.HARD),
            Case(120.0, 2.30, WorkoutLoadLevel.MODERATE, WorkoutLoadLevel.HARD),
            Case(139.99, 2.50, WorkoutLoadLevel.MODERATE, WorkoutLoadLevel.HARD),
            Case(140.0, 1.50, WorkoutLoadLevel.HARD, WorkoutLoadLevel.HARD),
            Case(140.0, 2.24, WorkoutLoadLevel.HARD, WorkoutLoadLevel.HARD),
            Case(140.0, 2.25, WorkoutLoadLevel.HARD, WorkoutLoadLevel.VERY_HARD),
            Case(175.0, 2.40, WorkoutLoadLevel.HARD, WorkoutLoadLevel.VERY_HARD),
            Case(199.99, 2.50, WorkoutLoadLevel.HARD, WorkoutLoadLevel.VERY_HARD),
            Case(200.0, 0.80, WorkoutLoadLevel.VERY_HARD, WorkoutLoadLevel.VERY_HARD),
        ).forEach { case ->
            val result = classifier.classify(case.totalTrimp, case.trimpPerMinute)
            assertEquals(case.base, result?.baseLoad)
            assertEquals(case.final, result?.finalLoad)
            assertEquals(case.base != case.final, result?.wasPromoted)
        }
    }

    @Test
    fun `keeps valid base load when intensity unavailable or invalid`() {
        val missingDensity = classifier.classify(50.0, null)
        assertEquals(WorkoutLoadLevel.LIGHT, missingDensity?.finalLoad)
        assertNull(missingDensity?.intensity)

        val invalidDensity = classifier.classify(50.0, -1.0)
        assertEquals(WorkoutLoadLevel.LIGHT, invalidDensity?.finalLoad)
        assertNull(invalidDensity?.intensity)
    }

    @Test
    fun `rejects invalid total trimp values`() {
        assertNull(classifier.classifyBaseLoad(Double.NaN))
        assertNull(classifier.classifyBaseLoad(Double.POSITIVE_INFINITY))
        assertNull(classifier.classifyBaseLoad(Double.NEGATIVE_INFINITY))
        assertNull(classifier.classifyBaseLoad(-0.001))
        assertNull(classifier.classify(Double.NaN, 1.0))
        assertNull(classifier.classify(Double.POSITIVE_INFINITY, 1.0))
        assertNull(classifier.classify(-1.0, 1.0))
    }
}
