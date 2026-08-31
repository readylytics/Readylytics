package app.readylytics.health.core.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import app.readylytics.health.core.model.domain.model.BucketZoneBands
import app.readylytics.health.core.model.domain.model.ZoneBand
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.DeltaDirection
import app.readylytics.health.core.ui.common.PeriodAverageSummary
import app.readylytics.health.core.ui.common.TrendGranularity
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState

internal data class TrendChartConfig(
    val rangeStartMs: Long,
    val rangeDays: Int,
    val baselineUnit: String,
    val baselineDecimalPlaces: Int,
    val axisDecimalPlaces: Int,
    val tooltipDecimalPlaces: Int,
    val hideUnitInTooltip: Boolean,
    val granularity: TrendGranularity,
)

internal data class TrendChartBounds(
    val minY: Double,
    val maxY: Double,
    val baselineValue: Float,
    val shouldShowBaseline: Boolean,
    val resolvedBaselineLabel: String,
    val baselineUnavailableLabel: String?,
    val hasHistoricalBaseline: Boolean,
)

internal data class TrendChartUIState(
    val scrollState: VicoScrollState,
    val zoomState: VicoZoomState,
    val parentScrollInProgress: () -> Boolean,
    val periodSummary: PeriodAverageSummary?,
    val deltaDirection: DeltaDirection,
    val modifier: Modifier,
)

internal data class TrendChartTooltipState(
    val selectedPointOffset: Offset?,
    val tooltipState: DataPointTooltipData?,
    val onUpdateOffset: (Offset) -> Unit,
    val onUpdateTooltip: (DataPointTooltipData) -> Unit,
)

internal data class TrendChartVisuals(
    val zoneBands: List<ZoneBand>?,
    val bucketZoneBands: List<BucketZoneBands>?,
    val historicalBaseline: List<DailyDataPoint>?,
    val dotColor: Color,
    val baselineColor: Color,
)

internal class TrendChartContext(
    val renderData: TrendChartRenderData,
    val config: TrendChartConfig,
    val bounds: TrendChartBounds,
    val visuals: TrendChartVisuals,
    val parentScrollInProgress: () -> Boolean,
    val tooltip: TrendChartTooltipState,
)
