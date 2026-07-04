package app.readylytics.health.domain.scoring.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CircadianConsistencyConfigTest {
    @Test
    fun `config with valid thresholdMinutes is enabled`() {
        val config = CircadianConsistencyConfig(thresholdMinutes = 30)
        assertFalse(config.isDisabled)
    }

    @Test
    fun `config with Int MAX_VALUE threshold is disabled`() {
        val config = CircadianConsistencyConfig(thresholdMinutes = Int.MAX_VALUE)
        assertTrue(config.isDisabled)
    }

    @Test
    fun `config stores thresholdMinutes`() {
        val config = CircadianConsistencyConfig(thresholdMinutes = 45)
        assertEquals(45, config.thresholdMinutes)
    }

    @Test
    fun `config has default evaluationDays of 14`() {
        val config = CircadianConsistencyConfig(thresholdMinutes = 30)
        assertEquals(14, config.evaluationDays)
    }

    @Test
    fun `config has default baselineDays of 7`() {
        val config = CircadianConsistencyConfig(thresholdMinutes = 30)
        assertEquals(7, config.baselineDays)
    }

    @Test
    fun `config accepts custom evaluationDays`() {
        val config = CircadianConsistencyConfig(
            thresholdMinutes = 30,
            evaluationDays = 21
        )
        assertEquals(21, config.evaluationDays)
    }

    @Test
    fun `config accepts custom baselineDays`() {
        val config = CircadianConsistencyConfig(
            thresholdMinutes = 30,
            baselineDays = 10
        )
        assertEquals(10, config.baselineDays)
    }

    @Test
    fun `config throws on negative thresholdMinutes`() {
        try {
            CircadianConsistencyConfig(thresholdMinutes = -1)
            assertTrue(false, "Should throw on negative threshold")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("non-negative") == true)
        }
    }

    @Test
    fun `config throws on zero evaluationDays`() {
        try {
            CircadianConsistencyConfig(thresholdMinutes = 30, evaluationDays = 0)
            assertTrue(false, "Should throw on zero evaluationDays")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("positive") == true)
        }
    }

    @Test
    fun `config throws on negative baselineDays`() {
        try {
            CircadianConsistencyConfig(thresholdMinutes = 30, baselineDays = -1)
            assertTrue(false, "Should throw on negative baselineDays")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("positive") == true)
        }
    }

    @Test
    fun `config accepts zero thresholdMinutes`() {
        val config = CircadianConsistencyConfig(thresholdMinutes = 0)
        assertEquals(0, config.thresholdMinutes)
        assertFalse(config.isDisabled)
    }
}
