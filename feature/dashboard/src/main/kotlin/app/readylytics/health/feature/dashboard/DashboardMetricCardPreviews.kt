package app.readylytics.health.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.readylytics.health.core.designsystem.FitDashboardTheme
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCard
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricScalePreparer

// Compose Preview fixtures for DashboardMetricCard, covering every visual/render-mode combination
// the Task 10 brief calls out for visual accessibility review in Android Studio. These carry no
// assertions of their own (see DashboardMetricCardTest for the automated semantics coverage) and
// build on the same UniversalMetricScalePreparer helpers production code uses, so the fractions
// drawn here match how the real renderers would compute them.
//
// Titles below are pulled from real string resources (via stringResource) so previews reflect
// actual localized labels; body copy that only exists for this fixture (tooltip/accessibility
// text) is a plain preview-only literal, matching the fixture convention already established in
// DashboardMetricCardTest.kt's `defaultPresentation`.

private val sleepScoreSpec = DashboardCardCatalog.spec(CardId.SLEEP_SCORE)!!
private val sleepDurationSpec = DashboardCardCatalog.spec(CardId.SLEEP_DURATION)!!
private val hrvSpec = DashboardCardCatalog.spec(CardId.HRV)!!
private val weightSpec = DashboardCardCatalog.spec(CardId.WEIGHT)!!

@Composable
private fun sleepScorePresentation(): UniversalMetricPresentation =
    UniversalMetricPresentation(
        title = stringResource(R.string.card_title_sleep_score),
        valueText = "82",
        unitText = "",
        secondaryText = "Optimal",
        status = MetricStatus.OPTIMAL,
        tooltip = "Total quality of rest based on duration and cycles.",
        accessibilityDescription = "Sleep score: 82 of 100, Optimal",
        visual = UniversalMetricScalePreparer.score(82f, 0f, 100f),
    )

@Composable
private fun goalAboveTargetPresentation(): UniversalMetricPresentation {
    val visual = UniversalMetricScalePreparer.goal(520f, 480f)
    return UniversalMetricPresentation(
        title = stringResource(R.string.card_title_sleep_duration),
        valueText = "8h 40m",
        unitText = "",
        secondaryText = null,
        status = MetricStatus.OPTIMAL,
        tooltip = "Total time asleep last night.",
        accessibilityDescription =
            "${stringResource(R.string.card_title_sleep_duration)}: 8h 40m, " +
                stringResource(R.string.goal_above_target_description),
        visual = visual,
    )
}

@Composable
private fun baselineWithinRangePresentation(): UniversalMetricPresentation {
    val visual =
        UniversalMetricScalePreparer.personalBaseline(
            value = 62f,
            baseline = 60f,
            axisMinimumRatio = 0.5f,
            axisMaximumRatio = 1.5f,
            baselineReady = true,
        )
    return UniversalMetricPresentation(
        title = stringResource(R.string.card_title_hrv),
        valueText = "62",
        unitText = stringResource(app.readylytics.health.core.ui.R.string.unit_ms),
        secondaryText = null,
        status = MetricStatus.OPTIMAL,
        tooltip = "Variation between heartbeats in milliseconds.",
        accessibilityDescription =
            "${stringResource(R.string.card_title_hrv)}: 62 ms, " +
                stringResource(R.string.personal_baseline_within_range_description),
        visual = visual,
    )
}

@Composable
private fun weightReferenceRangePresentation(): UniversalMetricPresentation {
    val visual =
        UniversalMetricScalePreparer.referenceRange(
            value = 21.7f,
            minimum = 15f,
            midpoint = 21.7f,
            maximum = 35f,
            scaleAvailable = true,
            unavailableReason = null,
        )
    val bmiSecondary = stringResource(app.readylytics.health.core.ui.R.string.bmi_secondary_text, "21.7")
    return UniversalMetricPresentation(
        title = stringResource(R.string.card_title_weight),
        valueText = "70",
        unitText = stringResource(R.string.unit_metric_kg),
        secondaryText = bmiSecondary,
        status = MetricStatus.OPTIMAL,
        tooltip = "Latest weight measurement.",
        accessibilityDescription =
            "${stringResource(R.string.card_title_weight)}: 70 kg, $bmiSecondary, " +
                stringResource(app.readylytics.health.core.ui.R.string.metric_status_optimal),
        visual = visual,
    )
}

@Composable
private fun goalUnavailablePresentation(): UniversalMetricPresentation {
    val visual = UniversalMetricScalePreparer.goal(null, null)
    val reason = stringResource(app.readylytics.health.core.ui.R.string.metric_unavailable_missing_target)
    return UniversalMetricPresentation(
        title = stringResource(R.string.card_title_sleep_duration),
        valueText = "—",
        unitText = "",
        secondaryText = null,
        status = MetricStatus.NEUTRAL,
        tooltip = "Total time asleep last night.",
        accessibilityDescription = "${stringResource(R.string.card_title_sleep_duration)}: $reason",
        visual = visual,
    )
}

@Preview(showBackground = true)
@Composable
private fun DashboardScoreGaugePreview() {
    FitDashboardTheme {
        UniversalMetricCard(
            presentation = sleepScorePresentation(),
            specification = sleepScoreSpec.toUniversalSpec(),
            requestedMode = DashboardCardDisplayMode.GAUGE.toUniversalMode(),
            isEditing = false,
            onModeSelected = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DashboardScoreBarPreview() {
    FitDashboardTheme {
        UniversalMetricCard(
            presentation = sleepScorePresentation(),
            specification = sleepScoreSpec.toUniversalSpec(),
            requestedMode = DashboardCardDisplayMode.BAR.toUniversalMode(),
            isEditing = false,
            onModeSelected = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DashboardScoreValuePreview() {
    FitDashboardTheme {
        UniversalMetricCard(
            presentation = sleepScorePresentation(),
            specification = sleepScoreSpec.toUniversalSpec(),
            requestedMode = DashboardCardDisplayMode.VALUE.toUniversalMode(),
            isEditing = false,
            onModeSelected = {},
        )
    }
}

@Preview(showBackground = true, name = "Goal Bar - above target")
@Composable
private fun DashboardGoalAboveTargetBarPreview() {
    FitDashboardTheme {
        UniversalMetricCard(
            presentation = goalAboveTargetPresentation(),
            specification = sleepDurationSpec.toUniversalSpec(),
            requestedMode = DashboardCardDisplayMode.BAR.toUniversalMode(),
            isEditing = false,
            onModeSelected = {},
        )
    }
}

@Preview(showBackground = true, name = "Baseline Gauge - within range")
@Composable
private fun DashboardBaselineWithinRangeGaugePreview() {
    FitDashboardTheme {
        UniversalMetricCard(
            presentation = baselineWithinRangePresentation(),
            specification = hrvSpec.toUniversalSpec(),
            requestedMode = DashboardCardDisplayMode.GAUGE.toUniversalMode(),
            isEditing = false,
            onModeSelected = {},
        )
    }
}

@Preview(showBackground = true, name = "Weight Bar - reference range at BMI 21.7")
@Composable
private fun DashboardWeightReferenceRangeBarPreview() {
    FitDashboardTheme {
        UniversalMetricCard(
            presentation = weightReferenceRangePresentation(),
            specification = weightSpec.toUniversalSpec(),
            requestedMode = DashboardCardDisplayMode.BAR.toUniversalMode(),
            isEditing = false,
            onModeSelected = {},
        )
    }
}

@Preview(showBackground = true, name = "Goal Bar - unavailable (grey)")
@Composable
private fun DashboardGoalUnavailableBarPreview() {
    FitDashboardTheme {
        UniversalMetricCard(
            presentation = goalUnavailablePresentation(),
            specification = sleepDurationSpec.toUniversalSpec(),
            requestedMode = DashboardCardDisplayMode.BAR.toUniversalMode(),
            isEditing = false,
            onModeSelected = {},
        )
    }
}

@Preview(showBackground = true, name = "Edit mode - openable selector control")
@Composable
private fun DashboardEditModeSelectorPreview() {
    FitDashboardTheme {
        var mode by remember { mutableStateOf(DashboardCardDisplayMode.GAUGE) }
        UniversalMetricCard(
            presentation = sleepScorePresentation(),
            specification = sleepScoreSpec.toUniversalSpec(),
            requestedMode = mode.toUniversalMode(),
            isEditing = true,
            onModeSelected = { mode = it.toDashboardMode() },
        )
    }
}

@Preview(showBackground = true, name = "Selector/card - default font scale")
@Composable
private fun DashboardCardFontScaleDefaultPreview() {
    FitDashboardTheme {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            UniversalMetricCard(
                presentation = sleepScorePresentation(),
                specification = sleepScoreSpec.toUniversalSpec(),
                requestedMode = DashboardCardDisplayMode.GAUGE.toUniversalMode(),
                isEditing = true,
                onModeSelected = {},
            )
        }
    }
}

@Preview(showBackground = true, fontScale = 1.5f, name = "Selector/card - fontScale 1.5x")
@Composable
private fun DashboardCardFontScaleLargePreview() {
    FitDashboardTheme {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            UniversalMetricCard(
                presentation = sleepScorePresentation(),
                specification = sleepScoreSpec.toUniversalSpec(),
                requestedMode = DashboardCardDisplayMode.GAUGE.toUniversalMode(),
                isEditing = true,
                onModeSelected = {},
            )
        }
    }
}
