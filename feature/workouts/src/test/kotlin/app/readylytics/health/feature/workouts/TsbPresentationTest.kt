package app.readylytics.health.feature.workouts

import org.junit.Assert.assertEquals
import org.junit.Test

class TsbPresentationTest {
    @Test
    fun formatsTsbValueWithSign() {
        val positive = 14.2f
        val negative = -8.5f
        assertEquals("+14", String.format(java.util.Locale.US, "%+d", positive.toInt()))
        assertEquals("-8", String.format(java.util.Locale.US, "%+d", negative.toInt()))
    }
}
