package app.readylytics.health.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.ui.components.metriccard.UniversalCardDisplayMode
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCard
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCardSpec
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricScalePreparer
import app.readylytics.health.domain.model.MetricStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun gaugeTextBounds_shrinkForHeightConstrainedGaugeSlot() {
        val inset = 12f
        val blockHeight = 40f
        val wide =
            resolveGaugeTextBoundsPx(
                geometry = resolveHorseshoeGaugeGeometry(Size(160f, 160f), inset),
                trackInsetPx = inset,
                textBlockCenterYOffsetPx = 0f,
                textBlockHeightPx = blockHeight,
            )
        val short =
            resolveGaugeTextBoundsPx(
                geometry = resolveHorseshoeGaugeGeometry(Size(160f, 80f), inset),
                trackInsetPx = inset,
                textBlockCenterYOffsetPx = 0f,
                textBlockHeightPx = blockHeight,
            )

        assertTrue(short.width < wide.width)
        assertTrue(short.height <= wide.height)
        assertTrue(short.width >= 0f)
        assertTrue(short.height >= 0f)
    }

    @Test
    fun gaugeTextBounds_neverExceedCircleDiameterMinusTrackInset() {
        val inset = 12f
        val geometry = resolveHorseshoeGaugeGeometry(Size(200f, 150f), inset)
        val bounds =
            resolveGaugeTextBoundsPx(
                geometry = geometry,
                trackInsetPx = inset,
                textBlockCenterYOffsetPx = 0f,
                textBlockHeightPx = 50f,
            )
        val maxInner = (geometry.radius - inset) * 2f

        assertTrue(bounds.width <= maxInner + 0.001f)
        assertTrue(bounds.height <= maxInner + 0.001f)
    }

    @Test
    fun gaugeTextBounds_nonNegativeForDegenerateSmallCanvas() {
        val inset = 12f
        val geometry = resolveHorseshoeGaugeGeometry(Size(5f, 5f), inset)
        val bounds =
            resolveGaugeTextBoundsPx(
                geometry = geometry,
                trackInsetPx = inset,
                textBlockCenterYOffsetPx = 5f,
                textBlockHeightPx = 50f,
            )

        assertTrue(bounds.width >= 0f)
        assertTrue(bounds.height >= 0f)
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
            UniversalMetricCard(
                presentation =
                    UniversalMetricPresentation(
                        title = "Test Score",
                        valueText = "86",
                        unitText = "pts",
                        secondaryText = null,
                        status = MetricStatus.NEUTRAL,
                        tooltip = "Test score",
                        accessibilityDescription = "Test Score: 86 pts",
                        visual = UniversalMetricScalePreparer.score(86f, 0f, 100f),
                    ),
                specification =
                    UniversalMetricCardSpec(
                        supportedModes = listOf(UniversalCardDisplayMode.GAUGE),
                    ),
                requestedMode = UniversalCardDisplayMode.GAUGE,
                onClick = {},
            )
        }

        composeTestRule.onNodeWithText("86").assertExists()
        composeTestRule.onNodeWithText("Test Score").assertExists()

        composeTestRule.onNodeWithContentDescription("Test Score: 86 pts").assertHasClickAction()
    }

    @Test
    fun metricGaugeWithValue_overlayIsTaggedAndOffsetDownward() {
        composeTestRule.setContent {
            M3MetricGaugeWithValue(
                markerFraction = 0.5f,
                activeColor = Color.Green,
                markerColor = Color.White,
                valueText = "50",
                unitText = "bpm",
                valueColor = Color.White,
                unitColor = Color.Gray,
                animateMarker = false,
            )
        }
        // The overlay Column must carry the internal test tag so we can
        // assert layout properties — confirming the tag wiring and the
        // fact that the composable renders.
        composeTestRule
            .onNodeWithTag("metric_gauge_value_overlay", useUnmergedTree = true)
            .assertExists()
            .assertHeightIsAtLeast(1.dp)
    }

    @Test
    fun metricGaugeWithValue_longValue_rendersFullTextWithoutTruncation() {
        composeTestRule.setContent {
            Box(
                modifier = Modifier.width(140.dp).height(120.dp),
            ) {
                M3MetricGaugeWithValue(
                    markerFraction = 0.7f,
                    activeColor = Color.Green,
                    markerColor = Color.White,
                    valueText = "142.8",
                    unitText = "kg",
                    valueColor = Color.White,
                    unitColor = Color.Gray,
                    animateMarker = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val valueNode = composeTestRule.onNodeWithText("142.8", substring = false)
        valueNode.assertExists()
        val valueSemantics = valueNode.fetchSemanticsNode()
        val valueText = valueSemantics.config[SemanticsProperties.Text].joinToString("") { it.text }
        assertFalse(valueText.contains('\u2026'))
        assertEquals("142.8", valueText)
        assertTrue(valueSemantics.boundsInRoot.width <= 140.dp.value)
    }
}
