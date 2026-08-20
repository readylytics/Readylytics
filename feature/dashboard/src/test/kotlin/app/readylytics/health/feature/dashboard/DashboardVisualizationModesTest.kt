package app.readylytics.health.feature.dashboard
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.FitDashboardTheme
import app.readylytics.health.core.model.data.preferences.AppTheme
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.ui.components.StepsCard
import app.readylytics.health.core.ui.components.metriccard.UNIVERSAL_BAR_TAG
import app.readylytics.health.core.ui.components.metriccard.UNIVERSAL_DELTA_PILL_TAG
import app.readylytics.health.core.ui.components.metriccard.UNIVERSAL_GAUGE_TAG
import app.readylytics.health.core.ui.components.metriccard.UNIVERSAL_METRIC_CARD_TAG
import app.readylytics.health.domain.model.MetricStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DashboardVisualizationModesTest : DashboardVisualizationRegressionTestBase() {
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
        composeRule.onNodeWithTag(UNIVERSAL_METRIC_CARD_TAG).assertDoesNotExist()
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
                composeRule.onNodeWithTag(UNIVERSAL_METRIC_CARD_TAG).assertHeightIsEqualTo(156.dp)
                composeRule.onNodeWithText("41").assertIsDisplayed()
                composeRule.onNodeWithText("ms").assertIsDisplayed()
                when (newMode) {
                    DashboardCardDisplayMode.GAUGE -> assertVisualizationIsInsideCard(UNIVERSAL_GAUGE_TAG)
                    DashboardCardDisplayMode.BAR -> assertVisualizationIsInsideCard(UNIVERSAL_BAR_TAG)
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
            usesDeltaPill = true,
        )

        composeRule.onNodeWithText("↑ 0.23").assertIsDisplayed()
        composeRule.onNodeWithTag(UNIVERSAL_DELTA_PILL_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertTagIsInsideCard(UNIVERSAL_DELTA_PILL_TAG)
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
        composeRule.onNodeWithTag(UNIVERSAL_GAUGE_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(UNIVERSAL_BAR_TAG).assertDoesNotExist()
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

        composeRule.onNodeWithTag(UNIVERSAL_GAUGE_TAG, useUnmergedTree = true).assertIsDisplayed()
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
            usesDeltaPill = true,
        )

        composeRule.onNodeWithText("48").assertIsDisplayed()
        composeRule.onNodeWithText("bpm").assertIsDisplayed()
        composeRule.onNodeWithText("↓ 1").assertIsDisplayed()
        composeRule.onNodeWithText("Optimal").assertDoesNotExist()
        composeRule.onNodeWithTag(UNIVERSAL_BAR_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(UNIVERSAL_DELTA_PILL_TAG, useUnmergedTree = true).assertIsDisplayed()

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
        composeRule.onNodeWithTag(UNIVERSAL_DELTA_PILL_TAG, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(UNIVERSAL_BAR_TAG, useUnmergedTree = true).assertIsDisplayed()
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
        composeRule.onNodeWithTag(UNIVERSAL_BAR_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertTextIsAboveBar("41")
        assertTextIsAboveBar("ms")
    }
}
