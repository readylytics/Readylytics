package app.readylytics.health.domain.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ModeSpecTest {
    private val allModes =
        ModeSpec(
            legacyDefaultMode = DashboardCardDisplayMode.VALUE,
            supportedModes = DashboardCardDisplayMode.entries,
        )

    @Test
    fun `null request resolves to legacy default`() {
        assertEquals(DashboardCardDisplayMode.VALUE, allModes.resolveRequestedMode(null))
    }

    @Test
    fun `supported request resolves to itself`() {
        assertEquals(DashboardCardDisplayMode.BAR, allModes.resolveRequestedMode(DashboardCardDisplayMode.BAR))
    }

    @Test
    fun `unsupported request resolves to legacy default`() {
        val valueOnly =
            ModeSpec(
                legacyDefaultMode = DashboardCardDisplayMode.VALUE,
                supportedModes = listOf(DashboardCardDisplayMode.VALUE),
            )
        assertEquals(
            DashboardCardDisplayMode.VALUE,
            valueOnly.resolveRequestedMode(DashboardCardDisplayMode.GAUGE),
        )
    }
}
