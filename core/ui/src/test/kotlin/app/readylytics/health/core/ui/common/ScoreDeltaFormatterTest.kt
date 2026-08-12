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

    @Test
    fun assessDeltaOutcome_returnsNullWhenEitherValueMissing() {
        assertNull(
            assessDeltaOutcome(
                currentRounded = null,
                previousRounded = 80,
                direction = DeltaDirection.HIGHER_IS_BETTER,
            ),
        )
        assertNull(
            assessDeltaOutcome(currentRounded = 80, previousRounded = null, direction = DeltaDirection.LOWER_IS_BETTER),
        )
    }

    @Test
    fun assessDeltaOutcome_returnsNeutralForEqualValues() {
        assertEquals(
            DeltaOutcome.NEUTRAL,
            assessDeltaOutcome(currentRounded = 80, previousRounded = 80, direction = DeltaDirection.HIGHER_IS_BETTER),
        )
    }

    @Test
    fun assessDeltaOutcome_highIsBetterMapsUpToImprovedAndDownToWorsened() {
        assertEquals(
            DeltaOutcome.IMPROVED,
            assessDeltaOutcome(currentRounded = 81, previousRounded = 80, direction = DeltaDirection.HIGHER_IS_BETTER),
        )
        assertEquals(
            DeltaOutcome.WORSENED,
            assessDeltaOutcome(currentRounded = 79, previousRounded = 81, direction = DeltaDirection.HIGHER_IS_BETTER),
        )
    }

    @Test
    fun assessDeltaOutcome_lowerIsBetterMapsUpToWorsenedAndDownToImproved() {
        assertEquals(
            DeltaOutcome.WORSENED,
            assessDeltaOutcome(currentRounded = 81, previousRounded = 80, direction = DeltaDirection.LOWER_IS_BETTER),
        )
        assertEquals(
            DeltaOutcome.IMPROVED,
            assessDeltaOutcome(currentRounded = 79, previousRounded = 81, direction = DeltaDirection.LOWER_IS_BETTER),
        )
    }

    @Test
    fun assessDeltaOutcome_neutralDirectionAlwaysNeutral() {
        assertEquals(
            DeltaOutcome.NEUTRAL,
            assessDeltaOutcome(currentRounded = 81, previousRounded = 80, direction = DeltaDirection.NEUTRAL),
        )
        assertEquals(
            DeltaOutcome.NEUTRAL,
            assessDeltaOutcome(currentRounded = 79, previousRounded = 81, direction = DeltaDirection.NEUTRAL),
        )
    }
}
