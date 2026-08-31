package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class BaselineComputerRhrFallbackTest {
    @Test
    fun `rounded RHR baseline falls back to the default for an empty history`() {
        assertEquals(
            ScoringConstants.DEFAULT_RHR_BPM.roundToInt(),
            BaselineComputer.resolveBaselineRhrRounded(emptyList(), null),
        )
    }
}
