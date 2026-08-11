package app.readylytics.health.feature.vitals.overview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.common.CardLoader
import app.readylytics.health.core.ui.common.DeltaDirection
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.components.TrendCard
import app.readylytics.health.core.ui.components.TrendChart
import app.readylytics.health.domain.preferences.UnitSystem
import app.readylytics.health.feature.vitals.R
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import app.readylytics.health.core.ui.R as CoreUiR

/**
 * The four Vico trend charts (HRV, RHR, SpO2, body temperature) on the Vitals screen. Takes only [VitalsChartInputs]
 * (never the raw [VitalsUiState]) so gauge-only or refresh-only state changes never recompose the
 * chart subtree -- this is the guarantee F1/F5 exist to provide.
 */
@Composable
internal fun VitalsTrendSection(
    chartInputs: VitalsChartInputs,
    chartScrollState: VicoScrollState,
    chartZoomState: VicoZoomState,
    parentScrollInProgress: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val chartSeries = chartInputs.chartSeries
    val presentation = chartInputs.presentation

    Column(modifier = modifier) {
        // Note: graphicsLayer{} intentionally omitted for performance (F19)
        // Chart 1: HRV Trend
        CardLoader(
            isLoading = chartInputs.isLoading,
            skeleton = {
                SkeletonCard(
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                    height = 250.dp,
                )
            },
            content = {
                TrendCard(
                    title = stringResource(R.string.label_hrv_rmssd),
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                ) {
                    TrendChart(
                        points = chartSeries.hrv,
                        rangeStartMs = chartInputs.rangeStartMs,
                        rangeDays = chartInputs.selectedRange.days,
                        metricName = stringResource(CoreUiR.string.label_hrv),
                        baselineUnit = stringResource(CoreUiR.string.unit_ms),
                        modifier = Modifier.testTag("HrvTrendChart"),
                        baseline =
                            chartSeries.historicalHrvBaselineAverage?.toFloat()
                                ?: presentation.hrv.baseline?.toFloat(),
                        showBaseline = presentation.hrv.baseline != null,
                        scrollState = chartScrollState,
                        zoomState = chartZoomState,
                        zoneBands =
                            chartSeries.historicalHrvZoneBands.takeIf { it.isNotEmpty() }
                                ?: presentation.hrv.zoneBands,
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

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

        // Chart 2: Resting HR Trend
        CardLoader(
            isLoading = chartInputs.isLoading,
            skeleton = {
                SkeletonCard(
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                    height = 250.dp,
                )
            },
            content = {
                TrendCard(
                    title = stringResource(R.string.label_resting_heart_rate),
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                ) {
                    TrendChart(
                        points = chartSeries.rhr,
                        rangeStartMs = chartInputs.rangeStartMs,
                        rangeDays = chartInputs.selectedRange.days,
                        metricName = stringResource(CoreUiR.string.label_rhr),
                        baselineUnit = stringResource(CoreUiR.string.unit_bpm),
                        modifier = Modifier.testTag("RestingHeartRateTrendChart"),
                        baseline =
                            chartSeries.historicalRhrBaselineAverage?.toFloat()
                                ?: presentation.rhr.baseline?.toFloat(),
                        showBaseline = presentation.rhr.baseline != null,
                        scrollState = chartScrollState,
                        zoomState = chartZoomState,
                        zoneBands =
                            chartSeries.historicalRhrZoneBands.takeIf { it.isNotEmpty() }
                                ?: presentation.rhr.zoneBands,
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

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

        // Chart 3: SpO2 Trend
        CardLoader(
            isLoading = chartInputs.isLoading,
            skeleton = {
                SkeletonCard(
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                    height = 250.dp,
                )
            },
            content = {
                TrendCard(
                    title = stringResource(R.string.label_oxygen_saturation),
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                ) {
                    TrendChart(
                        points = chartSeries.spo2,
                        rangeStartMs = chartInputs.rangeStartMs,
                        rangeDays = chartInputs.selectedRange.days,
                        metricName = stringResource(CoreUiR.string.label_spo2),
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

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

        // Chart 4: Body Temperature Trend
        CardLoader(
            isLoading = chartInputs.isLoading,
            skeleton = {
                SkeletonCard(
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                    height = 250.dp,
                )
            },
            content = {
                TrendCard(
                    title = stringResource(CoreUiR.string.label_body_temperature),
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                ) {
                    TrendChart(
                        points = chartSeries.bodyTemp,
                        rangeStartMs = chartInputs.rangeStartMs,
                        rangeDays = chartInputs.selectedRange.days,
                        metricName = stringResource(CoreUiR.string.label_body_temperature),
                        baselineUnit =
                            if (presentation.bodyTempUnitSystem == UnitSystem.IMPERIAL) {
                                stringResource(CoreUiR.string.unit_fahrenheit)
                            } else {
                                stringResource(CoreUiR.string.unit_celsius)
                            },
                        modifier = Modifier.testTag("BodyTemperatureTrendChart"),
                        baseline = presentation.baselineBodyTemp,
                        showBaseline = presentation.baselineBodyTemp != null,
                        baselineUnavailableLabel =
                            if (presentation.baselineBodyTemp == null) {
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
}
