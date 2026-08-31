package app.readylytics.health.feature.dashboard.usecase
import app.readylytics.health.core.model.data.preferences.Gender
import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.util.ResourceProvider
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricUnavailableReason
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import java.time.LocalDate
import app.readylytics.health.core.ui.R as CoreUiR
import app.readylytics.health.feature.dashboard.R as DashboardR

abstract class DashboardMetricPresentationFactoryTestBase {
    protected lateinit var factory: DashboardMetricPresentationFactory
    protected lateinit var resourceProvider: ResourceProvider

    @Before
    fun setup() {
        resourceProvider = mockk(relaxed = true)
        factory =
            DashboardMetricPresentationFactory(
                resourceProvider,
                ResidualFatiguePresentationFactory(resourceProvider),
            )
        every { resourceProvider.getString(any()) } returns "mock_string"
        every { resourceProvider.getString(CoreUiR.string.metric_value_unavailable) } returns "—"
        every { resourceProvider.getString(CoreUiR.string.score_maximum) } returns "100"
        every { resourceProvider.getString(CoreUiR.string.unit_kg) } returns "kg"
        every { resourceProvider.getString(CoreUiR.string.unit_lbs) } returns "lbs"
        every { resourceProvider.getString(any(), any()) } returns "BMI mock_string"
        every { resourceProvider.getString(any(), any(), any()) } returns "BMI mock_string"
        // Task 10: DashboardMetricPresentationFactory now also calls getString with 3 and 4
        // vararg format args (e.g. semantics_score_format, semantics_weight_bmi_format) to build
        // real accessibilityDescription text; stub those arities too so the relaxed mock doesn't
        // fall through to an empty-string default for them.
        every { resourceProvider.getString(any(), any(), any(), any()) } returns "mock_string"
        every { resourceProvider.getString(any(), any(), any(), any(), any()) } returns "mock_string"
        every {
            resourceProvider.getString(DashboardR.string.card_residual_fatigue_secondary, *anyVararg())
        } answers { "Half-life: ${invocation.formatArguments()[0]}h" }
    }

    protected fun summary(
        weightKg: Float? = null,
        bodyFatPercent: Float? = null,
        strainRatio: Float? = null,
        residualFatigue: Float? = null,
    ) = DailySummary(
        date = date,
        weightKg = weightKg,
        bodyFatPercent = bodyFatPercent,
        strainRatioWorkoutOnly = strainRatio,
        strainRatioEverydayHr = strainRatio,
        residualFatigue = residualFatigue,
    )

    protected fun preferences(
        heightCm: Float = 180f,
        gender: Gender = Gender.MALE,
        physiologyProfile: PhysiologyProfile = PhysiologyProfile.ACTIVE,
        residualFatigueEnabled: Boolean = true,
        residualFatigueHalfLifeHours: Float = 24f,
        residualFatigueGain: Float = 1f,
    ) = UserPreferences(
        heightCm = heightCm,
        gender = gender,
        physiologyProfile = physiologyProfile,
        residualFatigueEnabled = residualFatigueEnabled,
        residualFatigueHalfLifeHours = residualFatigueHalfLifeHours,
        residualFatigueGain = residualFatigueGain,
    )

    protected val date = LocalDate.now()

    protected val tooltipStubs =
        mapOf(
            CoreUiR.string.tooltip_sleep_score to "tooltip sleep score",
            CoreUiR.string.tooltip_readiness to "tooltip readiness",
            CoreUiR.string.card_tooltip_weight_no_data to "tooltip weight no data",
            CoreUiR.string.card_tooltip_weight_latest to "tooltip weight latest",
            CoreUiR.string.card_tooltip_body_fat_no_data to "tooltip body fat no data",
            CoreUiR.string.card_tooltip_body_fat_latest to "tooltip body fat latest",
            CoreUiR.string.card_tooltip_sleep_efficiency to "tooltip sleep efficiency",
            CoreUiR.string.tooltip_vitals_spo2 to "tooltip spo2",
            CoreUiR.string.card_tooltip_bp_no_data to "tooltip bp no data",
            CoreUiR.string.card_tooltip_bp_latest to "tooltip bp latest",
            DashboardR.string.tooltip_heart_rate_card to "tooltip heart rate",
            CoreUiR.string.tooltip_circadian_score to "tooltip circadian",
            CoreUiR.string.tooltip_strain_ratio to "tooltip strain ratio",
            DashboardR.string.tooltip_residual_fatigue to "tooltip residual fatigue",
        )

    protected fun stubTooltips() {
        tooltipStubs.forEach { (resourceId, text) ->
            every { resourceProvider.getString(resourceId) } returns text
        }
    }

    protected fun stubAccessibilityStatusText() {
        clearMocks(resourceProvider, answers = true)
        every { resourceProvider.getString(CoreUiR.string.metric_status_optimal) } returns "Optimal"
        every { resourceProvider.getString(CoreUiR.string.metric_status_neutral) } returns "Neutral"
        every { resourceProvider.getString(CoreUiR.string.metric_status_warning) } returns "Warning"
        every { resourceProvider.getString(CoreUiR.string.metric_status_poor) } returns "Poor"
        every { resourceProvider.getString(CoreUiR.string.metric_status_calibrating) } returns "Calibrating"

        every {
            resourceProvider.getString(DashboardR.string.semantics_score_format, *anyVararg())
        } answers { invocation.formattedArguments() }
        every {
            resourceProvider.getString(DashboardR.string.semantics_goal_status_format, *anyVararg())
        } answers { invocation.formattedArguments() }
        every {
            resourceProvider.getString(DashboardR.string.semantics_goal_above_target_status_format, *anyVararg())
        } answers {
            val formatArgs = invocation.formatArguments()
            assertEquals(3, formatArgs.size)
            "${formatArgs[0]}: ${formatArgs[1]}, above target, ${formatArgs[2]}"
        }
        every {
            resourceProvider.getString(DashboardR.string.semantics_value_note_format, *anyVararg())
        } answers { invocation.formattedArguments() }
        every {
            resourceProvider.getString(DashboardR.string.semantics_value_note_status_format, *anyVararg())
        } answers { invocation.formattedArguments() }
        every {
            resourceProvider.getString(DashboardR.string.semantics_weight_bmi_format, *anyVararg())
        } answers { invocation.formattedArguments() }
    }

    protected fun statusText(status: MetricStatus): String =
        when (status) {
            MetricStatus.OPTIMAL -> "Optimal"
            MetricStatus.NEUTRAL -> "Neutral"
            MetricStatus.WARNING -> "Warning"
            MetricStatus.POOR -> "Poor"
            MetricStatus.NO_DATA,
            MetricStatus.CALIBRATING,
            -> "Calibrating"
        }
}

internal fun UniversalMetricVisual.unavailableReasonOrNull(): UniversalMetricUnavailableReason? =
    when (this) {
        is UniversalMetricVisual.Score -> unavailableReason
        is UniversalMetricVisual.Goal -> unavailableReason
        is UniversalMetricVisual.PersonalBaseline -> unavailableReason
        is UniversalMetricVisual.ReferenceRange -> unavailableReason
        UniversalMetricVisual.ValueOnly -> null
    }

internal fun io.mockk.Invocation.formattedArguments(): String = formatArguments().joinToString("|")

internal fun io.mockk.Invocation.formatArguments(): List<Any?> =
    args
        .drop(1)
        .flatMap { argument -> if (argument is Array<*>) argument.asList() else listOf(argument) }
