package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.model.domain.cardio.TsbZone
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.preferences.Gender
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.util.ResourceProvider
import app.readylytics.health.core.scoring.domain.cardio.CooperCategory
import app.readylytics.health.core.scoring.domain.cardio.CooperNormsClassifier
import app.readylytics.health.core.scoring.domain.cardio.TrainingStressBalanceCalculator
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR
import app.readylytics.health.feature.dashboard.R as DashboardR

class DashboardCardioMetricPresentationFactory
    @Inject
    constructor(
        private val resourceProvider: ResourceProvider,
        private val tsbCalculator: TrainingStressBalanceCalculator,
        private val cooperClassifier: CooperNormsClassifier,
    ) {
        private fun classificationText(status: MetricStatus): String =
            resourceProvider.getString(
                when (status) {
                    MetricStatus.OPTIMAL -> CoreUiR.string.metric_status_optimal
                    MetricStatus.NEUTRAL -> CoreUiR.string.metric_status_neutral
                    MetricStatus.WARNING -> CoreUiR.string.metric_status_warning
                    MetricStatus.POOR -> CoreUiR.string.metric_status_poor
                    MetricStatus.NO_DATA,
                    MetricStatus.CALIBRATING,
                    -> CoreUiR.string.metric_status_calibrating
                },
            )

        fun build(
            summary: DailySummary?,
            preferences: UserPreferences,
            unavailableValueText: String,
        ): Map<CardId, UniversalMetricPresentation> {
            val map = mutableMapOf<CardId, UniversalMetricPresentation>()
            buildCardioFitness(summary, preferences, unavailableValueText)?.let { map[CardId.CARDIO_FITNESS] = it }
            buildTsb(summary, unavailableValueText)?.let { map[CardId.TSB] = it }
            return map
        }

        private fun buildCardioFitness(
            summary: DailySummary?,
            preferences: UserPreferences,
            unavailableValueText: String,
        ): UniversalMetricPresentation? {
            val vo2MaxTitle = resourceProvider.getString(DashboardR.string.card_title_cardio_fitness)
            val vo2MaxVal = summary?.vo2Max
            val vo2MaxValText = vo2MaxVal?.roundToInt()?.toString() ?: unavailableValueText

            val gender = preferences.gender ?: Gender.OTHER
            val cooperCat =
                vo2MaxVal?.let {
                    cooperClassifier.classify(it, preferences.age, gender)
                }
            val vo2MaxStatus =
                when (cooperCat) {
                    null -> MetricStatus.NO_DATA
                    CooperCategory.SUPERIOR -> MetricStatus.OPTIMAL
                    CooperCategory.EXCELLENT -> MetricStatus.OPTIMAL
                    CooperCategory.GOOD -> MetricStatus.OPTIMAL
                    CooperCategory.FAIR -> MetricStatus.WARNING
                    CooperCategory.POOR -> MetricStatus.POOR
                }

            val vo2MaxSecondary = resourceProvider.getString(CoreUiR.string.unit_ml_kg_min)
            val vo2MaxTooltip = buildCardioTooltip(cooperCat, summary?.vo2MaxSource, preferences.age, gender)

            val vo2MaxDesc =
                resourceProvider.getString(
                    DashboardR.string.semantics_value_note_format,
                    vo2MaxTitle,
                    vo2MaxValText,
                    classificationText(vo2MaxStatus),
                )
            return UniversalMetricPresentation(
                title = vo2MaxTitle,
                valueText = vo2MaxValText,
                unitText = "",
                secondaryText = vo2MaxSecondary,
                status = vo2MaxStatus,
                tooltip = vo2MaxTooltip,
                accessibilityDescription = vo2MaxDesc,
                visual = UniversalMetricVisual.ValueOnly,
            )
        }

        private fun categoryLabel(category: CooperCategory): String =
            resourceProvider.getString(
                when (category) {
                    CooperCategory.SUPERIOR -> CoreUiR.string.cooper_category_superior
                    CooperCategory.EXCELLENT -> CoreUiR.string.cooper_category_excellent
                    CooperCategory.GOOD -> CoreUiR.string.cooper_category_good
                    CooperCategory.FAIR -> CoreUiR.string.cooper_category_fair
                    CooperCategory.POOR -> CoreUiR.string.cooper_category_poor
                },
            )

        private fun resolveSourceLabel(rawSource: String?): String =
            when (rawSource) {
                "WEARABLE" -> resourceProvider.getString(CoreUiR.string.vo2_max_source_label_wearable)
                "ESTIMATED_UTH" -> resourceProvider.getString(CoreUiR.string.vo2_max_source_label_estimated)
                else -> rawSource.orEmpty()
            }

        private fun buildCurrentSection(
            cooperCat: CooperCategory?,
            rawSource: String?,
        ): String {
            if (cooperCat == null) return ""
            val catLabel = categoryLabel(cooperCat)
            val sourceLabel = resolveSourceLabel(rawSource)
            val statusText = if (sourceLabel.isNotBlank()) "$catLabel • $sourceLabel" else catLabel
            return resourceProvider.getString(CoreUiR.string.tooltip_cardio_fitness_current, statusText) + "\n\n"
        }

        private fun formatNormsHeader(
            age: Int,
            gender: Gender,
        ): String {
            if (age <= 0) return resourceProvider.getString(CoreUiR.string.tooltip_cooper_norms_header)
            val genderStr =
                resourceProvider.getString(
                    when (gender) {
                        Gender.MALE -> CoreUiR.string.gender_male
                        Gender.FEMALE -> CoreUiR.string.gender_female
                        Gender.OTHER -> CoreUiR.string.gender_other
                        Gender.PREFER_NOT_TO_SAY -> CoreUiR.string.gender_prefer_not_to_say
                    },
                )
            val ageStr = resourceProvider.getString(CoreUiR.string.format_age_years, age)
            return resourceProvider.getString(CoreUiR.string.tooltip_cooper_norms_header_profile, ageStr, genderStr)
        }

        private fun formatNormsList(thresholds: CooperNormsClassifier.Thresholds): String =
            buildString {
                appendLine(
                    String.format(
                        Locale.US,
                        "• %s: ≥ %.1f",
                        categoryLabel(CooperCategory.SUPERIOR),
                        thresholds.superior,
                    ),
                )
                appendLine(
                    String.format(
                        Locale.US,
                        "• %s: %.1f–%.1f",
                        categoryLabel(CooperCategory.EXCELLENT),
                        thresholds.excellent,
                        thresholds.superior,
                    ),
                )
                appendLine(
                    String.format(
                        Locale.US,
                        "• %s: %.1f–%.1f",
                        categoryLabel(CooperCategory.GOOD),
                        thresholds.good,
                        thresholds.excellent,
                    ),
                )
                appendLine(
                    String.format(
                        Locale.US,
                        "• %s: %.1f–%.1f",
                        categoryLabel(CooperCategory.FAIR),
                        thresholds.fair,
                        thresholds.good,
                    ),
                )
                append(String.format(Locale.US, "• %s: < %.1f", categoryLabel(CooperCategory.POOR), thresholds.fair))
            }

        private fun buildCardioTooltip(
            cooperCat: CooperCategory?,
            rawSource: String?,
            age: Int,
            gender: Gender,
        ): String {
            val desc = resourceProvider.getString(CoreUiR.string.tooltip_cardio_fitness)
            val currentSection = buildCurrentSection(cooperCat, rawSource)
            val header = formatNormsHeader(age, gender)
            val thresholds = cooperClassifier.thresholds(age, gender)
            val norms = formatNormsList(thresholds)

            return "$desc\n\n$currentSection$header\n$norms"
        }

        private fun buildTsb(
            summary: DailySummary?,
            unavailableValueText: String,
        ): UniversalMetricPresentation? {
            val tsbTitle = resourceProvider.getString(DashboardR.string.card_title_tsb)
            val tsbTooltip = resourceProvider.getString(CoreUiR.string.tooltip_tsb)
            val tsbResult = tsbCalculator.calculate(summary?.ctlWorkoutOnly, summary?.atlWorkoutOnly)
            val tsbValue = tsbResult?.value?.roundToInt()
            val tsbValText = tsbValue?.let { if (it > 0) "+$it" else "$it" } ?: unavailableValueText
            val tsbStatus =
                when (tsbResult?.zone) {
                    null -> MetricStatus.CALIBRATING
                    TsbZone.VERY_FRESH_OR_TRANSITION -> MetricStatus.NEUTRAL
                    TsbZone.FRESH_PEAKED -> MetricStatus.OPTIMAL
                    TsbZone.OPTIMAL_PRODUCTIVE -> MetricStatus.OPTIMAL
                    TsbZone.FATIGUED_OVERLOAD -> MetricStatus.WARNING
                    TsbZone.HIGH_RISK_OVERREACHED -> MetricStatus.POOR
                }
            val tsbSecondary =
                tsbResult
                    ?.zone
                    ?.name
                    ?.replace("_", " ")
                    ?.lowercase()
                    ?.replaceFirstChar { it.uppercase() }
            val tsbDesc =
                resourceProvider.getString(
                    DashboardR.string.semantics_value_note_format,
                    tsbTitle,
                    tsbValText,
                    classificationText(tsbStatus),
                )
            return UniversalMetricPresentation(
                title = tsbTitle,
                valueText = tsbValText,
                unitText = "",
                secondaryText = tsbSecondary,
                status = tsbStatus,
                tooltip = tsbTooltip,
                accessibilityDescription = tsbDesc,
                visual = UniversalMetricVisual.ValueOnly,
            )
        }
    }
