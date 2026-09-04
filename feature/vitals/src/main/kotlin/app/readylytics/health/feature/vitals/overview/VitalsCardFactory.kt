package app.readylytics.health.feature.vitals.overview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.model.PersonalBaselineAssessment
import app.readylytics.health.core.model.domain.preferences.UnitSystem
import app.readylytics.health.core.model.domain.util.UnitConverter
import app.readylytics.health.core.ui.components.metriccard.toDashboardMode
import app.readylytics.health.core.ui.components.metriccard.toUniversalMode
import app.readylytics.health.feature.vitals.UniversalVitalsMetricCard
import app.readylytics.health.feature.vitals.cardio.Vo2MaxAssessment
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR

private const val RHR_DIAL_FLOOR = 30
private const val RHR_BASELINE_FILL = 0.5f
private const val VO2_MAX_DIAL_MAX = 60f
private const val HRV_MAX_FLOOR = 150f
private const val BODY_TEMP_VISUAL_MIN_CELSIUS = 35.5f
private const val BODY_TEMP_VISUAL_MAX_CELSIUS = 39f

// Build a map of CardId to composable card content for the Vitals screen. Only covers the
// CardIds the Vitals tab can render; the dashboard's buildCardDataMap is the sibling for the
// full catalog. Each entry is built by its own private factory below so this stays a flat,
// low-complexity assembly step rather than one long branching function.
fun buildVitalsCardDataMap(
    presentation: VitalsPresentationState,
    isEditing: Boolean,
    onNavigateToHrv: (() -> Unit)? = null,
    onNavigateToRhr: (() -> Unit)? = null,
    onNavigateToCardioFitness: (() -> Unit)? = null,
    onVitalsCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit = { _, _ -> },
): Map<CardId, @Composable (CardConfiguration) -> Unit> =
    mapOf(
        CardId.RESTING_HR to
            restingHrCardContent(presentation.rhr, isEditing, onNavigateToRhr, onVitalsCardDisplayModeChanged),
        CardId.HRV to
            hrvCardContent(presentation.hrv, isEditing, onNavigateToHrv, onVitalsCardDisplayModeChanged),
        CardId.OXYGEN_SATURATION to
            oxygenSaturationCardContent(
                presentation.spo2.value,
                presentation.spo2.status,
                isEditing,
                onVitalsCardDisplayModeChanged,
            ),
        CardId.BODY_TEMPERATURE to bodyTemperatureCardContent(presentation, isEditing, onVitalsCardDisplayModeChanged),
        CardId.CARDIO_FITNESS to
            cardioFitnessCardContent(
                presentation.vo2Max,
                isEditing,
                onNavigateToCardioFitness,
                onVitalsCardDisplayModeChanged,
            ),
    )

/** Formats a [PersonalBaselineAssessment.delta] as a signed "↑/↓/— N unit" secondary label. */
@Composable
private fun rememberDeltaText(
    delta: Int?,
    unit: String,
): String? {
    val deltaUpText = stringResource(CoreUiR.string.delta_up)
    val deltaDownText = stringResource(CoreUiR.string.delta_down)
    val deltaNoChangeText = stringResource(CoreUiR.string.delta_no_change)
    return remember(delta, deltaUpText, deltaDownText, deltaNoChangeText, unit) {
        when {
            delta == null -> null
            delta > 0 -> "$deltaUpText $delta $unit"
            delta < 0 -> "$deltaDownText ${abs(delta)} $unit"
            else -> deltaNoChangeText
        }
    }
}

private fun restingHrCardContent(
    rhrAssessment: PersonalBaselineAssessment,
    isEditing: Boolean,
    onNavigateToRhr: (() -> Unit)?,
    onVitalsCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit,
): @Composable (CardConfiguration) -> Unit =
    { configuration ->
        val spec = DashboardCardCatalog.spec(CardId.RESTING_HR)
        if (spec != null) {
            val baselineRhr = rhrAssessment.baseline
            val currentRhr = rhrAssessment.value
            val rhrFill =
                if (baselineRhr != null && baselineRhr > RHR_DIAL_FLOOR && currentRhr != null) {
                    (
                        (currentRhr - RHR_DIAL_FLOOR).toFloat() /
                            (baselineRhr - RHR_DIAL_FLOOR) * RHR_BASELINE_FILL
                    ).coerceIn(0f, 1f)
                } else {
                    null
                }
            val bpmUnit = stringResource(CoreUiR.string.unit_bpm)
            val rhrDelta = rememberDeltaText(rhrAssessment.delta, bpmUnit)
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

private fun hrvCardContent(
    hrvAssessment: PersonalBaselineAssessment,
    isEditing: Boolean,
    onNavigateToHrv: (() -> Unit)?,
    onVitalsCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit,
): @Composable (CardConfiguration) -> Unit =
    { configuration ->
        val spec = DashboardCardCatalog.spec(CardId.HRV)
        if (spec != null) {
            val baselineHrv = hrvAssessment.baseline
            val currentHrv = hrvAssessment.value
            val hrvMax = if (baselineHrv != null && baselineHrv > 0) baselineHrv * 2.0f else HRV_MAX_FLOOR
            val msUnit = stringResource(CoreUiR.string.unit_ms)
            val hrvDelta = rememberDeltaText(hrvAssessment.delta, msUnit)
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

private fun oxygenSaturationCardContent(
    spo2Value: Float?,
    spo2Status: MetricStatus,
    isEditing: Boolean,
    onVitalsCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit,
): @Composable (CardConfiguration) -> Unit =
    { configuration ->
        val spec = DashboardCardCatalog.spec(CardId.OXYGEN_SATURATION)
        if (spec != null) {
            val roundedSpo2 = spo2Value?.roundToInt()
            UniversalVitalsMetricCard(
                title = stringResource(CardId.OXYGEN_SATURATION.displayNameResId),
                // score() hardcodes minimum 0, so the 80..100 SpO2 scale is pre-normalized into a
                // 0..1 fraction rendered against maxValue = 1f.
                rawValue = spo2Value?.let { (it - 80f) / 20f },
                maxValue = 1f,
                valueText = roundedSpo2?.let { "$it%" } ?: stringResource(CoreUiR.string.metric_value_unavailable),
                unitText = "",
                status = spo2Status,
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

private fun bodyTemperatureCardContent(
    presentation: VitalsPresentationState,
    isEditing: Boolean,
    onVitalsCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit,
): @Composable (CardConfiguration) -> Unit =
    { configuration ->
        val spec = DashboardCardCatalog.spec(CardId.BODY_TEMPERATURE)
        if (spec != null) {
            val bodyTempUnitLabelRes =
                if (presentation.bodyTempUnitSystem == UnitSystem.IMPERIAL) {
                    CoreUiR.string.unit_fahrenheit
                } else {
                    CoreUiR.string.unit_celsius
                }
            val bodyTempVisualMin =
                UnitConverter.celsiusToDisplayTemperature(BODY_TEMP_VISUAL_MIN_CELSIUS, presentation.bodyTempUnitSystem)
            val bodyTempVisualMax =
                UnitConverter.celsiusToDisplayTemperature(BODY_TEMP_VISUAL_MAX_CELSIUS, presentation.bodyTempUnitSystem)
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

private fun cardioFitnessCardContent(
    vo2MaxAssessment: Vo2MaxAssessment,
    isEditing: Boolean,
    onNavigateToCardioFitness: (() -> Unit)?,
    onVitalsCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit,
): @Composable (CardConfiguration) -> Unit =
    { configuration ->
        val spec = DashboardCardCatalog.spec(CardId.CARDIO_FITNESS)
        if (spec != null) {
            val vo2MaxValue = vo2MaxAssessment.value
            UniversalVitalsMetricCard(
                title = stringResource(CardId.CARDIO_FITNESS.displayNameResId),
                rawValue = vo2MaxValue,
                maxValue = VO2_MAX_DIAL_MAX,
                valueText =
                    vo2MaxValue?.let { String.format(Locale.US, "%.1f", it) }
                        ?: stringResource(CoreUiR.string.metric_value_unavailable),
                unitText = stringResource(CoreUiR.string.unit_ml_kg_min),
                status = vo2MaxAssessment.status,
                secondaryText = null,
                tooltip = stringResource(CoreUiR.string.tooltip_cardio_fitness),
                supportedModes = spec.supportedModes.map { it.toUniversalMode() },
                requestedMode = DashboardCardCatalog.requestedMode(configuration).toUniversalMode(),
                isEditing = isEditing,
                onModeSelected = { mode ->
                    onVitalsCardDisplayModeChanged(CardId.CARDIO_FITNESS, mode.toDashboardMode())
                },
                onClick = onNavigateToCardioFitness,
            )
        }
    }
