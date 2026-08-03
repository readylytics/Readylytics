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
        val activeStrokeWidthPx = 12f
        val geometry =
            resolveHorseshoeGaugeGeometry(
                canvasSize = Size(width = 120f, height = 90f),
                maximumStrokeWidthPx = activeStrokeWidthPx,
            )

        assertEquals(150f, geometry.startAngle)
        assertEquals(240f, geometry.sweepAngle)
        assertTrue(geometry.topLeft.x - activeStrokeWidthPx / 2f >= 0f)
        assertTrue(geometry.topLeft.y - activeStrokeWidthPx / 2f >= 0f)
        assertTrue(geometry.topLeft.x + geometry.arcSize.width + activeStrokeWidthPx / 2f <= 120f)
        // The 120° bottom opening means only 1.5 radii of the 2r circle are drawn vertically.
        assertTrue(geometry.center.y + geometry.radius * 0.5f + activeStrokeWidthPx / 2f <= 90f)
    }

    @Test
    fun horseshoeGeometry_shrinksForHeightConstrainedGaugeSlot() {
        val activeStrokeWidthPx = 12f
        val wide =
            resolveHorseshoeGaugeGeometry(
                Size(120f, 120f),
                maximumStrokeWidthPx = activeStrokeWidthPx,
            )
        val short =
            resolveHorseshoeGaugeGeometry(
                Size(120f, 60f),
                maximumStrokeWidthPx = activeStrokeWidthPx,
            )

        assertTrue(short.radius < wide.radius)
        assertTrue(short.topLeft.x - activeStrokeWidthPx / 2f >= 0f)
        assertTrue(short.topLeft.x + short.arcSize.width + activeStrokeWidthPx / 2f <= 120f)
        assertTrue(short.topLeft.y - activeStrokeWidthPx / 2f >= 0f)
        assertTrue(short.center.y + short.radius * 0.5f + activeStrokeWidthPx / 2f <= 60f)
    }

    @Test
    fun metricGauge_acceptsNullMarker_andClampsOutsideRange_withSingleTrackContract() {
        composeTestRule.setContent {
            M3MetricGauge(
                markerFraction = null,
                activeColor = Color.Red,
                markerColor = Color.White,
                animateMarker = false,
            )
            M3MetricGauge(
                markerFraction = 1.5f,
                activeColor = Color.Red,
                markerColor = Color.White,
                animateMarker = false,
            )
            M3MetricGauge(
                markerFraction = 1f,
                activeColor = Color.Green,
                markerColor = Color.White,
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
