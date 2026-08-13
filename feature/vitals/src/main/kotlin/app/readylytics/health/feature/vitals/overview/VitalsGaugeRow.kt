package app.readylytics.health.feature.vitals.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.common.CardLoader
import app.readylytics.health.core.ui.common.ScoreDialSkeleton
import app.readylytics.health.core.ui.components.metriccard.UniversalCardDisplayMode
import app.readylytics.health.feature.vitals.UniversalVitalsMetricCard
import kotlin.math.abs
import app.readylytics.health.core.ui.R as CoreUiR

private const val RHR_DIAL_FLOOR = 30
private const val RHR_BASELINE_FILL = 0.5f

/**
 * The RHR/HRV gauge row on the Vitals screen. Takes only gauge-relevant fields (never the raw
 * [VitalsUiState] or [VitalsChartInputs]) so chart-only state changes never recompose it.
 */
@Composable
internal fun VitalsGaugeRow(
    isLoading: Boolean,
    presentation: VitalsPresentationState,
    onNavigateToHrv: () -> Unit,
    onNavigateToRhr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CardLoader(
        isLoading = isLoading,
        skeleton = {
            Row(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = MaterialTheme.spacing.pageHorizontal,
                            vertical = MaterialTheme.spacing.small,
                        ),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScoreDialSkeleton(modifier = Modifier.weight(1f))
                ScoreDialSkeleton(modifier = Modifier.weight(1f))
            }
        },
        content = {
            Row(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = MaterialTheme.spacing.pageHorizontal,
                            vertical = MaterialTheme.spacing.small,
                        ),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val hrvAssessment = presentation.hrv
                val rhrAssessment = presentation.rhr
                val baselineHrv = hrvAssessment.baseline
                val baselineRhr = rhrAssessment.baseline
                val currentRhr = rhrAssessment.value
                val currentHrv = hrvAssessment.value

                val rhrFill =
                    if (baselineRhr != null && baselineRhr > RHR_DIAL_FLOOR && currentRhr != null) {
                        (
                            (currentRhr - RHR_DIAL_FLOOR).toFloat() /
                                (baselineRhr - RHR_DIAL_FLOOR) * RHR_BASELINE_FILL
                        ).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                val rhrTooltip = stringResource(CoreUiR.string.tooltip_sleep_rhr)

                // stringResource calls hoisted to locals before `remember` -- composable calls are
                // not allowed inside a remember lambda.
                val deltaUpText = stringResource(CoreUiR.string.delta_up)
                val deltaDownText = stringResource(CoreUiR.string.delta_down)
                val deltaNoChangeText = stringResource(CoreUiR.string.delta_no_change)
                val bpmUnit = stringResource(CoreUiR.string.unit_bpm)
                val msUnit = stringResource(CoreUiR.string.unit_ms)

                val rhrDelta =
                    remember(rhrAssessment.delta, deltaUpText, deltaDownText, deltaNoChangeText, bpmUnit) {
                        val diff = rhrAssessment.delta
                        if (diff != null) {
                            when {
                                diff > 0 -> "$deltaUpText $diff $bpmUnit"
                                diff < 0 -> "$deltaDownText ${abs(diff)} $bpmUnit"
                                else -> deltaNoChangeText
                            }
                        } else {
                            null
                        }
                    }

                UniversalVitalsMetricCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(CoreUiR.string.label_rhr),
                    supportedModes = listOf(UniversalCardDisplayMode.GAUGE),
                    requestedMode = UniversalCardDisplayMode.GAUGE,
                    rawValue = rhrFill,
                    valueText = currentRhr?.toString() ?: stringResource(CoreUiR.string.metric_value_unavailable),
                    unitText = bpmUnit,
                    maxValue = 1f,
                    status = rhrAssessment.status,
                    secondaryText = rhrDelta,
                    tooltip = rhrTooltip,
                    onClick = onNavigateToRhr,
                )

                val hrvMax = if (baselineHrv != null && baselineHrv > 0) baselineHrv * 2.0f else 150f
                val hrvTooltip = stringResource(CoreUiR.string.tooltip_sleep_hrv)

                val hrvDelta =
                    remember(hrvAssessment.delta, deltaUpText, deltaDownText, deltaNoChangeText, msUnit) {
                        val diff = hrvAssessment.delta
                        if (diff != null) {
                            when {
                                diff > 0 -> "$deltaUpText $diff $msUnit"
                                diff < 0 -> "$deltaDownText ${abs(diff)} $msUnit"
                                else -> deltaNoChangeText
                            }
                        } else {
                            null
                        }
                    }

                UniversalVitalsMetricCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(CoreUiR.string.label_hrv),
                    supportedModes = listOf(UniversalCardDisplayMode.GAUGE),
                    requestedMode = UniversalCardDisplayMode.GAUGE,
                    rawValue = currentHrv?.toFloat(),
                    valueText = currentHrv?.toString() ?: stringResource(CoreUiR.string.metric_value_unavailable),
                    unitText = msUnit,
                    maxValue = hrvMax,
                    status = hrvAssessment.status,
                    secondaryText = hrvDelta,
                    tooltip = hrvTooltip,
                    onClick = onNavigateToHrv,
                )
            }
        },
    )
}
