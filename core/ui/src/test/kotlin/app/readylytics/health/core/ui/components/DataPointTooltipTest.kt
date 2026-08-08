package app.readylytics.health.core.ui.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataPointTooltipTest {
    @Test
    fun `supplemental tooltip lines are retained before the date`() {
        val data =
            DataPointTooltipData(
                valueText = "Duration: 8h 05m",
                dateText = "Bedtime: 11:42 PM - 7:10 AM",
                preDateLines =
                    listOf(
                        "Naps:",
                        "• 2:00 PM – 2:35 PM (35m)",
                    ),
                extraLine = "01.08",
            )

        assertEquals("Bedtime: 11:42 PM - 7:10 AM", data.dateText)
        assertEquals(listOf("Naps:", "• 2:00 PM – 2:35 PM (35m)"), data.preDateLines)
        assertEquals("01.08", data.extraLine)
    }

    @Test
    fun `tooltip data omits supplemental section when there are no naps`() {
        val data =
            DataPointTooltipData(
                valueText = "Duration: 8h",
                dateText = "Bedtime: 11:00 PM - 7:00 AM",
                extraLine = "01.08",
            )

        assertTrue(data.preDateLines.isEmpty())
    }
}
