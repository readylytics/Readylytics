package app.readylytics.health.core.scoring.domain.cardio

import app.readylytics.health.core.model.domain.preferences.Vo2MaxSourceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Vo2MaxSourceResolverTest {
    private val resolver = Vo2MaxSourceResolver()

    @Test
    fun autoPrefersWearableOverEstimate() {
        val result = resolver.resolve(
            mode = Vo2MaxSourceMode.AUTO,
            wearableVo2Max = 48.0f,
            uthEstimatedVo2Max = 45.0f
        )
        assertEquals(48.0f, result.vo2Max)
        assertEquals("WEARABLE", result.source)
    }

    @Test
    fun autoFallsBackToEstimateWhenWearableNull() {
        val result = resolver.resolve(
            mode = Vo2MaxSourceMode.AUTO,
            wearableVo2Max = null,
            uthEstimatedVo2Max = 45.0f
        )
        assertEquals(45.0f, result.vo2Max)
        assertEquals("ESTIMATED_UTH", result.source)
    }

    @Test
    fun wearableOnlyIgnoresEstimate() {
        val result = resolver.resolve(
            mode = Vo2MaxSourceMode.WEARABLE_ONLY,
            wearableVo2Max = null,
            uthEstimatedVo2Max = 45.0f
        )
        assertNull(result.vo2Max)
        assertNull(result.source)
    }

    @Test
    fun estimatedOnlyIgnoresWearable() {
        val result = resolver.resolve(
            mode = Vo2MaxSourceMode.ESTIMATED_ONLY,
            wearableVo2Max = 48.0f,
            uthEstimatedVo2Max = 45.0f
        )
        assertEquals(45.0f, result.vo2Max)
        assertEquals("ESTIMATED_UTH", result.source)
    }
}
