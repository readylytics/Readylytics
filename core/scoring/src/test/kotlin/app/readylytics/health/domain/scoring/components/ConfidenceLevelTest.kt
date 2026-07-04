package app.readylytics.health.domain.scoring.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfidenceLevelTest {
    @Test
    fun `NOT_READY has correct displayName`() {
        assertEquals("Not Ready", ConfidenceLevel.NOT_READY.displayName)
    }

    @Test
    fun `LOW has correct displayName`() {
        assertEquals("Low", ConfidenceLevel.LOW.displayName)
    }

    @Test
    fun `MEDIUM has correct displayName`() {
        assertEquals("Medium", ConfidenceLevel.MEDIUM.displayName)
    }

    @Test
    fun `HIGH has correct displayName`() {
        assertEquals("High", ConfidenceLevel.HIGH.displayName)
    }

    @Test
    fun `all enum values are defined`() {
        val values = ConfidenceLevel.values()
        assertEquals(4, values.size)
    }

    @Test
    fun `enum values can be iterated`() {
        val values = ConfidenceLevel.values()
        assertTrue(values.contains(ConfidenceLevel.NOT_READY))
        assertTrue(values.contains(ConfidenceLevel.LOW))
        assertTrue(values.contains(ConfidenceLevel.MEDIUM))
        assertTrue(values.contains(ConfidenceLevel.HIGH))
    }

    @Test
    fun `enum valueOf works for all values`() {
        assertEquals(ConfidenceLevel.NOT_READY, ConfidenceLevel.valueOf("NOT_READY"))
        assertEquals(ConfidenceLevel.LOW, ConfidenceLevel.valueOf("LOW"))
        assertEquals(ConfidenceLevel.MEDIUM, ConfidenceLevel.valueOf("MEDIUM"))
        assertEquals(ConfidenceLevel.HIGH, ConfidenceLevel.valueOf("HIGH"))
    }

    @Test
    fun `displayNames are unique`() {
        val displayNames = ConfidenceLevel.values().map { it.displayName }
        assertEquals(4, displayNames.distinct().size)
    }

    @Test
    fun `enum order is preserved`() {
        val values = ConfidenceLevel.values()
        assertEquals(ConfidenceLevel.NOT_READY, values[0])
        assertEquals(ConfidenceLevel.LOW, values[1])
        assertEquals(ConfidenceLevel.MEDIUM, values[2])
        assertEquals(ConfidenceLevel.HIGH, values[3])
    }
}
