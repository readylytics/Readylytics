package app.readylytics.health.core.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class M3MetricGaugeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun metricGauge_acceptsNullMarker_andClampsOutsideRange() {
        composeTestRule.setContent {
            M3MetricGauge(
                markerFraction = null,
                activeColor = Color.Red,
                segments = emptyList(),
                animateMarker = false,
            )
            M3MetricGauge(
                markerFraction = 1.5f,
                activeColor = Color.Red,
                segments = listOf(M3GaugeSegment(0f, 1f, Color.Gray)),
                animateMarker = false,
            )
        }
        val unmergedRoot = composeTestRule.onRoot(useUnmergedTree = true)
        assert(unmergedRoot.fetchSemanticsNode().children.isEmpty()) { "Expected no semantic children" }
    }

    @Test
    fun m3ScoreGaugeCard_regression_semanticsAndRendering() {
        composeTestRule.setContent {
            M3ScoreGaugeCard(
                title = "Test Score",
                score = 86f,
                displayText = "86",
                unitText = "pts",
                onClick = {},
            )
        }

        composeTestRule.onNodeWithText("86").assertExists()
        composeTestRule.onNodeWithText("Test Score").assertExists()

        composeTestRule.onNodeWithContentDescription("Test Score: 86 pts").assertHasClickAction()
    }
}
