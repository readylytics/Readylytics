package app.readylytics.health.feature.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardMetricCardAccessibilityTest : DashboardMetricCardTestBase() {
    @Test
    fun restingHrSemanticsCommunicateValueAndPersonalRangeRelationship() {
        val title = string(R.string.card_title_resting_hr)
        val relation = string(R.string.personal_baseline_within_range_description)
        val expectedDescription = string(R.string.semantics_value_note_format, title, "60 bpm", relation)

        val presentation =
            defaultPresentation.copy(
                title = title,
                valueText = "60",
                unitText = string(app.readylytics.health.core.ui.R.string.unit_bpm),
                accessibilityDescription = expectedDescription,
                visual =
                    DashboardMetricScalePreparer.personalBaseline(
                        value = 60f,
                        baseline = 60f,
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
    fun rasSemanticsCommunicateValueDenominatorAndClassification() {
        val classification = string(app.readylytics.health.core.ui.R.string.metric_status_optimal)
        val title = string(R.string.card_title_ras_daily)
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
    fun sleepEfficiencySemanticsCommunicateValueAndCategory() {
        val category = string(app.readylytics.health.core.ui.R.string.metric_status_optimal)
        val title = string(app.readylytics.health.core.ui.R.string.card_title_sleep_efficiency)
        val expectedDescription = string(R.string.semantics_value_note_format, title, "88%", category)

        val presentation =
            defaultPresentation.copy(
                title = title,
                valueText = "88",
                unitText = "%",
                accessibilityDescription = expectedDescription,
                visual = DashboardMetricScalePreparer.score(88f, 0f, 100f),
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
    fun spo2SemanticsCommunicateValueAndCategory() {
        val category = string(app.readylytics.health.core.ui.R.string.metric_status_optimal)
        val title = string(R.string.card_title_oxygen_saturation)
        val expectedDescription = string(R.string.semantics_value_note_format, title, "98%", category)

        val presentation =
            defaultPresentation.copy(
                title = title,
                valueText = "98",
                unitText = "%",
                accessibilityDescription = expectedDescription,
                visual = DashboardMetricScalePreparer.score(98f, 80f, 100f),
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
    fun bloodPressureSemanticsCommunicateValueAndCategory() {
        val category = string(app.readylytics.health.core.ui.R.string.metric_status_optimal)
        val title = string(R.string.card_title_blood_pressure)
        val expectedDescription = string(R.string.semantics_value_note_format, title, "120/80 mmHg", category)

        val presentation =
            defaultPresentation.copy(
                title = title,
                valueText = "120/80",
                unitText = "mmHg",
                accessibilityDescription = expectedDescription,
                visual = UniversalMetricVisual.ValueOnly,
            )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = presentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.VALUE,
                isEditing = false,
                onModeSelected = {},
            )
        }

        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun heartRateSemanticsCommunicateValueAndCategory() {
        val category = string(app.readylytics.health.core.ui.R.string.metric_status_neutral)
        val title = string(R.string.card_title_heart_rate)
        val expectedDescription = string(R.string.semantics_value_note_format, title, "72 bpm", category)

        val presentation =
            defaultPresentation.copy(
                title = title,
                valueText = "72",
                unitText = "bpm",
                accessibilityDescription = expectedDescription,
                visual = UniversalMetricVisual.ValueOnly,
            )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = presentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.VALUE,
                isEditing = false,
                onModeSelected = {},
            )
        }

        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun circadianSemanticsCommunicateValueDenominatorAndClassification() {
        val classification = string(app.readylytics.health.core.ui.R.string.metric_status_optimal)
        val title = string(R.string.card_title_circadian_consistency)
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
    fun strainRatioSemanticsCommunicateValueAndCategory() {
        val category = string(app.readylytics.health.core.ui.R.string.metric_status_optimal)
        val title = string(app.readylytics.health.core.ui.R.string.card_title_strain_ratio)
        val expectedDescription = string(R.string.semantics_value_note_format, title, "1.10", category)

        val presentation =
            defaultPresentation.copy(
                title = title,
                valueText = "1.10",
                accessibilityDescription = expectedDescription,
                visual = DashboardMetricScalePreparer.score(1.1f, 0f, 2f),
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
    fun goalAboveTargetSemanticsCommunicateAboveTargetState() {
        val classification = string(app.readylytics.health.core.ui.R.string.metric_status_optimal)
        val title = string(R.string.card_title_sleep_duration)
        val expectedDescription = string(R.string.semantics_goal_above_target_format, title, "520", classification)

        val goalVisual = DashboardMetricScalePreparer.goal(520f, 480f)
        check(goalVisual.isAboveTarget) { "Fixture must exercise the above-target branch" }

        val presentation =
            defaultPresentation.copy(
                title = title,
                valueText = "520",
                accessibilityDescription = expectedDescription,
                visual = goalVisual,
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
    fun goalMissingTargetSemanticsCommunicateUnavailable() {
        val title = string(R.string.card_title_sleep_duration)
        val reason = string(app.readylytics.health.core.ui.R.string.metric_unavailable_missing_target)
        val expectedDescription = string(R.string.semantics_unavailable_format, title, reason)

        val presentation =
            defaultPresentation.copy(
                title = title,
                valueText = "—",
                accessibilityDescription = expectedDescription,
                visual = DashboardMetricScalePreparer.goal(null, null),
            )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = presentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.VALUE,
                isEditing = false,
                onModeSelected = {},
            )
        }

        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun personalBaselineNotReadySemanticsCommunicateUnavailable() {
        val title = string(R.string.card_title_hrv)
        val reason = string(app.readylytics.health.core.ui.R.string.metric_unavailable_baseline_not_ready)
        val expectedDescription = string(R.string.semantics_unavailable_format, title, reason)

        val presentation =
            defaultPresentation.copy(
                title = title,
                valueText = "—",
                accessibilityDescription = expectedDescription,
                visual =
                    DashboardMetricScalePreparer.personalBaseline(
                        value = null,
                        baseline = null,
                        axisMinimumRatio = 0.5f,
                        axisMaximumRatio = 1.5f,
                        baselineReady = false,
                    ),
            )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = presentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.VALUE,
                isEditing = false,
                onModeSelected = {},
            )
        }

        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun informationAction_isSeparatelyReachableAndShowsTooltip() {
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
            .onNodeWithContentDescription(
                string(app.readylytics.health.core.ui.R.string.accessibility_more_information),
            ).assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText(defaultPresentation.tooltip).assertIsDisplayed()
    }

    @Test
    fun modeSelectorItemSemanticsCommunicateVisualizationStyleAndSelectedState() {
        var selectedMode by mutableStateOf(DashboardCardDisplayMode.BAR)

        composeRule.setContent {
            DashboardMetricCard(
                presentation = defaultPresentation,
                specification = testSpec,
                requestedMode = selectedMode,
                isEditing = true,
                onModeSelected = { selectedMode = it },
            )
        }

        composeRule
            .onNodeWithContentDescription(string(R.string.menu_content_description_visualization_style))
            .performClick()

        val selectedDescription = string(R.string.menu_item_description_mode_selected, string(R.string.mode_bar))
        val unselectedDescription = string(R.string.menu_item_description_mode, string(R.string.mode_gauge))

        composeRule.onNodeWithContentDescription(selectedDescription).assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithContentDescription(unselectedDescription).assertIsDisplayed()
    }

    @Test
    fun modeSelectorMeetsMinimumTouchTargetSize() {
        composeRule.setContent {
            DashboardMetricCard(
                presentation = defaultPresentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.GAUGE,
                isEditing = true,
                onModeSelected = {},
            )
        }

        // Compose UI test in this project's Compose BOM does not expose
        // assertTouchWidthIsAtLeast/assertTouchHeightIsAtLeast (only *IsEqualTo variants exist
        // for touch bounds); assertWidthIsAtLeast/assertHeightIsAtLeast are the closest available
        // equivalents and are exact here since the selector's IconButton is a fixed 48.dp box
        // with no extra touch-target padding, so its layout bounds equal its touch bounds.
        composeRule
            .onNodeWithContentDescription(string(R.string.menu_content_description_visualization_style))
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }
}
