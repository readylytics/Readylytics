package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.preferences.UnitSystem
import app.readylytics.health.core.scoring.domain.workouts.weekly.ActivityMetricType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityVolumeFormatterTest {
    @Test
    fun `distance formats in metric kilometers`() {
        assertEquals(
            "5.0 km",
            ActivityVolumeFormatter.formatValue(5000f, ActivityMetricType.DISTANCE, UnitSystem.METRIC),
        )
    }

    @Test
    fun `distance formats in imperial miles`() {
        assertEquals(
            "3.1 mi",
            ActivityVolumeFormatter.formatValue(5000f, ActivityMetricType.DISTANCE, UnitSystem.IMPERIAL),
        )
    }

    @Test
    fun `zero distance renders as an em dash`() {
        assertEquals("—", ActivityVolumeFormatter.formatValue(0f, ActivityMetricType.DISTANCE, UnitSystem.METRIC))
    }

    @Test
    fun `duration formats minutes below an hour`() {
        assertEquals("42m", ActivityVolumeFormatter.formatValue(42f, ActivityMetricType.DURATION, UnitSystem.METRIC))
    }

    @Test
    fun `duration formats hours and minutes regardless of unit system`() {
        assertEquals(
            "1h 15m",
            ActivityVolumeFormatter.formatValue(75f, ActivityMetricType.DURATION, UnitSystem.IMPERIAL),
        )
    }

    @Test
    fun `positive percent delta carries a sign`() {
        assertEquals("+24%", ActivityVolumeFormatter.formatPercentDelta(24.4f))
    }

    @Test
    fun `negative percent delta carries a sign`() {
        assertEquals("-18%", ActivityVolumeFormatter.formatPercentDelta(-18.2f))
    }

    @Test
    fun `undefined percent change returns null for the new label`() {
        assertNull(ActivityVolumeFormatter.formatPercentDelta(null))
    }
}
