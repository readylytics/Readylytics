package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.sync.StepAttribution
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StepAttributionParityTest {
    private val day = LocalDate.of(2026, 1, 15)

    @Test
    fun `device selected, no data, not recompute-only, resolves to 0`() {
        assertEquals(0L, StepAttribution.resolve(day, emptyMap(), stepsDeviceSelected = true, recomputeOnly = false))
    }

    @Test
    fun `no device selected, no data, resolves to null (preserve stored)`() {
        assertEquals(null, StepAttribution.resolve(day, emptyMap(), stepsDeviceSelected = false, recomputeOnly = false))
    }

    @Test
    fun `device selected, data present, resolves to the fetched value`() {
        val steps = mapOf(day to 4200L)
        assertEquals(4200L, StepAttribution.resolve(day, steps, stepsDeviceSelected = true, recomputeOnly = false))
    }

    @Test
    fun `recompute-only always resolves to null regardless of device selection`() {
        assertEquals(null, StepAttribution.resolve(day, emptyMap(), stepsDeviceSelected = true, recomputeOnly = true))
    }
}
