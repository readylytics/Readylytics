package app.readylytics.health.feature.workouts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import app.readylytics.health.core.model.domain.display.MetricFormatter
import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.core.ui.components.DataPointTooltipData

/**
 * Holds all data needed to render the interactive selection overlay and tooltip
 * for the ACWR (Training Load) chart.
 *
 * @param dayOffset      Index into the chart's x-axis (day number from range start).
 * @param trimpValue     Daily TRIMP value for the selected day; null if no data.
 * @param strainRatioValue Strain Ratio (ACWR) for the selected day; null if no data.
 * @param canvasX        Canvas x-coordinate of the selected column / dot.
 * @param barCanvasYTop  Canvas y-coordinate of the top of the selected TRIMP bar;
 *                       null if the column layer was not hit by the touch event.
 * @param lineCanvasY    Canvas y-coordinate of the Strain Ratio dot;
 *                       null if the line layer was not hit by the touch event.
 */
data class AcwrSelectedState(
    val dayOffset: Int,
    val trimpValue: Float?,
    val strainRatioValue: Float?,
    val canvasX: Float,
    val barCanvasYTop: Float?,
    val lineCanvasY: Float?,
)

internal fun AcwrSelectedState.withComputedLineCanvasY(
    ratioAxisMax: Double,
    layerBounds: Rect?,
): AcwrSelectedState {
    val needsPlacement = lineCanvasY == null && strainRatioValue != null && ratioAxisMax > 0.0
    return if (needsPlacement && layerBounds != null) {
        val fraction = (1.0 - (strainRatioValue / ratioAxisMax)).coerceIn(0.0, 1.0)
        copy(lineCanvasY = (layerBounds.top + fraction * layerBounds.height).toFloat())
    } else {
        this
    }
}

@Composable
internal fun rememberAcwrTooltipData(
    selectedState: AcwrSelectedState?,
    rangeStartMs: Long,
    granularity: TrendGranularity,
    periodLabels: List<String>,
): DataPointTooltipData? {
    val trimpFormat = stringResource(R.string.acwr_tooltip_trimp_format)
    val strainFormat = stringResource(R.string.acwr_tooltip_strain_format)
    val avgTrimpFormat = stringResource(R.string.acwr_tooltip_avg_trimp_format)
    val avgStrainFormat = stringResource(R.string.acwr_tooltip_avg_strain_format)

    return remember(
        selectedState,
        rangeStartMs,
        trimpFormat,
        strainFormat,
        avgTrimpFormat,
        avgStrainFormat,
        granularity,
        periodLabels,
    ) {
        selectedState?.let { s ->
            val anchorY = s.lineCanvasY ?: s.barCanvasYTop ?: 0f
            val trimpText = s.trimpValue?.let { MetricFormatter.roundTrimp(it).toString() } ?: "—"
            val strainText = MetricFormatter.formatStrain(s.strainRatioValue)

            if (granularity != TrendGranularity.DAILY) {
                val periodLabel = periodLabels.getOrElse(s.dayOffset) { "" }
                DataPointTooltipData(
                    valueText = periodLabel,
                    dateText = avgTrimpFormat.format(trimpText),
                    extraLine = avgStrainFormat.format(strainText),
                    offset = IntOffset(s.canvasX.toInt(), anchorY.toInt()),
                )
            } else {
                val date = ChartUtils.dayOffsetToLocalDate(s.dayOffset, rangeStartMs)
                DataPointTooltipData(
                    valueText = trimpFormat.format(trimpText),
                    dateText = strainFormat.format(strainText),
                    extraLine = ChartUtils.formatTooltipDate(date),
                    offset = IntOffset(s.canvasX.toInt(), anchorY.toInt()),
                )
            }
        }
    }
}
