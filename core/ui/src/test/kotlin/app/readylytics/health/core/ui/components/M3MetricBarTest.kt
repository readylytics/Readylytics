package app.readylytics.health.core.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class M3MetricBarTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun visibleTickFractions_hidesTicksAtOrBeforeProgress() {
        assertEquals(listOf(0.2f, 0.4f, 0.6f, 0.8f), visibleTickFractions(0f))
        assertEquals(listOf(0.4f, 0.6f, 0.8f), visibleTickFractions(0.2f))
        assertEquals(listOf(0.6f, 0.8f), visibleTickFractions(0.5f))
        assertEquals(emptyList(), visibleTickFractions(0.8f))
        assertEquals(emptyList(), visibleTickFractions(1f))
    }

    @Test
    fun fillEndCenterX_clampsSoTheFillNeverOvershootsTheTrack() {
        val width = 200f
        val strokeWidth = 10f
        // At 100% the fill cap must end flush with the track's right end, not stick out past it.
        assertEquals(width - strokeWidth / 2f, fillEndCenterX(1f, width, strokeWidth))
        assertEquals(width - strokeWidth / 2f, fillEndCenterX(1.5f, width, strokeWidth))
        // Intermediate progress stays proportional so the cap bulges into the track.
        assertEquals(100f, fillEndCenterX(0.5f, width, strokeWidth))
        // Tiny/zero progress stays left-anchored instead of going negative.
        assertEquals(strokeWidth / 2f, fillEndCenterX(0f, width, strokeWidth))
        assertEquals(strokeWidth / 2f, fillEndCenterX(0.001f, width, strokeWidth))
    }

    @Test
    fun metricBar_acceptsNull_andClampsOutOfRange_progressSurfacedThroughSemantics() {
        composeTestRule.setContent {
            M3MetricBar(
                progressFraction = null,
                activeColor = Color.Red,
                trackColor = Color.Gray,
                animateProgress = false,
            )
            M3MetricBar(
                progressFraction = 1.5f,
                activeColor = Color.Red,
                trackColor = Color.Gray,
                animateProgress = false,
            )
            M3MetricBar(
                progressFraction = -0.2f,
                activeColor = Color.Red,
                trackColor = Color.Gray,
                animateProgress = false,
            )
            M3MetricBar(
                progressFraction = 0.5f,
                activeColor = Color.Green,
                trackColor = Color.Gray,
                animateProgress = false,
            )
        }
        // M3's LinearProgressIndicator surfaces the clamped progress via ProgressBarRangeInfo.
        composeTestRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo(0f, 0f..1f))).assertCountEquals(2)
        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(1f, 0f..1f))).assertExists()
        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.5f, 0f..1f))).assertExists()
    }
}
