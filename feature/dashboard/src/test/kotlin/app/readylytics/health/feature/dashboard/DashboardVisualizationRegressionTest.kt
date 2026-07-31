package app.readylytics.health.feature.dashboard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.FitDashboardTheme
import app.readylytics.health.data.preferences.AppTheme
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.dashboard.DashboardCardSpec
import app.readylytics.health.domain.model.MetricStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun representativeMetrics_renderPrimaryValueInEverySupportedMode() {
        val representatives =
            listOf(
                CardId.SLEEP_SCORE to "86",
                CardId.READINESS to "79",
                CardId.HRV to "41",
                CardId.SLEEP_DURATION to "6h 50m",
                CardId.RAS_DAILY to "74",
                CardId.RESTING_HR to "48",
            )
        var cardId by mutableStateOf(representatives.first().first)
        var cardPresentation by mutableStateOf(presentationFor(cardId, representatives.first().second))
        var mode by mutableStateOf(DashboardCardDisplayMode.VALUE)

        composeRule.setContent {
            TestTheme {
                DashboardMetricCard(
                    presentation = cardPresentation,
                    specification = requireNotNull(DashboardCardCatalog.spec(cardId)),
                    requestedMode = mode,
                    renderMode = mode,
                    isEditing = false,
                    onModeSelected = {},
                )
            }
        }

        representatives.forEach { (newCardId, primaryValue) ->
            val supportedModes = requireNotNull(DashboardCardCatalog.spec(newCardId)).supportedModes
            supportedModes.forEach { newMode ->
                composeRule.runOnIdle {
                    cardId = newCardId
                    cardPresentation = presentationFor(newCardId, primaryValue)
                    mode = newMode
                }
                composeRule.onNodeWithText(primaryValue).assertIsDisplayed()
                when (newCardId) {
                    CardId.HRV -> composeRule.onNodeWithText("ms").assertIsDisplayed()
                    CardId.RESTING_HR -> composeRule.onNodeWithText("bpm").assertIsDisplayed()
                    else -> Unit
                }
            }
        }
    }

    @Test
    fun steps_remainsBarOnlyAndOutsideDashboardMetricCard() {
        assertEquals(
            listOf(DashboardCardDisplayMode.BAR),
            requireNotNull(DashboardCardCatalog.spec(CardId.STEPS)).supportedModes,
        )

        composeRule.setContent {
            TestTheme {
                StepsCard(
                    stepCount = 4_321,
                    stepGoal = 10_000,
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithText("4321").assertIsDisplayed()
        composeRule.onNodeWithText("/ 10000").assertIsDisplayed()
        composeRule.onNodeWithTag(DASHBOARD_METRIC_CARD_TAG).assertDoesNotExist()
    }

    @Test
    fun status_isAccessibilityOnly_inEverySupportedMode() {
        val sleepSpecification = requireNotNull(DashboardCardCatalog.spec(CardId.SLEEP_SCORE))
        var mode by mutableStateOf(sleepSpecification.supportedModes.first())
        composeRule.setContent {
            TestTheme {
                DashboardMetricCard(
                    presentation =
                        presentationFor(CardId.SLEEP_SCORE, "86").copy(
                            secondaryText = null,
                            status = MetricStatus.OPTIMAL,
                            accessibilityDescription = "Sleep score 86, optimal.",
                        ),
                    specification = sleepSpecification,
                    requestedMode = mode,
                    renderMode = mode,
                    isEditing = false,
                    onModeSelected = {},
                )
            }
        }

        sleepSpecification.supportedModes.forEach { newMode ->
            composeRule.runOnIdle { mode = newMode }
            composeRule
                .onNodeWithContentDescription("Sleep score 86, optimal.")
                .assertExists()
            composeRule
                .onNodeWithText("optimal", substring = true, ignoreCase = true)
                .assertDoesNotExist()
        }
    }

    @Test
    fun supportedModes_keepFixedBoundsAndVisibleText_atLargeFontScaleInLightAndDarkThemes() {
        val hrvSpecification = requireNotNull(DashboardCardCatalog.spec(CardId.HRV))
        val hrvPresentation = presentationFor(CardId.HRV, "41")
        var appTheme by mutableStateOf(AppTheme.LIGHT)
        var mode by mutableStateOf(hrvSpecification.supportedModes.first())

        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 1.5f),
            ) {
                FitDashboardTheme(appTheme = appTheme, dynamicColor = false) {
                    DashboardMetricCard(
                        presentation = hrvPresentation,
                        specification = hrvSpecification,
                        requestedMode = mode,
                        renderMode = mode,
                        isEditing = false,
                        onModeSelected = {},
                    )
                }
            }
        }

        listOf(AppTheme.LIGHT, AppTheme.DARK).forEach { newTheme ->
            hrvSpecification.supportedModes.forEach { newMode ->
                composeRule.runOnIdle {
                    appTheme = newTheme
                    mode = newMode
                }
                composeRule.onNodeWithTag(DASHBOARD_METRIC_CARD_TAG).assertHeightIsEqualTo(156.dp)
                composeRule.onNodeWithText("41").assertIsDisplayed()
                composeRule.onNodeWithText("ms").assertIsDisplayed()
                when (newMode) {
                    DashboardCardDisplayMode.GAUGE -> assertVisualizationIsInsideCard(DASHBOARD_GAUGE_TAG)
                    DashboardCardDisplayMode.BAR -> assertVisualizationIsInsideCard(DASHBOARD_BAR_TAG)
                    DashboardCardDisplayMode.VALUE -> Unit
                }
            }
        }
    }

    @Test
    fun gaugeMode_allowsTwoLineTitleAndKeepsInfoActionVisible() {
        val twoLineTitle = "Resting heart\nrate"
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 1f),
            ) {
                TestTheme {
                    DashboardMetricCard(
                        presentation =
                            presentation.copy(
                                title = twoLineTitle,
                                valueText = "48",
                                unitText = "bpm",
                                accessibilityDescription = "Resting heart rate 48 bpm, optimal.",
                            ),
                        specification = specification,
                        requestedMode = DashboardCardDisplayMode.GAUGE,
                        renderMode = DashboardCardDisplayMode.GAUGE,
                        isEditing = false,
                        onModeSelected = {},
                    )
                }
            }
        }

        composeRule
            .onNodeWithText(twoLineTitle, useUnmergedTree = true)
            .assertHeightIsEqualTo(48.dp)
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("More information", useUnmergedTree = true)
            .assertWidthIsEqualTo(48.dp)
            .assertHeightIsEqualTo(48.dp)
            .assertIsDisplayed()
    }

    @Test
    fun valueMode_keepsDeltaPillInsideCardBounds() {
        setMetricCard(
            mode = DashboardCardDisplayMode.VALUE,
            specification = requireNotNull(DashboardCardCatalog.spec(CardId.STRAIN_RATIO)),
            presentation =
                presentation.copy(
                    title = "Strain",
                    valueText = "1.14",
                    secondaryText = "↑ 0.23",
                    accessibilityDescription = "Strain ratio 1.14, normal.",
                ),
        )

        composeRule.onNodeWithText("↑ 0.23").assertIsDisplayed()
        composeRule.onNodeWithTag(DASHBOARD_DELTA_PILL_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertTagIsInsideCard(DASHBOARD_DELTA_PILL_TAG)
    }

    @Test
    fun strainAndRas_keepTheirStatusContainerContentAcrossModes() {
        val representatives =
            listOf(
                Triple(CardId.STRAIN_RATIO, "Strain", MetricStatus.WARNING),
                Triple(CardId.RAS_DAILY, "RAS", MetricStatus.OPTIMAL),
            )
        var cardId by mutableStateOf(representatives.first().first)
        var cardPresentation by
            mutableStateOf(
                presentation.copy(
                    title = representatives.first().second,
                    status = representatives.first().third,
                ),
            )
        var mode by mutableStateOf(DashboardCardDisplayMode.VALUE)

        composeRule.setContent {
            TestTheme {
                DashboardMetricCard(
                    presentation = cardPresentation,
                    specification = requireNotNull(DashboardCardCatalog.spec(cardId)),
                    requestedMode = mode,
                    renderMode = mode,
                    isEditing = false,
                    onModeSelected = {},
                )
            }
        }

        representatives.forEach { (newCardId, title, status) ->
            composeRule.runOnIdle {
                cardId = newCardId
                cardPresentation =
                    presentation.copy(
                        title = title,
                        status = status,
                    )
                mode = DashboardCardDisplayMode.VALUE
            }
            val expectedContentColor = titleContentColorArgb(title)

            DashboardCardDisplayMode.entries.forEach { newMode ->
                composeRule.runOnIdle { mode = newMode }
                assertEquals(
                    "$newCardId must retain its status container content in $newMode",
                    expectedContentColor,
                    titleContentColorArgb(title),
                )
            }
        }
    }

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
    fun barMode_keepsValueUnitAndDeltaOutsideTrack_withoutStatusOrMarker() {
        setMetricCard(
            mode = DashboardCardDisplayMode.BAR,
            presentation =
                presentation.copy(
                    valueText = "48",
                    unitText = "bpm",
                    secondaryText = "↓ 1",
                    accessibilityDescription = "Resting heart rate 48 bpm, optimal.",
                ),
        )

        composeRule.onNodeWithText("48").assertIsDisplayed()
        composeRule.onNodeWithText("bpm").assertIsDisplayed()
        composeRule.onNodeWithText("↓ 1").assertIsDisplayed()
        composeRule.onNodeWithText("Optimal").assertDoesNotExist()
        composeRule.onNodeWithTag(DASHBOARD_BAR_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(DASHBOARD_DELTA_PILL_TAG, useUnmergedTree = true).assertIsDisplayed()

        assertTextIsAboveBar("48")
        assertTextIsAboveBar("bpm")
        assertTextIsBelowBar("↓ 1")
    }

    @Test
    fun barMode_keepsSleepDurationOutsideTrack() {
        setMetricCard(
            mode = DashboardCardDisplayMode.BAR,
            specification = requireNotNull(DashboardCardCatalog.spec(CardId.SLEEP_DURATION)),
            presentation =
                presentation.copy(
                    valueText = "6h 50m",
                    unitText = "",
                    secondaryText = "22:51 → 06:02",
                    accessibilityDescription = "Sleep duration 6 hours 50 minutes.",
                ),
        )

        composeRule.onNodeWithText("6h 50m").assertIsDisplayed()
        composeRule.onNodeWithText("22:51 → 06:02").assertIsDisplayed()
        composeRule.onNodeWithTag(DASHBOARD_DELTA_PILL_TAG, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(DASHBOARD_BAR_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertTextIsAboveBar("6h 50m")
        assertTextIsBelowBar("22:51 → 06:02")
    }

    @Test
    fun barMode_keepsHrvValueAndUnitOutsideTrack() {
        setMetricCard(
            mode = DashboardCardDisplayMode.BAR,
            presentation =
                presentation.copy(
                    valueText = "41",
                    unitText = "ms",
                    accessibilityDescription = "HRV 41 milliseconds, normal.",
                ),
        )

        composeRule.onNodeWithText("41").assertIsDisplayed()
        composeRule.onNodeWithText("ms").assertIsDisplayed()
        composeRule.onNodeWithTag(DASHBOARD_BAR_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertTextIsAboveBar("41")
        assertTextIsAboveBar("ms")
    }

    @Test
    fun barMode_keepsScoreOutsideTrack() {
        setMetricCard(
            mode = DashboardCardDisplayMode.BAR,
            presentation =
                presentation.copy(
                    valueText = "86",
                    unitText = "",
                    accessibilityDescription = "Sleep score 86, optimal.",
                ),
        )

        composeRule.onNodeWithText("86").assertIsDisplayed()
        composeRule.onNodeWithTag(DASHBOARD_BAR_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertTextIsAboveBar("86")
    }

    @Test
    fun barMode_showsPositiveStrainDeltaInTheSharedPillOutsideTheTrack() {
        setMetricCard(
            mode = DashboardCardDisplayMode.BAR,
            presentation =
                presentation.copy(
                    title = "Strain",
                    valueText = "1.14",
                    secondaryText = "↑ 0.23",
                    accessibilityDescription = "Strain ratio 1.14, normal.",
                ),
        )

        composeRule.onNodeWithText("↑ 0.23").assertIsDisplayed()
        composeRule.onNodeWithTag(DASHBOARD_DELTA_PILL_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertTextIsBelowBar("↑ 0.23")
    }

    @Test
    fun barMode_keepsValueAndUnitOutsideCanvasElement() {
        setMetricCard(
            mode = DashboardCardDisplayMode.BAR,
            presentation =
                presentation.copy(
                    valueText = "48",
                    unitText = "bpm",
                    secondaryText = "↓ 1",
                    accessibilityDescription = "Resting heart rate 48 bpm, optimal.",
                ),
        )

        // Value and unit text should be displayed
        composeRule.onNodeWithText("48").assertIsDisplayed()
        composeRule.onNodeWithText("bpm").assertIsDisplayed()

        // Bar canvas should be displayed
        composeRule.onNodeWithTag(DASHBOARD_BAR_TAG, useUnmergedTree = true).assertIsDisplayed()

        // Value and unit should not be contained within the bar canvas element
        val barBounds =
            composeRule
                .onNodeWithTag(DASHBOARD_BAR_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val valueBounds =
            composeRule
                .onNodeWithText("48", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val unitBounds =
            composeRule
                .onNodeWithText("bpm", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot

        // Text should not overlap with the canvas (value above, unit above)
        assertTrue(
            "Value must be outside (above) the Bar canvas",
            valueBounds.bottom <= barBounds.top,
        )
        assertTrue(
            "Unit must be outside (above) the Bar canvas",
            unitBounds.bottom <= barBounds.top,
        )
    }

    @Test
    fun barMode_secondaryContentFollowsTheTrack() {
        setMetricCard(
            mode = DashboardCardDisplayMode.BAR,
            presentation =
                presentation.copy(
                    valueText = "1.14",
                    unitText = "",
                    secondaryText = "↑ 0.23",
                    accessibilityDescription = "Strain ratio 1.14, normal.",
                ),
        )

        // Secondary content should be displayed
        composeRule.onNodeWithText("↑ 0.23").assertIsDisplayed()

        // Bar canvas should be displayed
        composeRule.onNodeWithTag(DASHBOARD_BAR_TAG, useUnmergedTree = true).assertIsDisplayed()

        // Secondary content should be after (below) the bar
        val barBounds =
            composeRule
                .onNodeWithTag(DASHBOARD_BAR_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val secondaryBounds =
            composeRule
                .onNodeWithText("↑ 0.23", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot

        assertTrue(
            "Secondary content must follow (be below) the Bar track",
            secondaryBounds.top >= barBounds.bottom,
        )
    }

    @Test
    fun gaugeMode_keepValueAndUnitReadableForLongTitle() {
        val longTitle = "Resting heart\nrate"
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 1.5f),
            ) {
                TestTheme {
                    DashboardMetricCard(
                        presentation =
                            presentation.copy(
                                title = longTitle,
                                valueText = "48",
                                unitText = "bpm",
                                accessibilityDescription = "Resting heart rate 48 bpm, optimal.",
                            ),
                        specification = specification,
                        requestedMode = DashboardCardDisplayMode.GAUGE,
                        renderMode = DashboardCardDisplayMode.GAUGE,
                        isEditing = false,
                        onModeSelected = {},
                    )
                }
            }
        }

        // Both value and unit should remain visible and readable
        composeRule.onNodeWithText("48").assertIsDisplayed()
        composeRule.onNodeWithText("bpm").assertIsDisplayed()

        // Gauge visualization should remain inside the card
        composeRule.onNodeWithTag(DASHBOARD_GAUGE_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertVisualizationIsInsideCard(DASHBOARD_GAUGE_TAG)
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
        specification: DashboardCardSpec = this.specification,
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

    private fun presentationFor(
        cardId: CardId,
        valueText: String,
    ): DashboardMetricPresentation {
        val (title, unitText) =
            when (cardId) {
                CardId.SLEEP_SCORE -> "Sleep score" to ""
                CardId.READINESS -> "Readiness" to ""
                CardId.HRV -> "HRV" to "ms"
                CardId.SLEEP_DURATION -> "Sleep duration" to ""
                CardId.RAS_DAILY -> "RAS" to ""
                CardId.RESTING_HR -> "Resting heart rate" to "bpm"
                else -> error("No representative presentation for $cardId")
            }
        return presentation.copy(
            title = title,
            valueText = valueText,
            unitText = unitText,
            accessibilityDescription = "$title $valueText $unitText, normal.",
            visual =
                DashboardMetricVisual.Score(
                    rawValue = 74f,
                    minValue = 0f,
                    maxValue = 100f,
                    markerFraction = 0.74f,
                    bands = emptyList(),
                    unavailableReason = null,
                ),
        )
    }

    private fun assertVisualizationIsInsideCard(tag: String) {
        val cardBounds =
            composeRule
                .onNodeWithTag(DASHBOARD_METRIC_CARD_TAG)
                .fetchSemanticsNode()
                .boundsInRoot
        val visualizationBounds =
            composeRule
                .onNodeWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(
            "$tag must remain inside the card: visualization=$visualizationBounds, card=$cardBounds",
            visualizationBounds.left >= cardBounds.left &&
                visualizationBounds.top >= cardBounds.top &&
                visualizationBounds.right <= cardBounds.right &&
                visualizationBounds.bottom <= cardBounds.bottom,
        )
    }

    private fun assertTagIsInsideCard(tag: String) {
        val cardBounds =
            composeRule
                .onNodeWithTag(DASHBOARD_METRIC_CARD_TAG)
                .fetchSemanticsNode()
                .boundsInRoot
        val taggedBounds =
            composeRule
                .onNodeWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(
            "$tag must remain inside the card: tagged=$taggedBounds, card=$cardBounds",
            taggedBounds.left >= cardBounds.left &&
                taggedBounds.top >= cardBounds.top &&
                taggedBounds.right <= cardBounds.right &&
                taggedBounds.bottom <= cardBounds.bottom,
        )
    }

    private fun titleContentColorArgb(title: String): Int {
        val textLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule
            .onNodeWithText(title, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                action(textLayouts)
            }
        return textLayouts
            .single()
            .layoutInput.style.color
            .toArgb()
    }

    private fun assertTextIsAboveBar(text: String) {
        val textBounds =
            composeRule
                .onNodeWithText(text, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val barBounds =
            composeRule
                .onNodeWithTag(DASHBOARD_BAR_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(
            "$text must not overlap the Bar track: text=$textBounds, bar=$barBounds",
            textBounds.bottom <= barBounds.top,
        )
    }

    private fun assertTextIsBelowBar(text: String) {
        val textBounds =
            composeRule
                .onNodeWithText(text, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val barBounds =
            composeRule
                .onNodeWithTag(DASHBOARD_BAR_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(
            "$text must stay below the Bar track: text=$textBounds, bar=$barBounds",
            textBounds.top >= barBounds.bottom,
        )
    }
}

@androidx.compose.runtime.Composable
private fun TestTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    FitDashboardTheme(dynamicColor = false, content = content)
}
