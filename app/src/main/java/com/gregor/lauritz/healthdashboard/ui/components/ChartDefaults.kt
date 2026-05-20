package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.ui.common.DateFormatUtils
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
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

    fun itemPlacerForRangeDays(
        rangeDays: Int,
        zoomState: VicoZoomState? = null,
    ): HorizontalAxis.ItemPlacer {
        val basePlacer =
            HorizontalAxis.ItemPlacer.aligned(
                spacing = {
                    val zoomFactor = zoomState?.value ?: 1f
                    val visibleDays = rangeDays / zoomFactor
                    when {
                        visibleDays <= 8 -> 1
                        visibleDays <= 15 -> 2
                        visibleDays <= 35 -> 5
                        visibleDays <= 70 -> 10
                        visibleDays <= 120 -> 15
                        else -> 30
                    }
                },
                addExtremeLabelPadding = true,
            )

        return object : HorizontalAxis.ItemPlacer by basePlacer {
            override fun getLabelValues(
                context: CartesianDrawingContext,
                visibleXRange: ClosedFloatingPointRange<Double>,
                fullXRange: ClosedFloatingPointRange<Double>,
                maxLabelWidth: Float,
            ): List<Double> {
                val zoomFactor = zoomState?.value ?: 1f
                val visibleDays = rangeDays / zoomFactor

                // Special case for 30d range fully/mostly zoomed out:
                // "for 30d we only show the first day. also always show the last day so the current selected date"
                if (rangeDays == 30 && visibleDays > 15) {
                    return listOf(0.0, 29.0)
                }

                // Default behavior: get the base placer's calculated values
                val baseValues =
                    basePlacer
                        .getLabelValues(
                            context,
                            visibleXRange,
                            fullXRange,
                            maxLabelWidth,
                        ).toMutableList()

                // Always ensure the first day is included if visible
                val firstDay = 0.0
                if (firstDay in visibleXRange && !baseValues.contains(firstDay)) {
                    baseValues.add(0, firstDay)
                }

                // Always ensure the last day is included if visible
                val lastDay = (rangeDays - 1).toDouble()
                if (lastDay in visibleXRange && !baseValues.contains(lastDay)) {
                    // Filter out any base values that are extremely close to the last day to prevent visual overlaps
                    val minSeparation =
                        when {
                            visibleDays <= 8 -> 0.8
                            visibleDays <= 15 -> 1.6
                            visibleDays <= 35 -> 4.0
                            visibleDays <= 70 -> 8.0
                            visibleDays <= 120 -> 12.0
                            else -> 24.0
                        }
                    baseValues.removeAll { it >= lastDay - minSeparation && it < lastDay }
                    baseValues.add(lastDay)
                }

                return baseValues.sorted()
            }
        }
    }
}
