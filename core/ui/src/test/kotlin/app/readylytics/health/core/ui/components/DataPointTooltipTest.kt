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

    @Test
    fun `tooltip renders naps between bedtime and date`() {
        composeRule.setContent {
            MaterialTheme {
                DataPointTooltip(
                    isVisible = true,
                    data =
                        DataPointTooltipData(
                            valueText = "Duration: 8h 05m",
                            dateText = "Bedtime: 11:42 PM - 7:10 AM",
                            preDateLines = listOf("Naps:", "• 2:00 PM – 2:35 PM (35m)"),
                            extraLine = "01.08",
                        ),
                    onDismissRequest = {},
                )
            }
        }

        val bedtimeTop = textTop("Bedtime: 11:42 PM - 7:10 AM")
        val napsHeadingTop = textTop("Naps:")
        val napItemTop = textTop("• 2:00 PM – 2:35 PM (35m)")
        val dateTop = textTop("01.08")

        assertTrue(bedtimeTop < napsHeadingTop)
        assertTrue(napsHeadingTop < napItemTop)
        assertTrue(napItemTop < dateTop)
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
