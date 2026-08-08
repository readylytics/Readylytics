package app.readylytics.health.core.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataPointTooltipTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tooltip data orders date then duration then details`() {
        val data =
            DataPointTooltipData(
                valueText = "01.08",
                dateText = "Duration: 8h 05m",
                preDateLines =
                    listOf(
                        "Bedtime: 11:42 PM - 7:10 AM",
                        "Naps:",
                        "• 2:00 PM – 2:35 PM (35m)",
                    ),
            )

        assertEquals("01.08", data.valueText)
        assertEquals("Duration: 8h 05m", data.dateText)
        assertEquals(
            listOf(
                "Bedtime: 11:42 PM - 7:10 AM",
                "Naps:",
                "• 2:00 PM – 2:35 PM (35m)",
            ),
            data.preDateLines,
        )
        assertEquals(null, data.extraLine)
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

    @Test
    fun `tooltip renders naps between bedtime and date`() {
        composeRule.setContent {
            MaterialTheme {
                DataPointTooltip(
                    isVisible = true,
                    data =
                        DataPointTooltipData(
                            valueText = "01.08",
                            dateText = "Duration: 8h 05m",
                            preDateLines =
                                listOf(
                                    "Bedtime: 11:42 PM - 7:10 AM",
                                    "Naps:",
                                    "• 2:00 PM – 2:35 PM (35m)",
                                ),
                        ),
                    onDismissRequest = {},
                )
            }
        }

        val dateTop = textTop("01.08")
        val durationTop = textTop("Duration: 8h 05m")
        val bedtimeTop = textTop("Bedtime: 11:42 PM - 7:10 AM")
        val napsHeadingTop = textTop("Naps:")
        val napItemTop = textTop("• 2:00 PM – 2:35 PM (35m)")

        assertTrue(dateTop < durationTop)
        assertTrue(durationTop < bedtimeTop)
        assertTrue(bedtimeTop < napsHeadingTop)
        assertTrue(napsHeadingTop < napItemTop)
    }

    @Test
    fun `tooltip does not render naps heading without nap lines`() {
        composeRule.setContent {
            MaterialTheme {
                DataPointTooltip(
                    isVisible = true,
                    data =
                        DataPointTooltipData(
                            valueText = "Duration: 8h",
                            dateText = "Bedtime: 11:00 PM - 7:00 AM",
                            extraLine = "01.08",
                        ),
                    onDismissRequest = {},
                )
            }
        }

        assertTrue(composeRule.onAllNodesWithText("Naps:").fetchSemanticsNodes().isEmpty())
    }

    private fun textTop(text: String): Float =
        composeRule
            .onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .top
}
