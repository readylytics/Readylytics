package app.readylytics.health.core.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    private var selected by mutableStateOf<Offset?>(null)
    private var containerTooltipVisible by mutableStateOf(false)
    private lateinit var scrollState: ScrollState

    /** A chart-sized Canvas stand-in inside a vertically scrolling page, like the app screens. */
    private fun setCanvasChart() {
        composeRule.setContent {
            scrollState = rememberScrollState()
            val hapticFeedback = LocalHapticFeedback.current
            Column(Modifier.size(200.dp).verticalScroll(scrollState)) {
                Box(
                    Modifier.size(200.dp).testTag(CHART_TAG).pointerInput(Unit) {
                        detectLongPressSelection(
                            hapticFeedback = hapticFeedback,
                            isVisible = { selected != null },
                            onDismiss = { selected = null },
                        ) { touch ->
                            selected = touch
                            true
                        }
                    },
                )
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

        assertNull(selected)
    }

    @Test
    fun `long press opens a tooltip that stays after release`() {
        setCanvasChart()

        composeRule.onNodeWithTag(CHART_TAG).performTouchInput { longClick(center) }

        assertNotNull(selected)
    }

    @Test
    fun `short tap dismisses an open tooltip`() {
        setCanvasChart()
        composeRule.onNodeWithTag(CHART_TAG).performTouchInput { longClick(center) }

        composeRule.onNodeWithTag(CHART_TAG).performClick()

        assertNull(selected)
    }

    @Test
    fun `scroll gesture starting on the chart scrolls the page without opening a tooltip`() {
        setCanvasChart()

        composeRule.onNodeWithTag(CHART_TAG).performTouchInput { swipeUp() }

        assertNull(selected)
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

        assertEquals(end, selected)
        assertEquals(0, scrollState.value)
    }

    @Test
    fun `scrub lock consumes moves only while the Vico controller is scrubbing`() {
        val controller = LongPressMarkerController(onLongPress = {}, onTap = { false })
        composeRule.setContent {
            scrollState = rememberScrollState()
            Column(Modifier.size(200.dp).verticalScroll(scrollState)) {
                Box(Modifier.size(200.dp).testTag(CHART_TAG).consumeGesturesWhileScrubbing(controller))
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

    @Test
    fun `container tap dismiss ignores the release that ends a child long press`() {
        composeRule.setContent {
            val hapticFeedback = LocalHapticFeedback.current
            Box(
                Modifier.size(200.dp).pointerInput(Unit) {
                    detectTapToDismiss(
                        isVisible = { containerTooltipVisible },
                        onDismiss = { containerTooltipVisible = false },
                    )
                },
            ) {
                // Like a split chart child: it reports the selection upward and owns no tooltip.
                Box(
                    Modifier.size(200.dp).testTag(CHART_TAG).pointerInput(Unit) {
                        detectLongPressSelection(
                            hapticFeedback = hapticFeedback,
                            isVisible = { false },
                            onDismiss = {},
                        ) {
                            containerTooltipVisible = true
                            true
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag(CHART_TAG).performTouchInput { longClick(center) }
        assertTrue(containerTooltipVisible)

        composeRule.onNodeWithTag(CHART_TAG).performClick()
        assertFalse(containerTooltipVisible)
    }
}
