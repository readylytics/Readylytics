package app.readylytics.health.core.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.Interaction
import com.patrykandpatrick.vico.compose.common.Point
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CHART_TAG = "long_press_chart"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LongPressChartGesturesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val selected = mutableStateOf<Offset?>(null)
    private var clicks = 0
    private var panned = false
    private lateinit var scrollState: ScrollState

    /**
     * A chart-sized Canvas stand-in inside a vertically scrolling page, like the app screens.
     * [stackedTransform] puts a pan/zoom detector in front of the long-press detector, the way the
     * real Canvas charts do; [clickableParent] wraps the chart in a clickable container.
     */
    private fun setCanvasChart(
        stackedTransform: Boolean = false,
        clickableParent: Boolean = false,
    ) {
        composeRule.setContent {
            scrollState = rememberScrollState()
            val hapticFeedback = LocalHapticFeedback.current
            var chart: Modifier = Modifier.size(200.dp)
            if (stackedTransform) {
                chart = chart.pointerInput(Unit) { detectTransformGestures { _, _, _, _ -> panned = true } }
            }
            chart = chart.pointerInput(Unit) { detectLongPressSelection(hapticFeedback, selected) { it } }
            Column(Modifier.size(200.dp).verticalScroll(scrollState)) {
                if (clickableParent) {
                    // clickable merges its descendants' semantics and never merges their test tags, so the
                    // tag has to sit on the clickable node itself; touches still land on the inner chart.
                    Box(Modifier.size(200.dp).testTag(CHART_TAG).clickable { clicks++ }) { Box(chart) }
                } else {
                    Box(chart.testTag(CHART_TAG))
                }
                Spacer(Modifier.height(600.dp))
            }
        }
    }

    private fun TouchInjectionScope.holdPastLongPressTimeout() {
        moveBy(Offset.Zero, delayMillis = viewConfiguration.longPressTimeoutMillis + 100)
    }

    @Test
    fun `short tap does not open a tooltip`() {
        setCanvasChart()

        composeRule.onNodeWithTag(CHART_TAG).performClick()

        assertNull(selected.value)
    }

    @Test
    fun `long press opens a tooltip that stays after release`() {
        setCanvasChart()

        composeRule.onNodeWithTag(CHART_TAG).performTouchInput { longClick(center) }

        assertNotNull(selected.value)
    }

    @Test
    fun `scroll gesture starting on the chart scrolls the page without opening a tooltip`() {
        setCanvasChart()

        composeRule.onNodeWithTag(CHART_TAG).performTouchInput { swipeUp() }

        assertNull(selected.value)
        assertTrue(scrollState.value > 0)
    }

    @Test
    fun `scrub after long press follows the finger and does not scroll the page`() {
        setCanvasChart()
        var end = Offset.Zero

        composeRule.onNodeWithTag(CHART_TAG).performTouchInput {
            end = Offset(width * 0.75f, height * 0.25f)
            down(Offset(width * 0.25f, height * 0.75f))
            holdPastLongPressTimeout()
            moveTo(end)
            up()
        }

        assertEquals(end, selected.value)
        assertEquals(0, scrollState.value)
    }

    @Test
    fun `pausing mid-scrub does not let the page pick up the gesture`() {
        setCanvasChart()
        var end = Offset.Zero

        composeRule.onNodeWithTag(CHART_TAG).performTouchInput {
            end = Offset(width * 0.75f, height * 0.1f)
            down(Offset(width * 0.25f, height * 0.9f))
            holdPastLongPressTimeout()
            // Far past touch slop along the page's scroll axis, then the finger rests while the touch
            // controller keeps reporting moves without a position change.
            moveTo(Offset(width * 0.25f, height * 0.1f))
            repeat(2) { moveBy(Offset.Zero, delayMillis = 50) }
            moveTo(end)
            up()
        }

        assertEquals(end, selected.value)
        assertEquals(0, scrollState.value)
    }

    @Test
    fun `scrub keeps a stacked transform detector from panning the chart`() {
        setCanvasChart(stackedTransform = true)
        var end = Offset.Zero

        composeRule.onNodeWithTag(CHART_TAG).performTouchInput {
            end = Offset(width * 0.75f, height * 0.25f)
            down(Offset(width * 0.25f, height * 0.75f))
            holdPastLongPressTimeout()
            moveTo(end)
            up()
        }

        assertEquals(end, selected.value)
        assertFalse(panned)
    }

    @Test
    fun `tap on the chart still reaches a clickable parent`() {
        setCanvasChart(clickableParent = true)

        composeRule.onNodeWithTag(CHART_TAG).performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `scrub does not click a clickable parent on release`() {
        setCanvasChart(clickableParent = true)

        composeRule.onNodeWithTag(CHART_TAG).performTouchInput { longClick(center) }

        assertNotNull(selected.value)
        assertEquals(0, clicks)
    }

    @Test
    fun `scrub gate consumes moves only while the Vico controller is scrubbing`() {
        val controller = LongPressMarkerController(onLongPress = {})
        composeRule.setContent {
            scrollState = rememberScrollState()
            Column(Modifier.size(200.dp).verticalScroll(scrollState)) {
                Box(Modifier.size(200.dp).testTag(CHART_TAG).then(controller.scrubGate))
                Spacer(Modifier.height(600.dp))
            }
        }
        val target =
            object : CartesianMarker.Target {
                override val x: Double = 0.0
                override val canvasX: Float = 0f
            }
        controller.shouldShowMarker(Interaction.LongPress(Point(0f, 0f)), listOf(target))

        composeRule.onNodeWithTag(CHART_TAG).performTouchInput { swipeUp() }
        assertEquals(0, scrollState.value)

        controller.shouldShowMarker(Interaction.Release(Point(0f, 0f)), listOf(target))
        composeRule.onNodeWithTag(CHART_TAG).performTouchInput { swipeUp() }
        assertTrue(scrollState.value > 0)
    }
}
