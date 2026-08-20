package app.readylytics.health.feature.vitals.overview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.util.UnitConverter
import app.readylytics.health.core.ui.components.metriccard.toDashboardMode
import app.readylytics.health.core.ui.components.metriccard.toUniversalMode
import app.readylytics.health.domain.preferences.UnitSystem
import app.readylytics.health.feature.vitals.UniversalVitalsMetricCard
import kotlin.math.abs
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR

private const val RHR_DIAL_FLOOR = 30
private const val RHR_BASELINE_FILL = 0.5f

// Build a map of CardId to composable card content for the Vitals screen. Only covers the
// CardIds the Vitals tab can render; the dashboard's buildCardDataMap is the sibling for the
// full catalog.
fun buildVitalsCardDataMap(
    presentation: VitalsPresentationState,
    isEditing: Boolean,
    onNavigateToHrv: (() -> Unit)? = null,
    onNavigateToRhr: (() -> Unit)? = null,
    onVitalsCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit = { _, _ -> },
): Map<CardId, @Composable (CardConfiguration) -> Unit> {
    val cardMap = mutableMapOf<CardId, @Composable (CardConfiguration) -> Unit>()

    val rhrAssessment = presentation.rhr
    val hrvAssessment = presentation.hrv
    val baselineRhr = rhrAssessment.baseline
    val baselineHrv = hrvAssessment.baseline
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
    val hrvMax = if (baselineHrv != null && baselineHrv > 0) baselineHrv * 2.0f else 150f

    cardMap[CardId.RESTING_HR] = { configuration ->
        val spec = DashboardCardCatalog.spec(CardId.RESTING_HR)
        if (spec != null) {
            val deltaUpText = stringResource(CoreUiR.string.delta_up)
            val deltaDownText = stringResource(CoreUiR.string.delta_down)
            val deltaNoChangeText = stringResource(CoreUiR.string.delta_no_change)
            val bpmUnit = stringResource(CoreUiR.string.unit_bpm)
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
                title = stringResource(CardId.RESTING_HR.displayNameResId),
                rawValue = rhrFill,
                maxValue = 1f,
                valueText = currentRhr?.toString() ?: stringResource(CoreUiR.string.metric_value_unavailable),
                unitText = bpmUnit,
                status = rhrAssessment.status,
                secondaryText = rhrDelta,
                tooltip = stringResource(CoreUiR.string.tooltip_sleep_rhr),
                supportedModes = spec.supportedModes.map { it.toUniversalMode() },
                requestedMode = DashboardCardCatalog.requestedMode(configuration).toUniversalMode(),
                isEditing = isEditing,
                onModeSelected = { mode ->
                    onVitalsCardDisplayModeChanged(CardId.RESTING_HR, mode.toDashboardMode())
                },
                onClick = onNavigateToRhr,
            )
        }
    }

    cardMap[CardId.HRV] = { configuration ->
        val spec = DashboardCardCatalog.spec(CardId.HRV)
        if (spec != null) {
            val deltaUpText = stringResource(CoreUiR.string.delta_up)
            val deltaDownText = stringResource(CoreUiR.string.delta_down)
            val deltaNoChangeText = stringResource(CoreUiR.string.delta_no_change)
            val msUnit = stringResource(CoreUiR.string.unit_ms)
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
                title = stringResource(CardId.HRV.displayNameResId),
                rawValue = currentHrv?.toFloat(),
                maxValue = hrvMax,
                valueText = currentHrv?.toString() ?: stringResource(CoreUiR.string.metric_value_unavailable),
                unitText = msUnit,
                status = hrvAssessment.status,
                secondaryText = hrvDelta,
                tooltip = stringResource(CoreUiR.string.tooltip_sleep_hrv),
                supportedModes = spec.supportedModes.map { it.toUniversalMode() },
                requestedMode = DashboardCardCatalog.requestedMode(configuration).toUniversalMode(),
                isEditing = isEditing,
                onModeSelected = { mode ->
                    onVitalsCardDisplayModeChanged(CardId.HRV, mode.toDashboardMode())
                },
                onClick = onNavigateToHrv,
            )
        }
    }

    val spo2Value = presentation.spo2.value
    val roundedSpo2 = spo2Value?.roundToInt()

    cardMap[CardId.OXYGEN_SATURATION] = { configuration ->
        val spec = DashboardCardCatalog.spec(CardId.OXYGEN_SATURATION)
        if (spec != null) {
            UniversalVitalsMetricCard(
                title = stringResource(CardId.OXYGEN_SATURATION.displayNameResId),
                // score() hardcodes minimum 0, so the 80..100 SpO2 scale is pre-normalized into a
                // 0..1 fraction rendered against maxValue = 1f.
                rawValue = spo2Value?.let { (it - 80f) / 20f },
                maxValue = 1f,
                valueText = roundedSpo2?.let { "$it%" } ?: stringResource(CoreUiR.string.metric_value_unavailable),
                unitText = "",
                status = presentation.spo2.status,
                secondaryText = null,
                tooltip = stringResource(CoreUiR.string.tooltip_vitals_spo2),
                supportedModes = spec.supportedModes.map { it.toUniversalMode() },
                requestedMode = DashboardCardCatalog.requestedMode(configuration).toUniversalMode(),
                isEditing = isEditing,
                onModeSelected = { mode ->
                    onVitalsCardDisplayModeChanged(CardId.OXYGEN_SATURATION, mode.toDashboardMode())
                },
                onClick = null,
            )
        }
    }

    val bodyTempUnitLabelRes =
        if (presentation.bodyTempUnitSystem == UnitSystem.IMPERIAL) {
            CoreUiR.string.unit_fahrenheit
        } else {
            CoreUiR.string.unit_celsius
        }
    val bodyTempVisualMin =
        UnitConverter.celsiusToDisplayTemperature(35.5f, presentation.bodyTempUnitSystem)
    val bodyTempVisualMax =
        UnitConverter.celsiusToDisplayTemperature(39f, presentation.bodyTempUnitSystem)

    cardMap[CardId.BODY_TEMPERATURE] = { configuration ->
        val spec = DashboardCardCatalog.spec(CardId.BODY_TEMPERATURE)
        if (spec != null) {
            val bodyTempDisplayValue = presentation.bodyTemp.value
            val bodyTempBaseline = presentation.bodyTemp.baseline
            val bodyTempSecondaryText =
                when {
                    bodyTempDisplayValue == null -> null
                    bodyTempBaseline == null -> stringResource(CoreUiR.string.body_temperature_calibrating)
                    else -> {
                        val deltaDisplay = bodyTempDisplayValue - bodyTempBaseline
                        val sign = if (deltaDisplay >= 0f) "+" else ""
                        "$sign%.1f°".format(deltaDisplay)
                    }
                }
            UniversalVitalsMetricCard(
                title = stringResource(CardId.BODY_TEMPERATURE.displayNameResId),
                // score() hardcodes minimum 0, so the display-unit body temperature scale
                // (35.5..39 in display units) is pre-normalized into a 0..1 fraction rendered
                // against maxValue = 1f.
                rawValue =
                    bodyTempDisplayValue?.let {
                        ((it - bodyTempVisualMin) / (bodyTempVisualMax - bodyTempVisualMin)).coerceIn(0f, 1f)
                    },
                maxValue = 1f,
                valueText =
                    bodyTempDisplayValue?.let { "%.1f".format(it) }
                        ?: stringResource(CoreUiR.string.metric_value_unavailable),
                unitText = stringResource(bodyTempUnitLabelRes),
                status = presentation.bodyTemp.status,
                secondaryText = bodyTempSecondaryText,
                tooltip = stringResource(CoreUiR.string.tooltip_vitals_body_temperature),
                supportedModes = spec.supportedModes.map { it.toUniversalMode() },
                requestedMode = DashboardCardCatalog.requestedMode(configuration).toUniversalMode(),
                isEditing = isEditing,
                onModeSelected = { mode ->
                    onVitalsCardDisplayModeChanged(CardId.BODY_TEMPERATURE, mode.toDashboardMode())
                },
                onClick = null,
            )
        }
    }

    return cardMap
}
