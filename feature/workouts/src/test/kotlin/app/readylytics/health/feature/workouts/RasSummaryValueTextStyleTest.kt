package app.readylytics.health.feature.workouts

import org.junit.Assert.assertEquals
import org.junit.Test

class RasSummaryValueTextStyleTest {
    @Test
    fun rasTotalUsesTitleEmphasis() {
        assertEquals(RasSummaryValueTextStyle.TITLE, rasTotalValueTextStyle())
    }
}
