package app.readylytics.health.ui.workouts

import org.junit.Assert.assertEquals
import org.junit.Test

class RasSummaryValueTextStyleTest {
    @Test
    fun rasTotalUsesTitleEmphasis() {
        assertEquals(RasSummaryValueTextStyle.TITLE, rasTotalValueTextStyle())
    }
}
