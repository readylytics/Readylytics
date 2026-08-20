package app.readylytics.health.domain.model

import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class LoadContextTest {
    @Test
    fun testStrainRatioToLoadContextMapping() {
        assertEquals(LoadContext.BELOW_TYPICAL, 0.79f.toLoadContext())
        assertEquals(LoadContext.SWEET_SPOT, 0.8f.toLoadContext())
        assertEquals(LoadContext.SWEET_SPOT, 1.3f.toLoadContext())
        assertEquals(LoadContext.ELEVATED, 1.31f.toLoadContext())
        assertEquals(LoadContext.ELEVATED, 1.5f.toLoadContext())
        assertEquals(LoadContext.HIGH, 1.51f.toLoadContext())
        assertEquals(LoadContext.UNKNOWN, null.toLoadContext())
        assertEquals(LoadContext.UNKNOWN, Float.NaN.toLoadContext())
        assertEquals(LoadContext.UNKNOWN, (-0.1f).toLoadContext())
    }

    @Test
    fun testSweetSpotBoundaryTracksScoringConstants() {
        assertEquals(LoadContext.SWEET_SPOT, ScoringConstants.Strain.SR_SWEET_SPOT_MAX.toLoadContext())
        assertEquals(
            LoadContext.ELEVATED,
            (ScoringConstants.Strain.SR_SWEET_SPOT_MAX + 0.01f).toLoadContext(),
        )
    }
}
