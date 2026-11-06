package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.model.DailyMetrics
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.HeartRateStatusClassifier
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.model.assessSpo2
import app.readylytics.health.core.model.domain.model.bodyTemperatureStatus
import app.readylytics.health.core.model.domain.model.toMetricStatus
import app.readylytics.health.core.model.domain.preferences.UnitSystem
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.service.HealthMetricsService
import app.readylytics.health.core.model.domain.util.ResourceProvider
import app.readylytics.health.core.model.domain.util.UnitConverter
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricScalePreparer
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricUnavailableReason
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import app.readylytics.health.core.ui.model.HeartRateDaySummary
import javax.inject.Inject
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR
import app.readylytics.health.feature.dashboard.R as DashboardR

private const val SPO2_SCALE_MIN = 80f
private const val SPO2_SCALE_MAX = 100f
private const val BODY_TEMP_SCALE_MIN_CELSIUS = 35.5f
private const val BODY_TEMP_SCALE_MAX_CELSIUS = 39f

class DashboardVitalsMetricPresentationFactory
    @Inject
    constructor(
        private val resourceProvider: ResourceProvider,
    ) {
        private val helpers = DashboardMetricPresentationHelpers(resourceProvider)

        fun build(
            summary: DailySummary?,
            preferences: UserPreferences,
            heartRateSummary: HeartRateDaySummary? = null,
            bodyTempBaseline: Float? = null,
            metrics: DailyMetrics? = null,
            unavailableValueText: String = resourceProvider.getString(CoreUiR.string.metric_value_unavailable),
        ): Map<CardId, UniversalMetricPresentation> =
            mapOf(
                CardId.OXYGEN_SATURATION to oxygenSaturationPresentation(summary, unavailableValueText),
                CardId.BLOOD_PRESSURE to bloodPressurePresentation(summary, metrics, unavailableValueText),
                CardId.BODY_TEMPERATURE to
                    bodyTemperaturePresentation(
                        summary = summary,
                        preferences = preferences,
                        bodyTempBaseline = bodyTempBaseline,
                        unavailableValueText = unavailableValueText,
                    ),
                CardId.HEART_RATE to heartRatePresentation(heartRateSummary, unavailableValueText),
            )

        private fun oxygenSaturationPresentation(
            summary: DailySummary?,
            unavailableValueText: String,
        ): UniversalMetricPresentation {
            val spo2Assessment = assessSpo2(summary?.avgSleepingSpo2)
            val spo2 = spo2Assessment.value
            val roundedSpo2 = spo2?.roundToInt()
            val oxygenStatus = spo2Assessment.status
            val spo2Visual =
                UniversalMetricScalePreparer.score(
                    spo2,
                    SPO2_SCALE_MIN,
                    SPO2_SCALE_MAX,
                )
            val spo2Title =
                resourceProvider.getString(
                    DashboardR.string.card_title_oxygen_saturation,
                )
            // Percent baked into the main value text (like Sleep Efficiency) so the "%" renders
            // at the value's size instead of as a small separate unit.
            val spo2ValueText = roundedSpo2?.let { "$it%" } ?: unavailableValueText
            val spo2Description =
                spo2Visual.unavailableReason?.let { reason ->
                    helpers.unavailableDescription(spo2Title, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_value_note_format,
                    spo2Title,
                    spo2ValueText,
                    helpers.classificationText(oxygenStatus),
                )
            return UniversalMetricPresentation(
                title = spo2Title,
                valueText = spo2ValueText,
                unitText = "",
                secondaryText = null,
                status = oxygenStatus,
                tooltip = resourceProvider.getString(CoreUiR.string.tooltip_vitals_spo2),
                accessibilityDescription = spo2Description,
                visual = spo2Visual,
            )
        }

        private fun bloodPressurePresentation(
            summary: DailySummary?,
            metrics: DailyMetrics?,
            unavailableValueText: String,
        ): UniversalMetricPresentation {
            val systolic = summary?.bloodPressureSystolic ?: 0
            val diastolic = summary?.bloodPressureDiastolic ?: 0
            val bpStatus =
                if (systolic > 0 && diastolic > 0) {
                    HealthMetricsService().assessBloodPressure(systolic, diastolic).toMetricStatus()
                } else {
                    MetricStatus.NEUTRAL
                }
            val bpTitle =
                resourceProvider.getString(
                    DashboardR.string.card_title_blood_pressure,
                )
            val bpValueText =
                metrics?.bloodPressureDisplay
                    ?: if (systolic > 0 && diastolic > 0) "$systolic/$diastolic" else unavailableValueText
            val bpUnitText = resourceProvider.getString(CoreUiR.string.unit_mmHg)
            val bpTooltip =
                if (systolic <= 0 || diastolic <= 0) {
                    resourceProvider.getString(CoreUiR.string.card_tooltip_bp_no_data)
                } else {
                    resourceProvider.getString(CoreUiR.string.card_tooltip_bp_latest)
                }
            val bpDescription =
                if (systolic <= 0 || diastolic <= 0) {
                    helpers.unavailableDescription(bpTitle, UniversalMetricUnavailableReason.MISSING_VALUE)
                } else {
                    resourceProvider.getString(
                        DashboardR.string.semantics_value_note_format,
                        bpTitle,
                        "$bpValueText $bpUnitText",
                        helpers.classificationText(bpStatus),
                    )
                }
            return UniversalMetricPresentation(
                title = bpTitle,
                valueText = bpValueText,
                unitText = bpUnitText,
                secondaryText = null,
                status = bpStatus,
                tooltip = bpTooltip,
                accessibilityDescription = bpDescription,
                visual = UniversalMetricVisual.ValueOnly,
            )
        }

        private fun bodyTemperaturePresentation(
            summary: DailySummary?,
            preferences: UserPreferences,
            bodyTempBaseline: Float?,
            unavailableValueText: String,
        ): UniversalMetricPresentation {
            val bodyTempCelsius = summary?.avgSleepingBodyTemp
            val unitSystem = preferences.unitSystem
            val bodyTempDisplay =
                bodyTempCelsius?.let { UnitConverter.celsiusToDisplayTemperature(it, unitSystem) }
            val bodyTempUnitLabel =
                if (unitSystem == UnitSystem.IMPERIAL) {
                    resourceProvider.getString(CoreUiR.string.unit_fahrenheit)
                } else {
                    resourceProvider.getString(CoreUiR.string.unit_celsius)
                }
            val bodyTempStatus =
                bodyTemperatureStatus(bodyTempCelsius, bodyTempBaseline, preferences.bodyTempElevatedThresholdCelsius)
            val bodyTempTitle = resourceProvider.getString(DashboardR.string.card_title_body_temperature)
            val bodyTempValueText =
                bodyTempDisplay?.let { "%.1f".format(it) } ?: unavailableValueText
            val bodyTempSecondaryText =
                when {
                    bodyTempCelsius == null -> null
                    bodyTempBaseline == null -> resourceProvider.getString(CoreUiR.string.body_temperature_calibrating)
                    else -> {
                        val deltaCelsius = bodyTempCelsius - bodyTempBaseline
                        val deltaDisplay = UnitConverter.celsiusDeltaToDisplayDelta(deltaCelsius, unitSystem)
                        val sign = if (deltaDisplay >= 0f) "+" else ""
                        "$sign%.1f°".format(deltaDisplay)
                    }
                }
            val bodyTempVisual =
                UniversalMetricScalePreparer.score(
                    bodyTempDisplay,
                    UnitConverter.celsiusToDisplayTemperature(BODY_TEMP_SCALE_MIN_CELSIUS, unitSystem),
                    UnitConverter.celsiusToDisplayTemperature(BODY_TEMP_SCALE_MAX_CELSIUS, unitSystem),
                )
            val bodyTempDescription =
                bodyTempVisual.unavailableReason?.let { reason ->
                    helpers.unavailableDescription(bodyTempTitle, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_value_note_format,
                    bodyTempTitle,
                    bodyTempValueText,
                    helpers.classificationText(bodyTempStatus),
                )
            return UniversalMetricPresentation(
                title = bodyTempTitle,
                valueText = bodyTempValueText,
                unitText = bodyTempUnitLabel,
                secondaryText = bodyTempSecondaryText,
                status = bodyTempStatus,
                tooltip = resourceProvider.getString(CoreUiR.string.tooltip_vitals_body_temperature),
                accessibilityDescription = bodyTempDescription,
                visual = bodyTempVisual,
            )
        }

        private fun heartRatePresentation(
            heartRateSummary: HeartRateDaySummary?,
            unavailableValueText: String,
        ): UniversalMetricPresentation {
            val hrTitle =
                resourceProvider.getString(
                    DashboardR.string.card_title_heart_rate,
                )
            val hrValueText = heartRateSummary?.let { "${it.minBpm}–${it.maxBpm}" } ?: unavailableValueText
            val hrSecondaryText =
                heartRateSummary?.let {
                    resourceProvider.getString(CoreUiR.string.hr_avg_display, it.avgBpm)
                }
            val hrStatus = HeartRateStatusClassifier.classify(heartRateSummary?.avgBpm)
            val hrDescription =
                if (heartRateSummary?.avgBpm == null) {
                    helpers.unavailableDescription(hrTitle, UniversalMetricUnavailableReason.MISSING_VALUE)
                } else {
                    resourceProvider.getString(
                        DashboardR.string.semantics_value_note_format,
                        hrTitle,
                        "$hrValueText $hrSecondaryText",
                        helpers.classificationText(hrStatus),
                    )
                }
            return UniversalMetricPresentation(
                title = hrTitle,
                valueText = hrValueText,
                unitText = "",
                secondaryText = hrSecondaryText,
                status = hrStatus,
                tooltip = resourceProvider.getString(DashboardR.string.tooltip_heart_rate_card),
                accessibilityDescription = hrDescription,
                visual = UniversalMetricVisual.ValueOnly,
            )
        }
    }
