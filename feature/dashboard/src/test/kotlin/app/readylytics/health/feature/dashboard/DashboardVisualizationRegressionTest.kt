package app.readylytics.health.feature.dashboard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
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
import app.readylytics.health.core.ui.components.onContainerColor
import app.readylytics.health.data.preferences.AppTheme
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.dashboard.DashboardCardSpec
import app.readylytics.health.domain.model.MetricStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // A natural-language title without an embedded newline: the two-line reservation must
        // come from the trimmed line-height plus minLines/maxLines = 2, not from a manual break.
        val twoLineTitle = "Resting heart rate"
        val shortTitle = "HRV"
        var title by mutableStateOf(twoLineTitle)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 1f),
            ) {
                TestTheme {
                    DashboardMetricCard(
                        presentation =
                            presentation.copy(
                                title = title,
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

        // The title reserves two lines regardless of how much text it holds: with minLines and
        // maxLines both 2 plus a trimmed line height, its measured height is the same for a
        // short and a long title, so the trailing action slot never reflows.
        val twoLineHeight = titleHeightPx(twoLineTitle)
        composeRule.runOnIdle { title = shortTitle }
        assertEquals(
            "A one-word title must reserve the same two-line height as a wrapping one",
            twoLineHeight,
            titleHeightPx(shortTitle),
            0.5f,
        )
        composeRule.runOnIdle { title = twoLineTitle }
        composeRule
            .onNodeWithText(twoLineTitle, useUnmergedTree = true)
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
            val expectedContentColor = textColorArgb(title)

            DashboardCardDisplayMode.entries.forEach { newMode ->
                composeRule.runOnIdle { mode = newMode }
                assertEquals(
                    "$newCardId must retain its status container content in $newMode",
                    expectedContentColor,
                    textColorArgb(title),
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
    fun gaugeMode_keepHrvValueAndUnitReadableWithLongTitle() {
        val hrvSpecification = requireNotNull(DashboardCardCatalog.spec(CardId.HRV))
        val longTitle = "Heart rate variability"
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 1.5f),
            ) {
                TestTheme {
                    DashboardMetricCard(
                        presentation =
                            presentation.copy(
                                title = longTitle,
                                valueText = "41",
                                unitText = "ms",
                                accessibilityDescription = "Heart rate variability 41 milliseconds, normal.",
                            ),
                        specification = hrvSpecification,
                        requestedMode = DashboardCardDisplayMode.GAUGE,
                        renderMode = DashboardCardDisplayMode.GAUGE,
                        isEditing = false,
                        onModeSelected = {},
                    )
                }
            }
        }

        // HRV value and unit should remain visible and readable with long title at high font scale
        composeRule.onNodeWithText("41").assertIsDisplayed()
        composeRule.onNodeWithText("ms").assertIsDisplayed()

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

    @Test
    fun barMode_keepsBarAndDeltaPillInsideCardBounds_atLargeFontScale() {
        val strainSpecification = requireNotNull(DashboardCardCatalog.spec(CardId.STRAIN_RATIO))
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 1.5f),
            ) {
                TestTheme {
                    DashboardMetricCard(
                        presentation =
                            presentation.copy(
                                title = "Strain ratio",
                                valueText = "1.14",
                                secondaryText = "↑ 0.23",
                                accessibilityDescription = "Strain ratio 1.14, normal.",
                            ),
                        specification = strainSpecification,
                        requestedMode = DashboardCardDisplayMode.BAR,
                        renderMode = DashboardCardDisplayMode.BAR,
                        isEditing = false,
                        onModeSelected = {},
                    )
                }
            }
        }

        // Both must still be laid out (not squeezed to zero) *and* inside the card.
        composeRule.onNodeWithTag(DASHBOARD_BAR_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(DASHBOARD_DELTA_PILL_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertTagIsInsideCard(DASHBOARD_BAR_TAG)
        assertTagIsInsideCard(DASHBOARD_DELTA_PILL_TAG)
    }

    @Test
    fun valueMode_keepsDeltaPillInsideCardBounds_atEveryFontScale() {
        val strainSpecification = requireNotNull(DashboardCardCatalog.spec(CardId.STRAIN_RATIO))
        var fontScale by mutableStateOf(1f)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = fontScale),
            ) {
                TestTheme {
                    DashboardMetricCard(
                        presentation =
                            presentation.copy(
                                title = "Strain ratio",
                                valueText = "1.14",
                                secondaryText = "\u2191 0.23",
                                accessibilityDescription = "Strain ratio 1.14, normal.",
                            ),
                        specification = strainSpecification,
                        requestedMode = DashboardCardDisplayMode.VALUE,
                        renderMode = DashboardCardDisplayMode.VALUE,
                        isEditing = false,
                        onModeSelected = {},
                    )
                }
            }
        }

        listOf(1f, 1.5f).forEach { newFontScale ->
            composeRule.runOnIdle { fontScale = newFontScale }
            composeRule
                .onNodeWithTag(DASHBOARD_DELTA_PILL_TAG, useUnmergedTree = true)
                .assertIsDisplayed()
            assertTagIsInsideCard(DASHBOARD_DELTA_PILL_TAG)
        }
    }

    @Test
    fun valueAndBarModes_shareTheirValueUnitAndSecondaryGeometry() {
        val hrvSpecification = requireNotNull(DashboardCardCatalog.spec(CardId.HRV))
        var mode by mutableStateOf(DashboardCardDisplayMode.BAR)
        composeRule.setContent {
            TestTheme {
                DashboardMetricCard(
                    presentation =
                        presentation.copy(
                            title = "HRV",
                            valueText = "41",
                            unitText = "ms",
                            secondaryText = "\u2193 2",
                            accessibilityDescription = "HRV 41 milliseconds, normal.",
                        ),
                    specification = hrvSpecification,
                    requestedMode = mode,
                    renderMode = mode,
                    isEditing = false,
                    onModeSelected = {},
                )
            }
        }

        val barValue = boundsOfText("41")
        val barUnit = boundsOfText("ms")
        val barPill = boundsOfTag(DASHBOARD_DELTA_PILL_TAG)
        composeRule.runOnIdle { mode = DashboardCardDisplayMode.VALUE }

        // Value mode is Bar mode without the painted track: the value/unit row and the
        // secondary slot must land in exactly the same place, and the unit must sit beside
        // the value rather than on its own line below it.
        assertEquals("Value row must not move between Bar and Value", barValue, boundsOfText("41"))
        assertEquals("Unit must not move between Bar and Value", barUnit, boundsOfText("ms"))
        assertEquals("Delta pill must not move between Bar and Value", barPill, boundsOfTag(DASHBOARD_DELTA_PILL_TAG))
        assertTrue(
            "Unit must sit beside the value, not below it: value=$barValue, unit=$barUnit",
            barUnit.left >= barValue.right,
        )
        composeRule.onNodeWithTag(DASHBOARD_BAR_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun gaugeUnitAndDeltaPillFollowTheStatusContentColor_forNonNeutralStatus() {
        var expectedContentColor = Color.Unspecified
        composeRule.setContent {
            TestTheme {
                expectedContentColor = MetricStatus.WARNING.onContainerColor()
                DashboardMetricCard(
                    presentation =
                        presentation.copy(
                            title = "HRV",
                            valueText = "41",
                            unitText = "ms",
                            secondaryText = "↓ 2",
                            status = MetricStatus.WARNING,
                            accessibilityDescription = "HRV 41 milliseconds, warning.",
                        ),
                    specification = specification,
                    requestedMode = DashboardCardDisplayMode.GAUGE,
                    renderMode = DashboardCardDisplayMode.GAUGE,
                    isEditing = false,
                    onModeSelected = {},
                )
            }
        }

        assertEquals(
            "Gauge unit text must follow the card's status content color",
            expectedContentColor.copy(alpha = 0.8f).toArgb(),
            textColorArgb("ms"),
        )
        assertEquals(
            "Delta pill text must follow the card's status content color",
            expectedContentColor.toArgb(),
            textColorArgb("↓ 2"),
        )
    }

    @Test
    fun barPlainSecondaryFollowsTheStatusContentColor_forNonNeutralStatus() {
        var expectedContentColor = Color.Unspecified
        composeRule.setContent {
            TestTheme {
                expectedContentColor = MetricStatus.POOR.onContainerColor()
                DashboardMetricCard(
                    presentation =
                        presentation.copy(
                            title = "Sleep duration",
                            valueText = "6h 50m",
                            secondaryText = "22:51 → 06:02",
                            status = MetricStatus.POOR,
                            accessibilityDescription = "Sleep duration 6 hours 50 minutes, poor.",
                        ),
                    specification = requireNotNull(DashboardCardCatalog.spec(CardId.SLEEP_DURATION)),
                    requestedMode = DashboardCardDisplayMode.BAR,
                    renderMode = DashboardCardDisplayMode.BAR,
                    isEditing = false,
                    onModeSelected = {},
                )
            }
        }

        assertEquals(
            "Bar plain secondary text must follow the card's status content color",
            expectedContentColor.copy(alpha = 0.8f).toArgb(),
            textColorArgb("22:51 → 06:02"),
        )
    }

    @Test
    fun titleInfoIcon_staysPinnedToTheCardTopRightCorner_forShortAndLongTitles() {
        val titles = listOf("HRV", "Heart rate variability over the last night")
        var title by mutableStateOf(titles.first())
        composeRule.setContent {
            TestTheme {
                DashboardMetricCard(
                    presentation = presentation.copy(title = title),
                    specification = specification,
                    requestedMode = DashboardCardDisplayMode.GAUGE,
                    renderMode = DashboardCardDisplayMode.GAUGE,
                    isEditing = false,
                    onModeSelected = {},
                )
            }
        }

        titles.forEach { newTitle ->
            composeRule.runOnIdle { title = newTitle }
            val cardBounds =
                composeRule
                    .onNodeWithTag(DASHBOARD_METRIC_CARD_TAG)
                    .fetchSemanticsNode()
                    .boundsInRoot
            val titleBounds =
                composeRule
                    .onNodeWithText(newTitle, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            val iconBounds =
                composeRule
                    .onNodeWithTag(DASHBOARD_TITLE_INFO_ICON_TAG, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            val actionBounds =
                composeRule
                    .onNodeWithContentDescription("More information", useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot

            assertTrue(
                "Info action must start at the title row's top for '$newTitle': " +
                    "action=$actionBounds, title=$titleBounds",
                kotlin.math.abs(actionBounds.top - titleBounds.top) <= 1f,
            )
            assertTrue(
                "Info icon must sit at the card's top edge for '$newTitle': " +
                    "icon=$iconBounds, title=$titleBounds",
                kotlin.math.abs(iconBounds.top - titleBounds.top) <= 1f,
            )
            assertTrue(
                "Info icon must sit at the card's trailing content edge for '$newTitle': " +
                    "icon=$iconBounds, card=$cardBounds",
                kotlin.math.abs(iconBounds.right - (cardBounds.right - 16f)) <= 1f,
            )
        }
    }

    @Test
    fun deltaPillTreatment_isLimitedToCardsWhoseSecondaryTextIsARealDelta() {
        listOf(
            CardId.SLEEP_SCORE,
            CardId.READINESS,
            CardId.HRV,
            CardId.SLEEP_RHR,
            CardId.RESTING_HR,
            CardId.STRAIN_RATIO,
        ).forEach { cardId ->
            assertTrue("$cardId must use the delta pill", cardId.usesDeltaPill())
        }
        listOf(
            CardId.SLEEP_DURATION,
            CardId.CIRCADIAN_CONSISTENCY,
            CardId.HEART_RATE,
        ).forEach { cardId ->
            assertFalse("$cardId must keep plain secondary text", cardId.usesDeltaPill())
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

    private fun boundsOfText(text: String) =
        composeRule
            .onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

    private fun boundsOfTag(tag: String) =
        composeRule
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

    private fun titleHeightPx(title: String): Float =
        composeRule
            .onNodeWithText(title, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .height

    private fun textColorArgb(text: String): Int {
        val textLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule
            .onNodeWithText(text, useUnmergedTree = true)
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
