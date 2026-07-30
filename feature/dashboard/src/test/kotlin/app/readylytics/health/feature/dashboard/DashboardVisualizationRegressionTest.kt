package app.readylytics.health.feature.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.FitDashboardTheme
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.dashboard.DashboardCardSpec
import app.readylytics.health.domain.model.MetricStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DashboardVisualizationRegressionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val specification =
        DashboardCardSpec(
            cardId = CardId.HRV,
            legacyDefaultMode = DashboardCardDisplayMode.VALUE,
            supportedModes = DashboardCardDisplayMode.entries,
        )

    private val presentation =
        DashboardMetricPresentation(
            title = "Metric",
            valueText = "0",
            unitText = "",
            secondaryText = null,
            status = MetricStatus.NEUTRAL,
            tooltip = "Metric context",
            accessibilityDescription = "Metric value, normal.",
            visual =
                DashboardMetricVisual.Score(
                    rawValue = 0f,
                    minValue = 0f,
                    maxValue = 100f,
                    markerFraction = 0f,
                    bands = emptyList(),
                    unavailableReason = null,
                ),
        )

    @Test
    fun valueMode_showsLargeValueUnitAndSecondary_withoutVisualizationOrVisibleStatus() {
        setMetricCard(
            mode = DashboardCardDisplayMode.VALUE,
            presentation =
                presentation.copy(
                    title = "HRV",
                    valueText = "41",
                    unitText = "ms",
                    secondaryText = "22:51 → 06:02",
                    accessibilityDescription = "HRV 41 milliseconds, normal.",
                ),
        )

        composeRule.onNodeWithText("41").assertIsDisplayed()
        composeRule.onNodeWithText("ms").assertIsDisplayed()
        composeRule.onNodeWithText("22:51 → 06:02").assertIsDisplayed()
        composeRule.onNodeWithText("Normal").assertDoesNotExist()
        composeRule.onNodeWithTag(DASHBOARD_GAUGE_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(DASHBOARD_BAR_TAG).assertDoesNotExist()
    }

    @Test
    fun gaugeMode_showsValueUnitAndDelta_withoutVisibleStatus() {
        setMetricCard(
            mode = DashboardCardDisplayMode.GAUGE,
            presentation =
                presentation.copy(
                    valueText = "41",
                    unitText = "ms",
                    secondaryText = "↓ 2",
                    accessibilityDescription = "HRV 41 milliseconds, normal.",
                ),
        )

        composeRule.onNodeWithTag(DASHBOARD_GAUGE_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("41").assertIsDisplayed()
        composeRule.onNodeWithText("ms").assertIsDisplayed()
        composeRule.onNodeWithText("↓ 2").assertIsDisplayed()
        composeRule.onNodeWithText("Normal").assertDoesNotExist()
    }

    @Test
    fun progressFraction_returnsEachNormalizedVisualMarkerFraction() {
        val visuals =
            listOf(
                DashboardMetricVisual.Score(
                    rawValue = 12f,
                    minValue = 0f,
                    maxValue = 100f,
                    markerFraction = 0.12f,
                    bands = emptyList(),
                    unavailableReason = null,
                ) to 0.12f,
                DashboardMetricVisual.Goal(
                    rawValue = 34f,
                    targetValue = 100f,
                    markerFraction = 0.34f,
                    targetMarkerFraction = 1f,
                    isAboveTarget = false,
                    bands = emptyList(),
                    selectionAvailable = true,
                    unavailableReason = null,
                ) to 0.34f,
                DashboardMetricVisual.PersonalBaseline(
                    rawValue = 56f,
                    baselineValue = 50f,
                    ratio = 1.12f,
                    markerFraction = 0.56f,
                    baselineMarkerFraction = 0.5f,
                    bands = emptyList(),
                    selectionAvailable = true,
                    unavailableReason = null,
                ) to 0.56f,
                DashboardMetricVisual.ReferenceRange(
                    rawValue = 78f,
                    markerFraction = 0.78f,
                    referenceMarkerFraction = 0.5f,
                    bands = emptyList(),
                    selectionAvailable = true,
                    unavailableReason = null,
                ) to 0.78f,
            )

        visuals.forEach { (visual, expectedFraction) ->
            assertEquals(expectedFraction, visual.progressFraction())
        }
    }

    @Test
    fun allModes_keepOriginalCardHeight() {
        var mode by mutableStateOf(DashboardCardDisplayMode.VALUE)
        composeRule.setContent {
            TestTheme {
                DashboardMetricCard(
                    presentation = presentation,
                    specification = specification,
                    requestedMode = mode,
                    renderMode = mode,
                    isEditing = false,
                    onModeSelected = {},
                )
            }
        }

        DashboardCardDisplayMode.entries.forEach { newMode ->
            composeRule.runOnIdle { mode = newMode }
            composeRule.onNodeWithTag(DASHBOARD_METRIC_CARD_TAG).assertHeightIsEqualTo(156.dp)
        }
    }

    private fun setMetricCard(
        mode: DashboardCardDisplayMode,
        presentation: DashboardMetricPresentation,
    ) {
        composeRule.setContent {
            TestTheme {
                DashboardMetricCard(
                    presentation = presentation,
                    specification = specification,
                    requestedMode = mode,
                    renderMode = mode,
                    isEditing = false,
                    onModeSelected = {},
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun TestTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    FitDashboardTheme(dynamicColor = false, content = content)
}
