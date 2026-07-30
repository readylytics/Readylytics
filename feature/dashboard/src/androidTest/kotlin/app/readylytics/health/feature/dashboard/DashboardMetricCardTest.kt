package app.readylytics.health.feature.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.dashboard.DashboardCardSpec
import app.readylytics.health.domain.model.MetricStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardMetricCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    // Resource-backed string lookups (rather than hardcoded literals) so semantics assertions
    // stay correct if wording is localized/changed, matching the pattern used by
    // core/ui's DateSwitcherTest.
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun string(id: Int): String = context.getString(id)

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private val testSpec = DashboardCardSpec(
        cardId = CardId.SLEEP_SCORE,
        legacyDefaultMode = DashboardCardDisplayMode.GAUGE,
        supportedModes = listOf(
            DashboardCardDisplayMode.GAUGE,
            DashboardCardDisplayMode.BAR,
            DashboardCardDisplayMode.VALUE
        )
    )

    private val defaultPresentation = DashboardMetricPresentation(
        title = "Test Metric",
        valueText = "85",
        unitText = "pts",
        secondaryText = "Good",
        status = MetricStatus.OPTIMAL,
        tooltip = "Tooltip text",
        accessibilityDescription = "Card description",
        visual = DashboardMetricVisual.Score(
            rawValue = 85f,
            minValue = 0f,
            maxValue = 100f,
            markerFraction = 0.85f,
            bands = emptyList(),
            unavailableReason = null,
        )
    )

    @Test
    fun menuVisibilityInModes() {
        composeRule.setContent {
            DashboardMetricCard(
                presentation = defaultPresentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.GAUGE,
                renderMode = DashboardCardDisplayMode.GAUGE,
                isEditing = false,
                onModeSelected = {}
            )
        }
        
        composeRule.onNodeWithContentDescription("Change visualization style")
            .assertDoesNotExist()
    }

    @Test
    fun menuItemsAndDisabledStateForMissingTarget() {
        var selectedMode by mutableStateOf(DashboardCardDisplayMode.GAUGE)
        
        val missingTargetPresentation = defaultPresentation.copy(
            visual = DashboardMetricVisual.Goal(
                rawValue = 50f,
                targetValue = null,
                markerFraction = 0.5f,
                targetMarkerFraction = null,
                isAboveTarget = false,
                bands = emptyList(),
                selectionAvailable = false,
                unavailableReason = DashboardMetricUnavailableReason.MISSING_TARGET
            )
        )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = missingTargetPresentation,
                specification = testSpec,
                requestedMode = selectedMode,
                renderMode = selectedMode,
                isEditing = true,
                onModeSelected = { selectedMode = it }
            )
        }
        
        composeRule.onNodeWithContentDescription("Change visualization style").performClick()
        
        // Assert only catalog-supported localized labels appear
        composeRule.onNodeWithText("Gauge").assertIsDisplayed().assertIsNotEnabled().assertIsSelected()
        composeRule.onNodeWithText("Bar").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("Value").assertIsDisplayed()
    }

    @Test
    fun unavailableRendererRetainsValueText() {
        val missingTargetPresentation = defaultPresentation.copy(
            visual = DashboardMetricVisual.Goal(
                rawValue = 50f,
                targetValue = null,
                markerFraction = 0.5f,
                targetMarkerFraction = null,
                isAboveTarget = false,
                bands = emptyList(),
                selectionAvailable = false,
                unavailableReason = DashboardMetricUnavailableReason.MISSING_TARGET
            )
        )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = missingTargetPresentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.GAUGE,
                renderMode = DashboardCardDisplayMode.GAUGE,
                isEditing = false,
                onModeSelected = {}
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
                renderMode = DashboardCardDisplayMode.VALUE,
                isEditing = false,
                onModeSelected = {}
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

        val missingScorePresentation = defaultPresentation.copy(
            valueText = "—",
            visual = DashboardMetricVisual.Score(
                rawValue = null,
                minValue = 0f,
                maxValue = 100f,
                markerFraction = null,
                bands = emptyList(),
                unavailableReason = DashboardMetricUnavailableReason.MISSING_VALUE,
            )
        )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = missingScorePresentation,
                specification = testSpec,
                requestedMode = selectedMode,
                renderMode = selectedMode,
                isEditing = true,
                onModeSelected = { selectedMode = it }
            )
        }

        composeRule.onNodeWithText("—").assertIsDisplayed()
        composeRule.onNodeWithText("0").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Change visualization style").performClick()

        composeRule.onNodeWithText("Gauge").assertIsDisplayed().assertIsEnabled().assertIsSelected()
        composeRule.onNodeWithText("Bar").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("Value").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun personalBaselineNotReadyDisablesGaugeAndBarButPreservesSelection() {
        var selectedMode by mutableStateOf(DashboardCardDisplayMode.VALUE)

        val notReadyPresentation = defaultPresentation.copy(
            visual = DashboardMetricVisual.PersonalBaseline(
                rawValue = 45f,
                baselineValue = null,
                ratio = null,
                markerFraction = null,
                baselineMarkerFraction = 0f,
                bands = emptyList(),
                selectionAvailable = false,
                unavailableReason = DashboardMetricUnavailableReason.BASELINE_NOT_READY,
            )
        )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = notReadyPresentation,
                specification = testSpec,
                requestedMode = selectedMode,
                renderMode = selectedMode,
                isEditing = true,
                onModeSelected = { selectedMode = it }
            )
        }

        composeRule.onNodeWithContentDescription("Change visualization style").performClick()

        composeRule.onNodeWithText("Gauge").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("Bar").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("Value").assertIsDisplayed().assertIsEnabled().assertIsSelected()
    }

    @Test
    fun referenceRangeUnavailableDisablesGaugeAndBarButKeepsRealValueVisible() {
        var selectedMode by mutableStateOf(DashboardCardDisplayMode.GAUGE)

        val missingBmiPresentation = defaultPresentation.copy(
            valueText = "70",
            visual = DashboardMetricVisual.ReferenceRange(
                rawValue = null,
                markerFraction = null,
                referenceMarkerFraction = null,
                bands = emptyList(),
                selectionAvailable = false,
                unavailableReason = DashboardMetricUnavailableReason.MISSING_BMI,
            )
        )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = missingBmiPresentation,
                specification = testSpec,
                requestedMode = selectedMode,
                renderMode = selectedMode,
                isEditing = true,
                onModeSelected = { selectedMode = it }
            )
        }

        composeRule.onNodeWithText("70").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Change visualization style").performClick()

        composeRule.onNodeWithText("Gauge").assertIsDisplayed().assertIsNotEnabled().assertIsSelected()
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
        val expectedDescription = "$title: 85 of 100, $classification"

        val presentation = defaultPresentation.copy(
            title = title,
            valueText = "85",
            accessibilityDescription = expectedDescription,
            visual = DashboardMetricScalePreparer.score(85f, 0f, 100f, emptyList())
        )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = presentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.GAUGE,
                renderMode = DashboardCardDisplayMode.GAUGE,
                isEditing = false,
                onModeSelected = {}
            )
        }

        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun sleepDurationSemanticsCommunicateValueAndTarget() {
        val title = string(R.string.card_title_sleep_duration)
        val expectedDescription = "$title: 7h 30m, target 8h 0m"

        val presentation = defaultPresentation.copy(
            title = title,
            valueText = "7h 30m",
            accessibilityDescription = expectedDescription,
            visual = DashboardMetricScalePreparer.goal(450f, 480f, emptyList())
        )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = presentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.GAUGE,
                renderMode = DashboardCardDisplayMode.GAUGE,
                isEditing = false,
                onModeSelected = {}
            )
        }

        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun hrvSemanticsCommunicateValueAndPersonalRangeRelationship() {
        val title = string(R.string.card_title_hrv)
        val relation = string(R.string.personal_baseline_within_range_description)
        val expectedDescription = "$title: 55 ms, $relation"

        val presentation = defaultPresentation.copy(
            title = title,
            valueText = "55",
            unitText = string(app.readylytics.health.core.ui.R.string.unit_ms),
            accessibilityDescription = expectedDescription,
            visual = DashboardMetricScalePreparer.personalBaseline(
                value = 55f,
                baseline = 50f,
                axisMinimumRatio = 0.5f,
                axisMaximumRatio = 1.5f,
                bands = emptyList(),
                baselineReady = true
            )
        )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = presentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.GAUGE,
                renderMode = DashboardCardDisplayMode.GAUGE,
                isEditing = false,
                onModeSelected = {}
            )
        }

        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun weightSemanticsCommunicateValueBmiAndCategory() {
        val title = string(R.string.card_title_weight)
        val bmiCategory = string(R.string.bmi_optimal)
        val bmiSecondary = string(app.readylytics.health.core.ui.R.string.bmi_secondary_text, "21.7")
        val expectedDescription = "$title: 70 kg, $bmiSecondary, $bmiCategory"

        val presentation = defaultPresentation.copy(
            title = title,
            valueText = "70",
            unitText = "kg",
            secondaryText = bmiSecondary,
            accessibilityDescription = expectedDescription,
            visual = DashboardMetricScalePreparer.referenceRange(
                value = 21.7f,
                minimum = 15f,
                midpoint = 21.7f,
                maximum = 35f,
                bands = emptyList(),
                scaleAvailable = true,
                unavailableReason = null
            )
        )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = presentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.GAUGE,
                renderMode = DashboardCardDisplayMode.GAUGE,
                isEditing = false,
                onModeSelected = {}
            )
        }

        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun bodyFatSemanticsCommunicateValueAndCategory() {
        val title = string(R.string.card_title_body_fat)
        val category = string(app.readylytics.health.core.ui.R.string.metric_status_optimal)
        val expectedDescription = "$title: 20%, $category"

        val presentation = defaultPresentation.copy(
            title = title,
            valueText = "20",
            unitText = "%",
            accessibilityDescription = expectedDescription,
            visual = DashboardMetricScalePreparer.referenceRange(
                value = 20f,
                minimum = 10f,
                midpoint = 20f,
                maximum = 30f,
                bands = emptyList(),
                scaleAvailable = true,
                unavailableReason = null
            )
        )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = presentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.GAUGE,
                renderMode = DashboardCardDisplayMode.GAUGE,
                isEditing = false,
                onModeSelected = {}
            )
        }

        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun goalAboveTargetSemanticsCommunicateAboveTargetState() {
        val title = string(R.string.card_title_sleep_duration)
        val aboveTarget = string(R.string.goal_above_target_description)
        val expectedDescription = "$title: 520, $aboveTarget"

        val goalVisual = DashboardMetricScalePreparer.goal(520f, 480f, emptyList())
        check(goalVisual.isAboveTarget) { "Fixture must exercise the above-target branch" }

        val presentation = defaultPresentation.copy(
            title = title,
            valueText = "520",
            accessibilityDescription = expectedDescription,
            visual = goalVisual
        )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = presentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.GAUGE,
                renderMode = DashboardCardDisplayMode.GAUGE,
                isEditing = false,
                onModeSelected = {}
            )
        }

        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun goalMissingTargetSemanticsCommunicateUnavailable() {
        val title = string(R.string.card_title_sleep_duration)
        val reason = string(app.readylytics.health.core.ui.R.string.metric_unavailable_missing_target)
        val expectedDescription = "$title: $reason"

        val presentation = defaultPresentation.copy(
            title = title,
            valueText = "—",
            accessibilityDescription = expectedDescription,
            visual = DashboardMetricScalePreparer.goal(null, null, emptyList())
        )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = presentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.VALUE,
                renderMode = DashboardCardDisplayMode.VALUE,
                isEditing = false,
                onModeSelected = {}
            )
        }

        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun personalBaselineNotReadySemanticsCommunicateUnavailable() {
        val title = string(R.string.card_title_hrv)
        val reason = string(app.readylytics.health.core.ui.R.string.metric_unavailable_baseline_not_ready)
        val expectedDescription = "$title: $reason"

        val presentation = defaultPresentation.copy(
            title = title,
            valueText = "—",
            accessibilityDescription = expectedDescription,
            visual = DashboardMetricScalePreparer.personalBaseline(
                value = null,
                baseline = null,
                axisMinimumRatio = 0.5f,
                axisMaximumRatio = 1.5f,
                bands = emptyList(),
                baselineReady = false
            )
        )

        composeRule.setContent {
            DashboardMetricCard(
                presentation = presentation,
                specification = testSpec,
                requestedMode = DashboardCardDisplayMode.VALUE,
                renderMode = DashboardCardDisplayMode.VALUE,
                isEditing = false,
                onModeSelected = {}
            )
        }

        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun modeSelectorItemSemanticsCommunicateVisualizationStyleAndSelectedState() {
        var selectedMode by mutableStateOf(DashboardCardDisplayMode.BAR)

        composeRule.setContent {
            DashboardMetricCard(
                presentation = defaultPresentation,
                specification = testSpec,
                requestedMode = selectedMode,
                renderMode = selectedMode,
                isEditing = true,
                onModeSelected = { selectedMode = it }
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
                renderMode = DashboardCardDisplayMode.GAUGE,
                isEditing = true,
                onModeSelected = {}
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
