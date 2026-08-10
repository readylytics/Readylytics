package app.readylytics.health.core.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
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
    fun `tooltip without extra content centers lines and shrinks to content`() {
        composeRule.setContent {
            MaterialTheme {
                DataPointTooltip(
                    isVisible = true,
                    data = DataPointTooltipData(valueText = "Duration: 8h", dateText = "01.08"),
                    onDismissRequest = {},
                )
            }
        }

        val valueBounds = composeRule.onNodeWithText("Duration: 8h").fetchSemanticsNode().boundsInRoot
        val dateBounds = composeRule.onNodeWithText("01.08").fetchSemanticsNode().boundsInRoot
        assertTrue(
            kotlin.math.abs(valueBounds.center.x - dateBounds.center.x) <= 1f,
            "value and date must share the same center axis: value=${valueBounds.center.x}, date=${dateBounds.center.x}",
        )

        val bubble = boundsOfTag(DATA_POINT_TOOLTIP_TAG)
        val maxWidthPx = with(composeRule.density) { 150.dp.toPx() }
        assertTrue(
            bubble.width < maxWidthPx,
            "short tooltip must shrink toward content width instead of forcing 150dp, width=${bubble.width}",
        )
    }

    @Test
    fun `tooltip with pre-date lines left aligns value date and lines`() {
        composeRule.setContent {
            MaterialTheme {
                DataPointTooltip(
                    isVisible = true,
                    data =
                        DataPointTooltipData(
                            valueText = "01.08",
                            dateText = "Duration: 8h 05m",
                            preDateLines = listOf("Bedtime: 11:42 PM - 7:10 AM"),
                        ),
                    onDismissRequest = {},
                )
            }
        }

        val valueLeft =
            composeRule
                .onNodeWithText("01.08")
                .fetchSemanticsNode()
                .boundsInRoot.left
        val dateLeft =
            composeRule
                .onNodeWithText("Duration: 8h 05m")
                .fetchSemanticsNode()
                .boundsInRoot.left
        val bedtimeLeft =
            composeRule
                .onNodeWithText(
                    "Bedtime: 11:42 PM - 7:10 AM",
                ).fetchSemanticsNode()
                .boundsInRoot.left
        assertTrue(
            kotlin.math.abs(valueLeft - dateLeft) <= 1f,
            "value must be left-aligned with date: value=$valueLeft, date=$dateLeft",
        )
        assertTrue(
            kotlin.math.abs(dateLeft - bedtimeLeft) <= 1f,
            "date must be left-aligned with bedtime line: date=$dateLeft, bedtime=$bedtimeLeft",
        )
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

    private fun boundsOfTag(tag: String): androidx.compose.ui.geometry.Rect =
        composeRule
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
}
