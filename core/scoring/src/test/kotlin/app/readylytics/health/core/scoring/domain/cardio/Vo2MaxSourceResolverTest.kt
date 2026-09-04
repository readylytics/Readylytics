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
            estimatedVo2Max = 45.0f,
            estimatedSource = Vo2MaxSourceResolver.SOURCE_ESTIMATED_UTH,
        )
        assertEquals(48.0f, result.vo2Max)
        assertEquals("WEARABLE", result.source)
    }

    @Test
    fun autoFallsBackToUthEstimateWhenWearableNull() {
        val result = resolver.resolve(
            mode = Vo2MaxSourceMode.AUTO,
            wearableVo2Max = null,
            estimatedVo2Max = 45.0f,
            estimatedSource = Vo2MaxSourceResolver.SOURCE_ESTIMATED_UTH,
        )
        assertEquals(45.0f, result.vo2Max)
        assertEquals("ESTIMATED_UTH", result.source)
    }

    @Test
    fun autoFallsBackToMaterkoAdaptedEstimateWhenWearableNull() {
        val result = resolver.resolve(
            mode = Vo2MaxSourceMode.AUTO,
            wearableVo2Max = null,
            estimatedVo2Max = 39.0f,
            estimatedSource = Vo2MaxSourceResolver.SOURCE_ESTIMATED_MATERKO_ADAPTED,
        )
        assertEquals(39.0f, result.vo2Max)
        assertEquals("ESTIMATED_MATERKO_ADAPTED", result.source)
    }

    @Test
    fun wearableOnlyIgnoresEstimate() {
        val result = resolver.resolve(
            mode = Vo2MaxSourceMode.WEARABLE_ONLY,
            wearableVo2Max = null,
            estimatedVo2Max = 45.0f,
            estimatedSource = Vo2MaxSourceResolver.SOURCE_ESTIMATED_UTH,
        )
        assertNull(result.vo2Max)
        assertNull(result.source)
    }

    @Test
    fun wearableOnlyUsesWearableWhenAvailable() {
        val result = resolver.resolve(
            mode = Vo2MaxSourceMode.WEARABLE_ONLY,
            wearableVo2Max = 48.0f,
            estimatedVo2Max = 45.0f,
            estimatedSource = Vo2MaxSourceResolver.SOURCE_ESTIMATED_UTH,
        )
        assertEquals(48.0f, result.vo2Max)
        assertEquals("WEARABLE", result.source)
    }

    @Test
    fun estimatedOnlyEmitsEstimatedSourceTag() {
        val result = resolver.resolve(
            mode = Vo2MaxSourceMode.ESTIMATED_ONLY,
            wearableVo2Max = 48.0f,
            estimatedVo2Max = 39.0f,
            estimatedSource = Vo2MaxSourceResolver.SOURCE_ESTIMATED_MATERKO_ADAPTED,
        )
        assertEquals(39.0f, result.vo2Max)
        assertEquals("ESTIMATED_MATERKO_ADAPTED", result.source)
    }
}
