package app.readylytics.health.feature.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricUnavailableReason
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardMetricCardModesTest : DashboardMetricCardTestBase() {
    @Test
    fun menuVisibilityInModes() {
        composeRule.setContent {
            DashboardMetricCard(
                presentation = defaultPresentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.GAUGE,
                isEditing = false,
                onModeSelected = {},
            )
        }

        composeRule
            .onNodeWithContentDescription("Change visualization style")
            .assertDoesNotExist()
    }

    @Test
    fun menuItemsAndDisabledStateForMissingTarget() {
        var selectedMode by mutableStateOf(DashboardCardDisplayMode.GAUGE)

        val missingTargetPresentation =
            defaultPresentation.copy(
                visual =
                    UniversalMetricVisual.Goal(
                        rawValue = 50f,
                        targetValue = null,
                        markerFraction = 0.5f,
                        targetMarkerFraction = null,
                        isAboveTarget = false,
                        selectionAvailable = false,
                        unavailableReason = UniversalMetricUnavailableReason.MISSING_TARGET,
                    ),
            )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = missingTargetPresentation,
                specification = testSpec,
                requestedMode = selectedMode,
                isEditing = true,
                onModeSelected = { selectedMode = it },
            )
        }

        composeRule.onNodeWithContentDescription("Change visualization style").performClick()

        // Assert only catalog-supported localized labels appear
        composeRule
            .onNodeWithText("Gauge")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assertIsSelected()
        composeRule.onNodeWithText("Bar").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("Value").assertIsDisplayed()
    }

    @Test
    fun unavailableRendererRetainsValueText() {
        val missingTargetPresentation =
            defaultPresentation.copy(
                visual =
                    UniversalMetricVisual.Goal(
                        rawValue = 50f,
                        targetValue = null,
                        markerFraction = 0.5f,
                        targetMarkerFraction = null,
                        isAboveTarget = false,
                        selectionAvailable = false,
                        unavailableReason = UniversalMetricUnavailableReason.MISSING_TARGET,
                    ),
            )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = missingTargetPresentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.GAUGE,
                isEditing = false,
                onModeSelected = {},
            )
        }

        composeRule.onNodeWithText("85").assertIsDisplayed()
    }

    @Test
    fun valueRendererShowsContext() {
        composeRule.setContent {
            DashboardMetricCard(
                presentation = defaultPresentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.VALUE,
                isEditing = false,
                onModeSelected = {},
            )
        }

        composeRule.onNodeWithText("85").assertIsDisplayed()
        composeRule.onNodeWithText("pts").assertIsDisplayed()
        composeRule.onNodeWithText("Good").assertIsDisplayed()
    }

    @Test
    fun scoreVisualKeepsAllModesSelectableEvenWhenValueIsMissing() {
        // Unlike Goal/PersonalBaseline/ReferenceRange, a Score visual has no
        // selectionAvailable field: DashboardMetricCard treats it as always
        // selectable, even when its unavailableReason is set for a missing value.
        var selectedMode by mutableStateOf(DashboardCardDisplayMode.GAUGE)

        val missingScorePresentation =
            defaultPresentation.copy(
                valueText = "—",
                visual =
                    UniversalMetricVisual.Score(
                        rawValue = null,
                        minValue = 0f,
                        maxValue = 100f,
                        markerFraction = null,
                        unavailableReason = UniversalMetricUnavailableReason.MISSING_VALUE,
                    ),
            )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = missingScorePresentation,
                specification = testSpec,
                requestedMode = selectedMode,
                isEditing = true,
                onModeSelected = { selectedMode = it },
            )
        }

        composeRule.onNodeWithText("—").assertIsDisplayed()
        composeRule.onNodeWithText("0").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Change visualization style").performClick()

        composeRule
            .onNodeWithText("Gauge")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertIsSelected()
        composeRule.onNodeWithText("Bar").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("Value").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun personalBaselineNotReadyDisablesGaugeAndBarButPreservesSelection() {
        var selectedMode by mutableStateOf(DashboardCardDisplayMode.VALUE)

        val notReadyPresentation =
            defaultPresentation.copy(
                visual =
                    UniversalMetricVisual.PersonalBaseline(
                        rawValue = 45f,
                        baselineValue = null,
                        ratio = null,
                        markerFraction = null,
                        baselineMarkerFraction = 0f,
                        selectionAvailable = false,
                        unavailableReason = UniversalMetricUnavailableReason.BASELINE_NOT_READY,
                    ),
            )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = notReadyPresentation,
                specification = testSpec,
                requestedMode = selectedMode,
                isEditing = true,
                onModeSelected = { selectedMode = it },
            )
        }

        composeRule.onNodeWithContentDescription("Change visualization style").performClick()

        composeRule.onNodeWithText("Gauge").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("Bar").assertIsDisplayed().assertIsNotEnabled()
        composeRule
            .onNodeWithText("Value")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertIsSelected()
    }

    @Test
    fun referenceRangeUnavailableDisablesGaugeAndBarButKeepsRealValueVisible() {
        var selectedMode by mutableStateOf(DashboardCardDisplayMode.GAUGE)

        val missingBmiPresentation =
            defaultPresentation.copy(
                valueText = "70",
                visual =
                    UniversalMetricVisual.ReferenceRange(
                        rawValue = null,
                        markerFraction = null,
                        referenceMarkerFraction = null,
                        selectionAvailable = false,
                        unavailableReason = UniversalMetricUnavailableReason.MISSING_BMI,
                    ),
            )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = missingBmiPresentation,
                specification = testSpec,
                requestedMode = selectedMode,
                isEditing = true,
                onModeSelected = { selectedMode = it },
            )
        }

        composeRule.onNodeWithText("70").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Change visualization style").performClick()

        composeRule
            .onNodeWithText("Gauge")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assertIsSelected()
        composeRule.onNodeWithText("Bar").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("Value").assertIsDisplayed().assertIsEnabled()
    }

    // -------------------------------------------------------------------------
    // Task 10: accessibility semantics regression coverage.
    //
    // DashboardMetricCard only forwards presentation.accessibilityDescription verbatim into a
    // single merged contentDescription (see DashboardMetricCard.kt); these fixtures assemble
    // that description from real, localized resource strings (never a hardcoded literal for a
    // word this feature introduced/touched) to prove the shell threads it through correctly for
    // each visual type the brief calls out.
    // -------------------------------------------------------------------------

    @Test
    fun sleepScoreSemanticsCommunicateValueDenominatorAndClassification() {
        val classification = string(app.readylytics.health.core.ui.R.string.metric_status_optimal)
        val title = string(R.string.card_title_sleep_score)
        val expectedDescription = string(R.string.semantics_score_format, title, "85", "100", classification)

        val presentation =
            defaultPresentation.copy(
                title = title,
                valueText = "85",
                accessibilityDescription = expectedDescription,
                visual = DashboardMetricScalePreparer.score(85f, 0f, 100f),
            )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = presentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.GAUGE,
                isEditing = false,
                onModeSelected = {},
            )
        }

        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun sleepDurationSemanticsCommunicateValueAndTarget() {
        val classification = string(app.readylytics.health.core.ui.R.string.metric_status_optimal)
        val title = string(R.string.card_title_sleep_duration)
        val expectedDescription = string(R.string.semantics_goal_format, title, "7h 30m", "8h 0m", classification)

        val presentation =
            defaultPresentation.copy(
                title = title,
                valueText = "7h 30m",
                accessibilityDescription = expectedDescription,
                visual = DashboardMetricScalePreparer.goal(450f, 480f),
            )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = presentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.GAUGE,
                isEditing = false,
                onModeSelected = {},
            )
        }

        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun hrvSemanticsCommunicateValueAndPersonalRangeRelationship() {
        val title = string(R.string.card_title_hrv)
        val relation = string(R.string.personal_baseline_within_range_description)
        val expectedDescription = string(R.string.semantics_value_note_format, title, "55 ms", relation)

        val presentation =
            defaultPresentation.copy(
                title = title,
                valueText = "55",
                unitText = string(app.readylytics.health.core.ui.R.string.unit_ms),
                accessibilityDescription = expectedDescription,
                visual =
                    DashboardMetricScalePreparer.personalBaseline(
                        value = 55f,
                        baseline = 50f,
                        axisMinimumRatio = 0.5f,
                        axisMaximumRatio = 1.5f,
                        baselineReady = true,
                    ),
            )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = presentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.GAUGE,
                isEditing = false,
                onModeSelected = {},
            )
        }

        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun weightSemanticsCommunicateValueBmiAndCategory() {
        val title = string(R.string.card_title_weight)
        val bmiCategory = string(app.readylytics.health.core.ui.R.string.metric_status_optimal)
        val bmiSecondary = string(app.readylytics.health.core.ui.R.string.bmi_secondary_text, "21.7")
        val expectedDescription =
            string(R.string.semantics_weight_bmi_format, title, "70 kg", bmiSecondary, bmiCategory)

        val presentation =
            defaultPresentation.copy(
                title = title,
                valueText = "70",
                unitText = "kg",
                secondaryText = bmiSecondary,
                accessibilityDescription = expectedDescription,
                visual =
                    DashboardMetricScalePreparer.referenceRange(
                        value = 21.7f,
                        minimum = 15f,
                        midpoint = 21.7f,
                        maximum = 35f,
                        scaleAvailable = true,
                        unavailableReason = null,
                    ),
            )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = presentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.GAUGE,
                isEditing = false,
                onModeSelected = {},
            )
        }

        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun bodyFatSemanticsCommunicateValueAndCategory() {
        val title = string(R.string.card_title_body_fat)
        val category = string(app.readylytics.health.core.ui.R.string.metric_status_optimal)
        val expectedDescription = string(R.string.semantics_value_note_format, title, "20%", category)

        val presentation =
            defaultPresentation.copy(
                title = title,
                valueText = "20",
                unitText = "%",
                accessibilityDescription = expectedDescription,
                visual =
                    DashboardMetricScalePreparer.referenceRange(
                        value = 20f,
                        minimum = 10f,
                        midpoint = 20f,
                        maximum = 30f,
                        scaleAvailable = true,
                        unavailableReason = null,
                    ),
            )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = presentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.GAUGE,
                isEditing = false,
                onModeSelected = {},
            )
        }

        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun readinessSemanticsCommunicateValueDenominatorAndClassification() {
        val classification = string(app.readylytics.health.core.ui.R.string.metric_status_optimal)
        val title = string(app.readylytics.health.core.ui.R.string.card_title_readiness)
        val expectedDescription = string(R.string.semantics_score_format, title, "80", "100", classification)

        val presentation =
            defaultPresentation.copy(
                title = title,
                valueText = "80",
                accessibilityDescription = expectedDescription,
                visual = DashboardMetricScalePreparer.score(80f, 0f, 100f),
            )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = presentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.GAUGE,
                isEditing = false,
                onModeSelected = {},
            )
        }

        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun sleepRhrSemanticsCommunicateValueAndPersonalRangeRelationship() {
        val title = string(R.string.card_title_sleep_rhr)
        val relation = string(R.string.personal_baseline_within_range_description)
        val expectedDescription = string(R.string.semantics_value_note_format, title, "55 bpm", relation)

        val presentation =
            defaultPresentation.copy(
                title = title,
                valueText = "55",
                unitText = string(app.readylytics.health.core.ui.R.string.unit_bpm),
                accessibilityDescription = expectedDescription,
                visual =
                    DashboardMetricScalePreparer.personalBaseline(
                        value = 55f,
                        baseline = 55f,
                        axisMinimumRatio = 0.5f,
                        axisMaximumRatio = 1.5f,
                        baselineReady = true,
                    ),
            )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = presentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.GAUGE,
                isEditing = false,
                onModeSelected = {},
            )
        }

        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }
}
