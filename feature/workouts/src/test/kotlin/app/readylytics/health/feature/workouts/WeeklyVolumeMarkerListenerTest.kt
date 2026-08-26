package app.readylytics.health.feature.workouts

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the series-index contract the tooltip depends on: series 0 is the current week and series 1
 * is the previous week. Swapping the two would silently invert every tooltip's values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeeklyVolumeMarkerListenerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `series order helper keeps current week ahead of previous week`() {
        val ordered = weeklyVolumeSeriesOrder(current = "current", previous = "previous")

        assertEquals(listOf("current", "previous"), ordered)
        assertEquals("current", ordered[CURRENT_WEEK_SERIES_INDEX])
        assertEquals("previous", ordered[PREVIOUS_WEEK_SERIES_INDEX])
    }

    @Test
    fun `selected state resolves each series by its series index, not point order`() {
        var selectedState: WeeklyVolumeSelectedState? = null
        lateinit var listener: CartesianMarkerVisibilityListener

        composeRule.setContent {
            listener = rememberWeeklyVolumeMarkerVisibilityListener(onStateChanged = { selectedState = it })
        }

        val entries = entriesAt(x = 3, currentY = 180, previousY = 120)
        composeRule.runOnIdle {
            listener.onShown(
                marker = TestMarker,
                targets =
                    listOf(
                        TestLineTarget(
                            x = 3.0,
                            canvasX = 210f,
                            // Deliberately reversed so a positional lookup would fail.
                            points =
                                listOf(
                                    point(entries[PREVIOUS_WEEK_SERIES_INDEX], canvasY = 150f),
                                    point(entries[CURRENT_WEEK_SERIES_INDEX], canvasY = 60f),
                                ),
                        ),
                    ),
            )
        }

        val resolved = requireNotNull(selectedState)
        assertEquals(3, resolved.dayOffset)
        assertEquals(180, resolved.currentMinutes)
        assertEquals(120, resolved.previousMinutes)
        assertEquals(210f, resolved.canvasX)
        assertEquals(60f, resolved.canvasY)
    }

    @Test
    fun `day after today has no current week point and falls back to the previous week anchor`() {
        var selectedState: WeeklyVolumeSelectedState? = null
        lateinit var listener: CartesianMarkerVisibilityListener

        composeRule.setContent {
            listener = rememberWeeklyVolumeMarkerVisibilityListener(onStateChanged = { selectedState = it })
        }

        val entries = entriesAt(x = 6, currentY = 0, previousY = 300)
        composeRule.runOnIdle {
            listener.onUpdated(
                marker = TestMarker,
                targets =
                    listOf(
                        TestLineTarget(
                            x = 6.0,
                            canvasX = 400f,
                            points = listOf(point(entries[PREVIOUS_WEEK_SERIES_INDEX], canvasY = 40f)),
                        ),
                    ),
            )
        }

        val resolved = requireNotNull(selectedState)
        assertEquals(6, resolved.dayOffset)
        assertNull(resolved.currentMinutes)
        assertEquals(300, resolved.previousMinutes)
        assertEquals(40f, resolved.canvasY)
    }

    @Test
    fun `missing previous week point leaves the selection untouched`() {
        var selectedState: WeeklyVolumeSelectedState? = null
        lateinit var listener: CartesianMarkerVisibilityListener

        composeRule.setContent {
            listener = rememberWeeklyVolumeMarkerVisibilityListener(onStateChanged = { selectedState = it })
        }

        val entries = entriesAt(x = 1, currentY = 60, previousY = 45)
        composeRule.runOnIdle {
            listener.onShown(
                marker = TestMarker,
                targets =
                    listOf(
                        TestLineTarget(
                            x = 1.0,
                            canvasX = 90f,
                            points = listOf(point(entries[CURRENT_WEEK_SERIES_INDEX], canvasY = 70f)),
                        ),
                    ),
            )
        }

        assertNull(selectedState)
    }

    /**
     * Builds the two entries for one x value through [LineCartesianLayerModel], which is the only
     * way to obtain entries carrying a non-zero `seriesIndex` — the four-argument [
     * LineCartesianLayerModel.Entry] constructor is internal to Vico.
     */
    private fun entriesAt(
        x: Int,
        currentY: Int,
        previousY: Int,
    ): List<LineCartesianLayerModel.Entry> {
        val model =
            LineCartesianLayerModel(
                weeklyVolumeSeriesOrder(
                    current = listOf(LineCartesianLayerModel.Entry(x, currentY)),
                    previous = listOf(LineCartesianLayerModel.Entry(x, previousY)),
                ),
            )
        return model.series.map { it.single() }
    }

    private fun point(
        entry: LineCartesianLayerModel.Entry,
        canvasY: Float,
    ) = LineCartesianLayerMarkerTarget.Point(entry = entry, canvasY = canvasY, color = Color.Black)

    private data class TestLineTarget(
        override val x: Double,
        override val canvasX: Float,
        override val points: List<LineCartesianLayerMarkerTarget.Point>,
    ) : LineCartesianLayerMarkerTarget

    private data object TestMarker : CartesianMarker
}
