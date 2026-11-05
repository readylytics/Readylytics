package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.display.MetricFormatter
import app.readylytics.health.core.model.domain.model.BodyCompositionAssessment
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.model.toMetricStatus
import app.readylytics.health.core.model.domain.preferences.UnitSystem
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.util.ResourceProvider
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricScalePreparer
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricUnavailableReason
import javax.inject.Inject
import app.readylytics.health.core.ui.R as CoreUiR
import app.readylytics.health.feature.dashboard.R as DashboardR

class DashboardBodyCompositionMetricPresentationFactory
    @Inject
    constructor(
        private val resourceProvider: ResourceProvider,
    ) {
        private val helpers = DashboardMetricPresentationHelpers(resourceProvider)

        fun build(
            summary: DailySummary?,
            preferences: UserPreferences,
            unavailableValueText: String = resourceProvider.getString(CoreUiR.string.metric_value_unavailable),
        ): Map<CardId, UniversalMetricPresentation> =
            mapOf(
                CardId.WEIGHT to weightPresentation(summary, preferences, unavailableValueText),
                CardId.BODY_FAT to bodyFatPresentation(summary, preferences, unavailableValueText),
            )

        private fun weightPresentation(
            summary: DailySummary?,
            preferences: UserPreferences,
            unavailableValueText: String,
        ): UniversalMetricPresentation {
            val heightM = (preferences.heightCm ?: 0f) / 100f
            val isHeightValid = heightM > 0f
            val bmi = if (isHeightValid) summary?.weightKg?.let { it / (heightM * heightM) } else null
            val bmiAssessment = bmi?.let(BodyCompositionAssessment::assessBmi)
            val bmiReference = BodyCompositionAssessment.bmiReference
            val weightVisual =
                UniversalMetricScalePreparer.referenceRange(
                    value = bmi,
                    minimum = bmiReference.axisMinimum,
                    midpoint = bmiReference.referenceMidpoint,
                    maximum = bmiReference.axisMaximum,
                    scaleAvailable = isHeightValid,
                    unavailableReason = if (!isHeightValid) UniversalMetricUnavailableReason.MISSING_BMI else null,
                )
            val weightStatus = bmiAssessment?.status?.toMetricStatus() ?: MetricStatus.CALIBRATING
            val weightTitle = resourceProvider.getString(DashboardR.string.card_title_weight)
            val weightValueText =
                summary?.weightKg?.let {
                    MetricFormatter.formatWeightNumericOnly(it, preferences.unitSystem)
                } ?: unavailableValueText
            val weightUnitText =
                resourceProvider.getString(
                    if (preferences.unitSystem == UnitSystem.METRIC) {
                        CoreUiR.string.unit_kg
                    } else {
                        CoreUiR.string.unit_lbs
                    },
                )
            val weightTooltip =
                if (summary?.weightKg == null) {
                    resourceProvider.getString(CoreUiR.string.card_tooltip_weight_no_data)
                } else {
                    resourceProvider.getString(CoreUiR.string.card_tooltip_weight_latest)
                }
            val weightDescription =
                weightVisual.unavailableReason?.let { reason ->
                    helpers.unavailableDescription(weightTitle, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_value_note_format,
                    weightTitle,
                    "$weightValueText $weightUnitText",
                    helpers.classificationText(weightStatus),
                )
            return UniversalMetricPresentation(
                title = weightTitle,
                valueText = weightValueText,
                unitText = weightUnitText,
                secondaryText = null,
                status = weightStatus,
                tooltip = weightTooltip,
                accessibilityDescription = weightDescription,
                visual = weightVisual,
            )
        }

        private fun bodyFatPresentation(
            summary: DailySummary?,
            preferences: UserPreferences,
            unavailableValueText: String,
        ): UniversalMetricPresentation {
            val bodyFatPercent = summary?.bodyFatPercent
            val bodyFatAssessment =
                bodyFatPercent?.let {
                    BodyCompositionAssessment.assessBodyFat(
                        bodyFatPercent = it,
                        physiologyProfile = preferences.physiologyProfile,
                        gender = preferences.gender,
                    )
                }
            val bodyFatStatusVal = bodyFatAssessment?.status?.toMetricStatus() ?: MetricStatus.NEUTRAL
            val bodyFatVisual =
                UniversalMetricScalePreparer.referenceRange(
                    value = bodyFatPercent,
                    minimum = bodyFatAssessment?.reference?.axisMinimum ?: 0f,
                    midpoint = bodyFatAssessment?.reference?.referenceMidpoint ?: 20f,
                    maximum = bodyFatAssessment?.reference?.axisMaximum ?: 40f,
                    scaleAvailable = true,
                    unavailableReason = null,
                )
            val bodyFatTitle = resourceProvider.getString(DashboardR.string.card_title_body_fat)
            val bodyFatValueText = bodyFatPercent?.let { MetricFormatter.formatBodyFat(it) } ?: unavailableValueText
            val bodyFatTooltip =
                if (bodyFatPercent == null) {
                    resourceProvider.getString(CoreUiR.string.card_tooltip_body_fat_no_data)
                } else {
                    resourceProvider.getString(CoreUiR.string.card_tooltip_body_fat_latest)
                }
            val bodyFatDescription =
                bodyFatVisual.unavailableReason?.let { reason ->
                    helpers.unavailableDescription(bodyFatTitle, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_value_note_format,
                    bodyFatTitle,
                    bodyFatValueText,
                    helpers.classificationText(bodyFatStatusVal),
                )
            return UniversalMetricPresentation(
                title = bodyFatTitle,
                valueText = bodyFatValueText,
                unitText = "",
                secondaryText = null,
                status = bodyFatStatusVal,
                tooltip = bodyFatTooltip,
                accessibilityDescription = bodyFatDescription,
                visual = bodyFatVisual,
            )
        }
    }
