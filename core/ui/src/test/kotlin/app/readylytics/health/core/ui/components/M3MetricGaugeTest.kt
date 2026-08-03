package app.readylytics.health.core.ui.components

import androidx.compose.ui.geometry.Size
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class M3MetricGaugeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun horseshoeGeometry_usesApprovedSweepAndStaysInsideItsCanvas() {
        val strokeWidthPx = 10f
        val geometry =
            resolveHorseshoeGaugeGeometry(
                canvasSize = Size(width = 120f, height = 90f),
                strokeWidthPx = strokeWidthPx,
            )

        assertEquals(150f, geometry.startAngle)
        assertEquals(240f, geometry.sweepAngle)
        assertTrue(geometry.topLeft.x >= 0f)
        assertTrue(geometry.topLeft.y >= 0f)
        assertTrue(geometry.topLeft.x + geometry.arcSize.width <= 120f)
        // The 120° bottom opening means only 1.5 radii of the 2r circle are drawn vertically.
        assertTrue(geometry.center.y + geometry.radius * 0.5f + strokeWidthPx / 2f <= 90f)
    }

    @Test
    fun horseshoeGeometry_shrinksForHeightConstrainedGaugeSlot() {
        val strokeWidthPx = 10f
        val wide = resolveHorseshoeGaugeGeometry(Size(120f, 120f), strokeWidthPx = strokeWidthPx)
        val short = resolveHorseshoeGaugeGeometry(Size(120f, 60f), strokeWidthPx = strokeWidthPx)

        assertTrue(short.radius < wide.radius)
        assertTrue(short.center.y + short.radius * 0.5f + strokeWidthPx / 2f <= 60f)
    }

    @Test
    fun metricGauge_acceptsNullMarker_andClampsOutsideRange_withSingleTrackContract() {
        composeTestRule.setContent {
            M3MetricGauge(
                markerFraction = null,
                activeColor = Color.Red,
                animateMarker = false,
            )
            M3MetricGauge(
                markerFraction = 1.5f,
                activeColor = Color.Red,
                animateMarker = false,
            )
        }
        val unmergedRoot = composeTestRule.onRoot(useUnmergedTree = true)
        assert(unmergedRoot.fetchSemanticsNode().children.isEmpty()) {
            "Expected gauge Canvas to add no semantic children"
        }
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
