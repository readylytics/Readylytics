package app.readylytics.health.core.scoring.domain.scoring

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StageIntegrityTest {
    @Test
    fun `no breakdown when duration is positive but deep and rem are both 0`() {
        assertTrue(StageIntegrity.hasNoStageBreakdown(durationMinutes = 480, deepMinutes = 0, remMinutes = 0))
    }

    @Test
    fun `breakdown present when deep or rem is positive`() {
        assertFalse(StageIntegrity.hasNoStageBreakdown(durationMinutes = 480, deepMinutes = 90, remMinutes = 0))
        assertFalse(StageIntegrity.hasNoStageBreakdown(durationMinutes = 480, deepMinutes = 0, remMinutes = 90))
    }

    @Test
    fun `no breakdown is false when duration is zero`() {
        assertFalse(StageIntegrity.hasNoStageBreakdown(durationMinutes = 0, deepMinutes = 0, remMinutes = 0))
    }
}
