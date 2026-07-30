package app.readylytics.health.feature.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
}
