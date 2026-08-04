package app.readylytics.health.core.ui.components.metriccard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalMetricCardSpecTest {
    @Test
    fun specProperties_defaultUsesDeltaPillToFalse() {
        val spec =
            UniversalMetricCardSpec(
                supportedModes = listOf(UniversalCardDisplayMode.GAUGE, UniversalCardDisplayMode.BAR),
            )

        assertEquals(listOf(UniversalCardDisplayMode.GAUGE, UniversalCardDisplayMode.BAR), spec.supportedModes)
        assertFalse(spec.usesDeltaPill)
    }

    @Test
    fun specProperties_customUsesDeltaPill() {
        val spec =
            UniversalMetricCardSpec(
                supportedModes = listOf(UniversalCardDisplayMode.VALUE),
                usesDeltaPill = true,
            )

        assertEquals(listOf(UniversalCardDisplayMode.VALUE), spec.supportedModes)
        assertTrue(spec.usesDeltaPill)
    }
}
