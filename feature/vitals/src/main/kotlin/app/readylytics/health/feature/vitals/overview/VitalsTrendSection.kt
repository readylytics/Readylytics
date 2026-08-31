package app.readylytics.health.feature.vitals.overview

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.preferences.UnitSystem
import app.readylytics.health.core.model.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.core.model.domain.vitals.VitalsChartId
import app.readylytics.health.core.ui.common.CardLoader
import app.readylytics.health.core.ui.common.DeltaDirection
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.components.ChartConfigurationsList
import app.readylytics.health.core.ui.components.ChartDataMap
import app.readylytics.health.core.ui.components.ReorderableChartList
import app.readylytics.health.core.ui.components.TrendCard
import app.readylytics.health.core.ui.components.TrendChart
import app.readylytics.health.feature.vitals.R
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import app.readylytics.health.core.ui.R as CoreUiR

/**
 * The four Vico trend charts (HRV, RHR, SpO2, body temperature) on the Vitals screen. Takes only
 * [VitalsChartInputs] (never the raw [VitalsUiState]) so gauge-only or refresh-only state changes
 * never recompose the chart subtree -- this is the guarantee F1/F5 exist to provide.
 */
@Composable
internal fun VitalsTrendSection(
    chartInputs: VitalsChartInputs,
    modifier: Modifier = Modifier,
    chartConfigurations: List<VitalsChartConfiguration> =
        VitalsChartId.entries.mapIndexed { index, chartId -> VitalsChartConfiguration(chartId, true, index) },
    isEditing: Boolean = false,
    onChartHide: (VitalsChartId) -> Unit = {},
    onChartReorder: (List<VitalsChartConfiguration>) -> Unit = {},
    chartScrollState: VicoScrollState,
    chartZoomState: VicoZoomState,
    parentScrollInProgress: () -> Boolean,
) {
    val chartDataMap =
        VitalsChartId.entries.associateWith { chartId ->
            chartBlockFor(
                chartId = chartId,
                chartInputs = chartInputs,
                chartScrollState = chartScrollState,
                chartZoomState = chartZoomState,
                parentScrollInProgress = parentScrollInProgress,
            )
        }
    ReorderableChartList(
        chartConfigurations = ChartConfigurationsList(chartConfigurations),
        chartDataMap = ChartDataMap(map = chartDataMap),
        isEditing = isEditing,
        onChartHide = onChartHide,
        onChartReorder = onChartReorder,
        modifier = modifier,
    )
}

// Note: graphicsLayer{} intentionally omitted for performance (F19)
private fun chartBlockFor(
    chartId: VitalsChartId,
    chartInputs: VitalsChartInputs,
    chartScrollState: VicoScrollState,
    chartZoomState: VicoZoomState,
    parentScrollInProgress: () -> Boolean,
): @Composable (VitalsChartConfiguration) -> Unit =
    when (chartId) {
        VitalsChartId.HRV_TREND ->
            { _ ->
                HrvTrendChartBlock(
                    chartInputs = chartInputs,
                    chartScrollState = chartScrollState,
                    chartZoomState = chartZoomState,
                    parentScrollInProgress = parentScrollInProgress,
                )
            }
        VitalsChartId.RHR_TREND ->
            { _ ->
                RhrTrendChartBlock(
                    chartInputs = chartInputs,
                    chartScrollState = chartScrollState,
                    chartZoomState = chartZoomState,
                    parentScrollInProgress = parentScrollInProgress,
                )
            }
        VitalsChartId.SPO2_TREND ->
            { _ ->
                Spo2TrendChartBlock(
                    chartInputs = chartInputs,
                    chartScrollState = chartScrollState,
                    chartZoomState = chartZoomState,
                    parentScrollInProgress = parentScrollInProgress,
                )
            }
        VitalsChartId.BODY_TEMP_TREND ->
            { _ ->
                BodyTempTrendChartBlock(
                    chartInputs = chartInputs,
                    chartScrollState = chartScrollState,
                    chartZoomState = chartZoomState,
                    parentScrollInProgress = parentScrollInProgress,
                )
            }
    }

@Composable
private fun HrvTrendChartBlock(
    chartInputs: VitalsChartInputs,
    chartScrollState: VicoScrollState,
    chartZoomState: VicoZoomState,
    parentScrollInProgress: () -> Boolean,
) {
    val chartSeries = chartInputs.chartSeries
    val presentation = chartInputs.presentation
    CardLoader(
        isLoading = chartInputs.isLoading,
        skeleton = { ChartSkeleton() },
        content = {
            TrendCard(
                title = stringResource(R.string.label_hrv_rmssd),
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
            ) {
                TrendChart(
                    points = chartSeries.hrv,
                    rangeStartMs = chartInputs.rangeStartMs,
                    rangeDays = chartInputs.selectedRange.days,
                    baselineUnit = stringResource(CoreUiR.string.unit_ms),
                    modifier = Modifier.testTag("HrvTrendChart"),
                    baseline =
                        presentation.hrv.baseline?.toFloat()
                            ?: chartSeries.historicalHrvBaselineAverage?.toFloat(),
                    showBaseline = presentation.hrv.baseline != null,
                    scrollState = chartScrollState,
                    zoomState = chartZoomState,
                    zoneBands =
                        if (chartSeries.historicalHrvBaseline.isEmpty()) {
                            presentation.hrv.zoneBands
                        } else {
                            chartSeries.historicalHrvZoneBands
                        },
                    historicalBaseline = chartSeries.historicalHrvBaseline.takeIf { it.isNotEmpty() },
                    bucketZoneBands = chartSeries.historicalHrvBucketZoneBands.takeIf { it.isNotEmpty() },
                    parentScrollInProgress = parentScrollInProgress,
                    granularity = chartInputs.selectedRange.granularity,
                    periodSummary = chartSeries.hrvPeriodSummary,
                    deltaDirection = DeltaDirection.HIGHER_IS_BETTER,
                )
            }
        },
    )
}

@Composable
private fun RhrTrendChartBlock(
    chartInputs: VitalsChartInputs,
    chartScrollState: VicoScrollState,
    chartZoomState: VicoZoomState,
    parentScrollInProgress: () -> Boolean,
) {
    val chartSeries = chartInputs.chartSeries
    val presentation = chartInputs.presentation
    CardLoader(
        isLoading = chartInputs.isLoading,
        skeleton = { ChartSkeleton() },
        content = {
            TrendCard(
                title = stringResource(R.string.label_resting_heart_rate),
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
            ) {
                TrendChart(
                    points = chartSeries.rhr,
                    rangeStartMs = chartInputs.rangeStartMs,
                    rangeDays = chartInputs.selectedRange.days,
                    baselineUnit = stringResource(CoreUiR.string.unit_bpm),
                    modifier = Modifier.testTag("RestingHeartRateTrendChart"),
                    baseline =
                        presentation.rhr.baseline?.toFloat()
                            ?: chartSeries.historicalRhrBaselineAverage?.toFloat(),
                    showBaseline = presentation.rhr.baseline != null,
                    scrollState = chartScrollState,
                    zoomState = chartZoomState,
                    zoneBands =
                        if (chartSeries.historicalRhrBaseline.isEmpty()) {
                            presentation.rhr.zoneBands
                        } else {
                            chartSeries.historicalRhrZoneBands
                        },
                    historicalBaseline = chartSeries.historicalRhrBaseline.takeIf { it.isNotEmpty() },
                    bucketZoneBands = chartSeries.historicalRhrBucketZoneBands.takeIf { it.isNotEmpty() },
                    parentScrollInProgress = parentScrollInProgress,
                    granularity = chartInputs.selectedRange.granularity,
                    periodSummary = chartSeries.rhrPeriodSummary,
                    deltaDirection = DeltaDirection.LOWER_IS_BETTER,
                )
            }
        },
    )
}

@Composable
private fun Spo2TrendChartBlock(
    chartInputs: VitalsChartInputs,
    chartScrollState: VicoScrollState,
    chartZoomState: VicoZoomState,
    parentScrollInProgress: () -> Boolean,
) {
    val chartSeries = chartInputs.chartSeries
    val presentation = chartInputs.presentation
    CardLoader(
        isLoading = chartInputs.isLoading,
        skeleton = { ChartSkeleton() },
        content = {
            TrendCard(
                title = stringResource(R.string.label_oxygen_saturation),
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
            ) {
                TrendChart(
                    points = chartSeries.spo2,
                    rangeStartMs = chartInputs.rangeStartMs,
                    rangeDays = chartInputs.selectedRange.days,
                    baselineUnit = stringResource(CoreUiR.string.unit_percent),
                    modifier = Modifier.testTag("OxygenSaturationTrendChart"),
                    baseline = 95f,
                    baselineLabel = stringResource(CoreUiR.string.label_normal_limit),
                    showBaseline = true,
                    scrollState = chartScrollState,
                    zoomState = chartZoomState,
                    zoneBands = presentation.spo2.zoneBands,
                    axisDecimalPlaces = 0,
                    baselineDecimalPlaces = 0,
                    minYOverride = 90.0,
                    maxYOverride = 100.0,
                    parentScrollInProgress = parentScrollInProgress,
                    granularity = chartInputs.selectedRange.granularity,
                    periodSummary = chartSeries.spo2PeriodSummary,
                    deltaDirection = DeltaDirection.HIGHER_IS_BETTER,
                )
            }
        },
    )
}

@Composable
private fun BodyTempTrendChartBlock(
    chartInputs: VitalsChartInputs,
    chartScrollState: VicoScrollState,
    chartZoomState: VicoZoomState,
    parentScrollInProgress: () -> Boolean,
) {
    val chartSeries = chartInputs.chartSeries
    val presentation = chartInputs.presentation
    CardLoader(
        isLoading = chartInputs.isLoading,
        skeleton = { ChartSkeleton() },
        content = {
            TrendCard(
                title = stringResource(CoreUiR.string.label_body_temperature),
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
            ) {
                TrendChart(
                    points = chartSeries.bodyTemp,
                    rangeStartMs = chartInputs.rangeStartMs,
                    rangeDays = chartInputs.selectedRange.days,
                    baselineUnit =
                        if (presentation.bodyTempUnitSystem == UnitSystem.IMPERIAL) {
                            stringResource(CoreUiR.string.unit_fahrenheit)
                        } else {
                            stringResource(CoreUiR.string.unit_celsius)
                        },
                    modifier = Modifier.testTag("BodyTemperatureTrendChart"),
                    baseline = presentation.bodyTemp.baseline,
                    showBaseline = presentation.bodyTemp.baseline != null,
                    baselineUnavailableLabel =
                        if (presentation.bodyTemp.baseline == null) {
                            stringResource(CoreUiR.string.body_temperature_calibrating)
                        } else {
                            null
                        },
                    baselineDecimalPlaces = 1,
                    axisDecimalPlaces = 1,
                    scrollState = chartScrollState,
                    zoomState = chartZoomState,
                    parentScrollInProgress = parentScrollInProgress,
                    granularity = chartInputs.selectedRange.granularity,
                    periodSummary = chartSeries.bodyTempPeriodSummary,
                    deltaDirection = DeltaDirection.NEUTRAL,
                )
            }
        },
    )
}

@Composable
private fun ChartSkeleton() {
    SkeletonCard(
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
        height = 250.dp,
    )
}
