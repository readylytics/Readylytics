package app.readylytics.health.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreDeltaFormatterTest {
    @Test
    fun formatRoundedScoreDelta_returnsNeutralForMissingOrEqualValues() {
        assertEquals("—", formatRoundedScoreDelta(currentRounded = null, previousRounded = 80))
        assertEquals("—", formatRoundedScoreDelta(currentRounded = 80, previousRounded = null))
        assertEquals("—", formatRoundedScoreDelta(currentRounded = 80, previousRounded = 80))
    }

    @Test
    fun formatRoundedScoreDelta_formatsIncreasesAndDecreasesFromRoundedValues() {
        assertEquals("↑ 1", formatRoundedScoreDelta(currentRounded = 81, previousRounded = 80))
        assertEquals("↓ 2", formatRoundedScoreDelta(currentRounded = 79, previousRounded = 81))
    }
}
