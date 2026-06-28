package app.readylytics.health.core.ui.common

import app.readylytics.health.core.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoreDeltaFormatterTest {
    @Test
    fun formatRoundedScoreDelta_returnsNullForMissingValues() {
        assertNull(formatRoundedScoreDelta(currentRounded = null, previousRounded = 80))
        assertNull(formatRoundedScoreDelta(currentRounded = 80, previousRounded = null))
    }

    @Test
    fun formatRoundedScoreDelta_returnsNoChangeForEqualValues() {
        assertEquals(UiText.StringRes(R.string.delta_no_change), formatRoundedScoreDelta(80, 80))
    }

    @Test
    fun formatRoundedScoreDelta_formatsIncreasesAndDecreasesFromRoundedValues() {
        val up = formatRoundedScoreDelta(currentRounded = 81, previousRounded = 80) as UiText.Compound
        assertEquals(UiText.StringRes(R.string.delta_up), up.parts[0])
        assertEquals(" 1", (up.parts[1] as UiText.RawString).value)

        val down = formatRoundedScoreDelta(currentRounded = 79, previousRounded = 81) as UiText.Compound
        assertEquals(UiText.StringRes(R.string.delta_down), down.parts[0])
        assertEquals(" 2", (down.parts[1] as UiText.RawString).value)
    }
}
