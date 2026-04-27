package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.gregor.lauritz.healthdashboard.ui.common.DateFormatUtils
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.common.component.LineComponent
import com.patrykandpatrick.vico.core.common.component.TextComponent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object ChartDefaults {

    @Composable
    fun labelTextComponent(): TextComponent =
        rememberTextComponent(color = MaterialTheme.colorScheme.onSurface)

    @Composable
    fun axisLabelTextComponent(): TextComponent =
        rememberTextComponent(color = MaterialTheme.colorScheme.onSurfaceVariant)

    @Composable
    fun guidelineComponent(): LineComponent {
        val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        return LineComponent(fill = fill(color), thicknessDp = 1f)
    }

    @Composable
    fun rememberDayOffsetFormatter(rangeStartMs: Long): CartesianValueFormatter =
        remember(rangeStartMs) {
            val fmt = java.time.format.DateTimeFormatter.ofPattern(DateFormatUtils.DATE_FORMAT_SHORT, Locale.getDefault())
            CartesianValueFormatter { _, value, _ ->
                val date = java.time.Instant.ofEpochMilli(rangeStartMs)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
                    .plusDays(value.toLong())
                date.format(fmt)
            }
        }

    fun itemPlacerForRangeDays(rangeDays: Int): HorizontalAxis.ItemPlacer =
        if (rangeDays == 7) {
            HorizontalAxis.ItemPlacer.aligned(spacing = { 2 }, addExtremeLabelPadding = true)
        } else {
            HorizontalAxis.ItemPlacer.aligned(spacing = { 5 }, addExtremeLabelPadding = true)
        }
}
