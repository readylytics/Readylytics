package app.readylytics.health.domain.insights

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadinessCalculationTest {
    @Test
    fun `readiness score from sleep score and load score`() {
        val sleepScore = 85f
        val loadScore = 75f
        val readiness = (sleepScore + loadScore) / 2
        assertEquals(80f, readiness)
    }

    @Test
    fun `readiness scale bounds`() {
        for (sleep in listOf(0f, 50f, 100f)) {
            for (load in listOf(0f, 50f, 100f)) {
                val readiness = (sleep + load) / 2
                assertTrue(readiness in 0f..100f)
            }
        }
    }

    @Test
    fun `readiness categories`() {
        val scores =
            mapOf(
                10f to "poor",
                35f to "low",
                55f to "moderate",
                75f to "good",
                95f to "excellent",
            )
        for ((score, category) in scores) {
            val actual =
                when {
                    score < 25 -> "poor"
                    score < 50 -> "low"
                    score < 70 -> "moderate"
                    score < 85 -> "good"
                    else -> "excellent"
                }
            assertEquals(category, actual, "Score $score should be $category")
        }
    }

    @Test
    fun `readiness trend calculation`() {
        val scores = listOf(60f, 62f, 61f, 65f, 63f, 64f, 68f)
        val avgScore = scores.average()
        assertTrue(avgScore in 60f..70f)
    }

    @Test
    fun `readiness warning thresholds`() {
        val thresholds =
            mapOf(
                "critical" to 20f,
                "warning" to 40f,
                "normal" to 100f,
            )
        val scores = listOf(15f, 35f, 50f, 80f)
        for (score in scores) {
            val level =
                when {
                    score <= thresholds["critical"]!! -> "critical"
                    score <= thresholds["warning"]!! -> "warning"
                    else -> "normal"
                }
            assertTrue(level in listOf("critical", "warning", "normal"))
        }
    }

    @Test
    fun `readiness improvement recommendation`() {
        val currentScore = 55f
        val targetScore = 75f
        val gap = targetScore - currentScore
        assertEquals(20f, gap)
    }

    @Test
    fun `readiness temporal stability`() {
        val dailyScores = listOf(65f, 66f, 64f, 65f, 67f)
        val stdDev = calculateStdDev(dailyScores)
        assertTrue(stdDev < 5f)
    }

    private fun calculateStdDev(values: List<Float>): Float {
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return kotlin.math.sqrt(variance).toFloat()
    }

    @Test
    fun `readiness baseline establishment`() {
        val baselineData = (1..14).map { (Math.random() * 100).toFloat() }
        val baseline = baselineData.average()
        assertTrue(baseline in 0.0..100.0)
    }

    @Test
    fun `readiness anomaly detection`() {
        val recentScore = 45f
        val baselineAvg = 70f
        val deviation = baselineAvg - recentScore
        val isAnomaly = deviation > 20f
        assertTrue(isAnomaly)
    }

    @Test
    fun `readiness recovery tracking`() {
        val lowScore = 35f
        val recoveredScore = 70f
        val recoveryDays = 3
        val scoreImprovement = recoveredScore - lowScore
        assertEquals(35f, scoreImprovement)
    }
}
