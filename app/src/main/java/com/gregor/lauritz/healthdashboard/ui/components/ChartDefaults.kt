package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.ui.common.DateFormatUtils
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

    fun itemPlacerForRangeDays(rangeDays: Int): HorizontalAxis.ItemPlacer =
        when (rangeDays) {
            7 ->
                HorizontalAxis.ItemPlacer.aligned(
                    spacing = { _ -> 1 },
                    addExtremeLabelPadding = true,
                )
            30 ->
                HorizontalAxis.ItemPlacer.aligned(
                    spacing = { _ -> 5 },
                    addExtremeLabelPadding = true,
                )
            180 ->
                HorizontalAxis.ItemPlacer.aligned(
                    spacing = { _ -> 30 },
                    addExtremeLabelPadding = true,
                )
            else ->
                HorizontalAxis.ItemPlacer.aligned(
                    spacing = { _ -> 5 },
                    addExtremeLabelPadding = true,
                )
        }
}
