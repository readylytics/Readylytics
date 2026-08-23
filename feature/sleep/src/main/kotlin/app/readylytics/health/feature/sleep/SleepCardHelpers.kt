package app.readylytics.health.feature.sleep

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.dashboard.ModeSpec
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.model.domain.sleep.SleepCardCatalog
import app.readylytics.health.core.model.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepMetricCardId
import app.readylytics.health.core.model.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepTopCardId
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.core.scoring.domain.scoring.toStatus
import app.readylytics.health.core.scoring.domain.scoring.toTimeString
import app.readylytics.health.core.scoring.domain.util.roundToPercentInt
import app.readylytics.health.core.ui.common.DateFormatUtils
import app.readylytics.health.core.ui.components.metriccard.UniversalCardDisplayMode
import app.readylytics.health.core.ui.components.metriccard.toDashboardMode
import app.readylytics.health.core.ui.components.metriccard.toUniversalMode
import app.readylytics.health.feature.sleep.R
import app.readylytics.health.core.ui.R as CoreUiR

/**
 * Helper to extract supported display modes from a mode spec.
 * Used across all metric and top card builders.
 */
internal fun supportedModes(spec: ModeSpec?): List<UniversalCardDisplayMode> =
    spec?.supportedModes?.map { it.toUniversalMode() } ?: listOf(UniversalCardDisplayMode.VALUE)

/**
 * Extract status determination for circadian consistency result.
 * Centralizes status logic used in metric card builders.
 */
internal fun circadianCardStatus(circadianResult: CircadianConsistencyResult): MetricStatus =
    circadianResult.toStatus()

/**
 * Builds circadian consistency display text from result.
 * Handles all states: Calibrating, MissingData, Ready.
 */
@Composable
internal fun buildCircadianScoreText(circadianResult: CircadianConsistencyResult): String =
    when (circadianResult) {
        is CircadianConsistencyResult.Calibrating ->
            stringResource(CoreUiR.string.spo2_calibrating)
        is CircadianConsistencyResult.MissingData ->
            stringResource(CoreUiR.string.metric_value_unavailable)
        is CircadianConsistencyResult.Ready ->
            stringResource(
                R.string.sleep_metric_percent_format,
                circadianResult.score.roundToPercentInt(),
            )
    }

/**
 * Builds circadian consistency window text (bedtime/wake time).
 * Returns null for Calibrating and MissingData states.
 */
@Composable
internal fun buildCircadianWindowText(circadianResult: CircadianConsistencyResult): String? =
    when (circadianResult) {
        is CircadianConsistencyResult.Calibrating,
        is CircadianConsistencyResult.MissingData,
        -> null
        is CircadianConsistencyResult.Ready ->
            stringResource(
                CoreUiR.string.label_circadian_median,
                circadianResult.medianBedtimeMinutes.toTimeString(),
                circadianResult.medianWakeMinutes.toTimeString(),
            )
    }

/**
 * Extracts threshold minutes from circadian result for tooltip and display.
 */
internal fun getCircadianThresholdMinutes(circadianResult: CircadianConsistencyResult): Int =
    when (circadianResult) {
        is CircadianConsistencyResult.Calibrating,
        is CircadianConsistencyResult.MissingData,
        -> 30
        is CircadianConsistencyResult.Ready -> circadianResult.thresholdMinutes
    }

/**
 * Builds efficiency metric card display text from session.
 * Returns formatted percentage or "unavailable" string.
 */
@Composable
internal fun buildEfficiencyText(session: SleepSessionData?): String =
    session?.let {
        stringResource(
            CoreUiR.string.card_efficiency_format,
            it.efficiency.roundToPercentInt(),
        )
    } ?: stringResource(CoreUiR.string.metric_value_unavailable)

/**
 * Builds deep sleep percentage display text from metrics.
 * Handles null metrics gracefully.
 */
@Composable
internal fun buildDeepSleepText(metrics: app.readylytics.health.core.model.domain.model.DailyMetrics?): String =
    metrics?.deepSleepPercentDisplay
        ?: stringResource(CoreUiR.string.metric_value_unavailable)

/**
 * Builds REM sleep percentage display text from metrics.
 * Handles null metrics gracefully.
 */
@Composable
internal fun buildRemSleepText(metrics: app.readylytics.health.core.model.domain.model.DailyMetrics?): String =
    metrics?.remSleepPercentDisplay
        ?: stringResource(CoreUiR.string.metric_value_unavailable)

/**
 * Builds nap count display text from metrics.
 * Returns formatted count or zero string.
 */
@Composable
internal fun buildNapCountText(metrics: app.readylytics.health.core.model.domain.model.DailyMetrics?): String =
    metrics?.napCount?.let {
        stringResource(R.string.sleep_metric_count_format, it)
    } ?: stringResource(R.string.sleep_metric_zero)

/**
 * Extracts raw score value from circadian result for gauge display.
 */
internal fun getCircadianRawScore(circadianResult: CircadianConsistencyResult): Float? =
    (circadianResult as? CircadianConsistencyResult.Ready)?.score
