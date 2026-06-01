package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.ui.common.DateFormatUtils
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import com.patrykandpatrick.vico.compose.common.component.TextComponent
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object ChartDefaults {
    @Composable
    fun labelTextComponent(): TextComponent =
        rememberTextComponent(
            style = TextStyle(color = MaterialTheme.colorScheme.onSurface),
        )

    @Composable
    fun axisLabelTextComponent(): TextComponent =
        rememberTextComponent(
            style = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )

    @Composable
    fun guidelineComponent(): LineComponent =
        rememberLineComponent(
            fill = Fill(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
            thickness = 1.dp,
        )

    @Composable
    fun rememberDayOffsetFormatter(rangeStartMs: Long): CartesianValueFormatter =
        remember(rangeStartMs) {
            val formatter =
                DateTimeFormatter.ofPattern(
                    DateFormatUtils.DATE_FORMAT_SHORT,
                    Locale.getDefault(),
                )

            CartesianValueFormatter { _, value, _ ->
                Instant
                    .ofEpochMilli(rangeStartMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .plusDays(value.toLong())
                    .format(formatter)
            }
        }

    @Composable
    fun rememberChartState(
        rangeDays: Int,
        key: Any,
    ): Pair<VicoScrollState, VicoZoomState> =
        key(key) {
            val scrollState = rememberVicoScrollState(scrollEnabled = rangeDays > 7)
            val zoomState =
                rememberVicoZoomState(
                    zoomEnabled = rangeDays > 7,
                    initialZoom = Zoom.Content,
                    // minZoom = Zoom.fixed(0.01f): a tiny floor always below Zoom.Content.
                    // • Zoom.Content as minZoom blocks pinch-in (circular constraint).
                    // • Zoom.fixed(1f) is Vico's 1× default density, which exceeds Zoom.Content
                    //   for 30d (~0.86×) and 180d (~0.14×), clamping the initial view.
                    // • 0.01f is safely below Content zoom for any realistic screen/range.
                    minZoom = Zoom.fixed(0.01f),
                    maxZoom =
                        remember(rangeDays) {
                            when (rangeDays) {
                                30 -> Zoom.fixed(6f)
                                180 -> Zoom.fixed(25f)
                                else -> Zoom.fixed(2f)
                            }
                        },
                )
            scrollState to zoomState
        }

    fun itemPlacerForRangeDays(rangeDays: Int): HorizontalAxis.ItemPlacer {
        val basePlacer =
            HorizontalAxis.ItemPlacer.aligned(
                spacing = { 1 },
                addExtremeLabelPadding = true,
            )

        return object : HorizontalAxis.ItemPlacer by basePlacer {
            private fun calculateValues(visibleXRange: ClosedFloatingPointRange<Double>): List<Double> {
                val visibleDays = visibleXRange.endInclusive - visibleXRange.start

                // If mostly/fully zoomed out, use perfectly spaced 6-label lists to avoid strange jumps
                if (visibleDays > rangeDays - 2.0) {
                    val zoomedOutList =
                        when (rangeDays) {
                            30 -> listOf(0.0, 6.0, 12.0, 18.0, 24.0, 29.0)
                            180 -> listOf(0.0, 36.0, 72.0, 108.0, 144.0, 179.0)
                            else -> null
                        }
                    if (zoomedOutList != null) {
                        val buffer = 0.01
                        return zoomedOutList.filter {
                            it in (visibleXRange.start - buffer)..(visibleXRange.endInclusive + buffer)
                        }
                    }
                }

                val spacing =
                    when {
                        visibleDays <= 1.1 -> 1
                        visibleDays <= 3.5 -> 2
                        visibleDays <= 8.5 -> 2
                        visibleDays <= 15.5 -> 2
                        visibleDays <= 35.0 -> 5
                        visibleDays <= 70.0 -> 10
                        visibleDays <= 120.0 -> 15
                        else -> 35
                    }

                val maxVal = (rangeDays - 1).toDouble()
                val values = mutableListOf<Double>()
                var current = 0.0
                while (current <= maxVal) {
                    values.add(current)
                    current += spacing.toDouble()
                }

                val buffer = 0.01
                val visibleValues =
                    values
                        .filter {
                            it in (visibleXRange.start - buffer)..(visibleXRange.endInclusive + buffer)
                        }.toMutableList()

                val firstDay = 0.0
                if (firstDay in visibleXRange && !visibleValues.contains(firstDay)) {
                    visibleValues.add(0, firstDay)
                }

                if (maxVal in visibleXRange && !visibleValues.contains(maxVal)) {
                    val minSeparation =
                        when {
                            visibleDays <= 1.1 -> 0.1
                            visibleDays <= 3.5 -> 0.1
                            visibleDays <= 8.5 -> if (visibleDays <= 5.5) 0.5 else 1.1
                            visibleDays <= 15.5 -> 1.1
                            visibleDays <= 35.0 -> 4.0
                            visibleDays <= 70.0 -> 8.0
                            visibleDays <= 120.0 -> 12.0
                            else -> 24.0
                        }
                    val lastValue = visibleValues.lastOrNull() ?: 0.0
                    if (maxVal - lastValue < minSeparation) {
                        visibleValues.removeAt(visibleValues.size - 1)
                    }
                    visibleValues.add(maxVal)
                }

                return visibleValues.sorted()
            }

            override fun getLabelValues(
                context: CartesianDrawingContext,
                visibleXRange: ClosedFloatingPointRange<Double>,
                fullXRange: ClosedFloatingPointRange<Double>,
                maxLabelWidth: Float,
            ): List<Double> = calculateValues(visibleXRange)

            override fun getLineValues(
                context: CartesianDrawingContext,
                visibleXRange: ClosedFloatingPointRange<Double>,
                fullXRange: ClosedFloatingPointRange<Double>,
                maxLabelWidth: Float,
            ): List<Double> = calculateValues(visibleXRange)
        }
    }
}
