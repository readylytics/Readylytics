package app.readylytics.health.core.scoring.domain.airecommendation

import app.readylytics.health.domain.model.LoadContext
import app.readylytics.health.core.scoring.domain.scoring.WorkoutLoadClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecommendedLoadCalculatorTest {
    private val calculator = RecommendedLoadCalculator(WorkoutLoadClassifier())

    @Test
    fun `compute maps every load context before completed training`() {
        val expectedByContext =
            mapOf(
                LoadContext.BELOW_TYPICAL to "HIGH",
                LoadContext.SWEET_SPOT to "NORMAL",
                LoadContext.ELEVATED to "MODERATE",
                LoadContext.HIGH to "LIGHT",
            )

        expectedByContext.forEach { (loadContext, expected) ->
            assertEquals(expected, calculator.compute(loadContext, todayTrimp = 0f))
        }
        assertNull(calculator.compute(LoadContext.UNKNOWN, todayTrimp = 0f))
        assertNull(calculator.compute(loadContext = null, todayTrimp = 0f))
    }

    @Test
    fun `compute downgrades for every completed training tier`() {
        val expectedByTodayTrimp =
            mapOf(
                0f to "HIGH",
                30f to "NORMAL",
                70f to "MODERATE",
                140f to "LIGHT",
                200f to "LIGHT",
            )

        expectedByTodayTrimp.forEach { (todayTrimp, expected) ->
            assertEquals(
                expected,
                calculator.compute(LoadContext.BELOW_TYPICAL, todayTrimp),
            )
        }
    }

    @Test
    fun `compute floors a very hard completed load at light`() {
        assertEquals(
            "LIGHT",
            calculator.compute(LoadContext.BELOW_TYPICAL, todayTrimp = 200f),
        )
    }

    @Test
    fun `compute treats missing completed training as zero`() {
        assertEquals(
            "HIGH",
            calculator.compute(LoadContext.BELOW_TYPICAL, todayTrimp = 0f),
        )
        assertEquals(
            "HIGH",
            calculator.compute(LoadContext.BELOW_TYPICAL, todayTrimp = null),
        )
    }
}
