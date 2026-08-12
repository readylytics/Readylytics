package app.readylytics.health.feature.sleep

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.domain.scoring.sleep.SleepTrendDay
import app.readylytics.health.domain.scoring.sleep.SleepTrendNap
import com.patrykandpatrick.vico.compose.cartesian.data.ColumnCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.ColumnCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SleepTrendMarkerListenerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `selected marker state carries naps for its scoring day`() {
        val naps =
            listOf(
                SleepTrendNap(startTimeMs = 100L, endTimeMs = 2_200_100L, durationMinutes = 35),
                SleepTrendNap(startTimeMs = 300L, endTimeMs = 1_800_300L, durationMinutes = 30),
            )
        var selectedState: SleepTrendSelectedState? = null
        lateinit var listener: com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener

        composeRule.setContent {
            listener =
                rememberSleepTrendMarkerVisibilityListener(
                    startOffsetPoints = listOf(DailyDataPoint(1, 11f)),
                    durationSpanPoints = listOf(DailyDataPoint(1, 8f)),
                    actualDurationPoints = listOf(DailyDataPoint(1, 9f)),
                    trendDays =
                        listOf(
                            SleepTrendDay(
                                dayOffset = 0,
                                scoreDay = LocalDate.of(2026, 8, 1),
                                coreStartTimeMs = null,
                                coreEndTimeMs = null,
                                totalDurationMinutes = null,
                                naps = emptyList(),
                            ),
                            SleepTrendDay(
                                dayOffset = 1,
                                scoreDay = LocalDate.of(2026, 8, 2),
                                coreStartTimeMs = 10L,
                                coreEndTimeMs = 20L,
                                totalDurationMinutes = 540,
                                naps = naps,
                            ),
                        ),
                    granularity = TrendGranularity.DAILY,
                    onStateChanged = { selectedState = it },
                )
        }

        composeRule.runOnIdle {
            listener.onShown(
                marker = TestMarker,
                targets =
                    listOf(
                        TestColumnTarget(
                            x = 1.0,
                            canvasX = 80f,
                            columns =
                                listOf(
                                    column(canvasY = 170f),
                                    column(canvasY = 70f),
                                ),
                        ),
                        TestLineTarget(
                            x = 1.0,
                            canvasX = 80f,
                            points = listOf(linePoint(canvasY = 45f)),
                        ),
                    ),
            )
        }

        val resolvedState = requireNotNull(selectedState)
        assertEquals(1, resolvedState.dayOffset)
        assertEquals(80f, resolvedState.canvasX)
        assertEquals(170f, resolvedState.barCanvasYBottom)
        assertEquals(70f, resolvedState.barCanvasYTop)
        assertEquals(45f, resolvedState.lineCanvasY)
        assertEquals(10L, resolvedState.coreStartTimeMs)
        assertEquals(20L, resolvedState.coreEndTimeMs)
        assertEquals(naps, resolvedState.naps)
    }

    @Test
    fun `selected marker resolves trend day by offset key when list is sparse`() {
        var selectedState: SleepTrendSelectedState? = null
        lateinit var listener: com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener

        composeRule.setContent {
            listener =
                rememberSleepTrendMarkerVisibilityListener(
                    startOffsetPoints = listOf(DailyDataPoint(5, 11f)),
                    durationSpanPoints = listOf(DailyDataPoint(5, 8f)),
                    actualDurationPoints = listOf(DailyDataPoint(5, 9f)),
                    trendDays =
                        listOf(
                            SleepTrendDay(
                                dayOffset = 5,
                                scoreDay = LocalDate.of(2026, 8, 6),
                                coreStartTimeMs = 42L,
                                coreEndTimeMs = 52L,
                                totalDurationMinutes = 600,
                                naps = emptyList(),
                            ),
                        ),
                    granularity = TrendGranularity.DAILY,
                    onStateChanged = { selectedState = it },
                )
        }

        composeRule.runOnIdle {
            listener.onShown(
                marker = TestMarker,
                targets =
                    listOf(
                        TestColumnTarget(
                            x = 5.0,
                            canvasX = 300f,
                            columns = listOf(column(canvasY = 170f), column(canvasY = 70f)),
                        ),
                    ),
            )
        }

        val resolvedState = requireNotNull(selectedState)
        assertEquals(5, resolvedState.dayOffset)
        assertEquals(42L, resolvedState.coreStartTimeMs)
        assertEquals(52L, resolvedState.coreEndTimeMs)
    }

    private fun column(canvasY: Float) =
        ColumnCartesianLayerMarkerTarget.Column(
            entry = ColumnCartesianLayerModel.Entry(1, 1),
            canvasY = canvasY,
            color = Color.Black,
        )

    private fun linePoint(canvasY: Float) =
        LineCartesianLayerMarkerTarget.Point(
            entry = LineCartesianLayerModel.Entry(1, 9),
            canvasY = canvasY,
            color = Color.Black,
        )

    private data class TestColumnTarget(
        override val x: Double,
        override val canvasX: Float,
        override val columns: List<ColumnCartesianLayerMarkerTarget.Column>,
    ) : ColumnCartesianLayerMarkerTarget

    private data class TestLineTarget(
        override val x: Double,
        override val canvasX: Float,
        override val points: List<LineCartesianLayerMarkerTarget.Point>,
    ) : LineCartesianLayerMarkerTarget

    private data object TestMarker : CartesianMarker
}
