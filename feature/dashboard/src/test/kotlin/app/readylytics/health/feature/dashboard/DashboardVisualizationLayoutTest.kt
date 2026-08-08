package app.readylytics.health.feature.dashboard
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.ui.components.metriccard.UNIVERSAL_BAR_TAG
import app.readylytics.health.core.ui.components.metriccard.UNIVERSAL_DELTA_PILL_TAG
import app.readylytics.health.core.ui.components.metriccard.UNIVERSAL_GAUGE_TAG
import app.readylytics.health.core.ui.components.metriccard.UNIVERSAL_METRIC_CARD_TAG
import app.readylytics.health.core.ui.components.metriccard.UNIVERSAL_TITLE_INFO_ICON_TAG
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import app.readylytics.health.core.ui.components.metriccard.progressFraction
import app.readylytics.health.core.ui.components.onContainerColor
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.model.MetricStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DashboardVisualizationLayoutTest : DashboardVisualizationRegressionTestBase() {
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
        composeRule.onNodeWithTag(UNIVERSAL_BAR_TAG, useUnmergedTree = true).assertIsDisplayed()
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
        composeRule.onNodeWithTag(UNIVERSAL_DELTA_PILL_TAG, useUnmergedTree = true).assertIsDisplayed()
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
        composeRule.onNodeWithTag(UNIVERSAL_GAUGE_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertVisualizationIsInsideCard(UNIVERSAL_GAUGE_TAG)
    }

    @Test
    fun gaugeMode_fullSweep_staysInsideCardWithTwoLineTitle() {
        setMetricCard(
            mode = DashboardCardDisplayMode.GAUGE,
            presentation =
                presentation.copy(
                    title = "Very long readiness title that uses two lines",
                    visual =
                        UniversalMetricVisual.Score(
                            rawValue = 100f,
                            minValue = 0f,
                            maxValue = 100f,
                            markerFraction = 1f,
                            unavailableReason = null,
                        ),
                ),
        )

        composeRule.onNodeWithTag(UNIVERSAL_GAUGE_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertVisualizationIsInsideCard(UNIVERSAL_GAUGE_TAG)
    }

    @Test
    fun progressFraction_returnsEachNormalizedVisualMarkerFraction() {
        val visuals =
            listOf(
                UniversalMetricVisual.Score(
                    rawValue = 12f,
                    minValue = 0f,
                    maxValue = 100f,
                    markerFraction = 0.12f,
                    unavailableReason = null,
                ) to 0.12f,
                UniversalMetricVisual.Goal(
                    rawValue = 34f,
                    targetValue = 100f,
                    markerFraction = 0.34f,
                    targetMarkerFraction = 1f,
                    isAboveTarget = false,
                    selectionAvailable = true,
                    unavailableReason = null,
                ) to 0.34f,
                UniversalMetricVisual.PersonalBaseline(
                    rawValue = 56f,
                    baselineValue = 50f,
                    ratio = 1.12f,
                    markerFraction = 0.56f,
                    baselineMarkerFraction = 0.5f,
                    selectionAvailable = true,
                    unavailableReason = null,
                ) to 0.56f,
                UniversalMetricVisual.ReferenceRange(
                    rawValue = 78f,
                    markerFraction = 0.78f,
                    referenceMarkerFraction = 0.5f,
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
                    isEditing = false,
                    onModeSelected = {},
                )
            }
        }

        DashboardCardDisplayMode.entries.forEach { newMode ->
            composeRule.runOnIdle { mode = newMode }
            composeRule.onNodeWithTag(UNIVERSAL_METRIC_CARD_TAG).assertHeightIsEqualTo(156.dp)
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
                        isEditing = false,
                        onModeSelected = {},
                    )
                }
            }
        }

        // Both must still be laid out (not squeezed to zero) *and* inside the card.
        composeRule.onNodeWithTag(UNIVERSAL_BAR_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(UNIVERSAL_DELTA_PILL_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertTagIsInsideCard(UNIVERSAL_BAR_TAG)
        assertTagIsInsideCard(UNIVERSAL_DELTA_PILL_TAG)
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
                        isEditing = false,
                        onModeSelected = {},
                    )
                }
            }
        }

        listOf(1f, 1.5f).forEach { newFontScale ->
            composeRule.runOnIdle { fontScale = newFontScale }
            composeRule
                .onNodeWithTag(UNIVERSAL_DELTA_PILL_TAG, useUnmergedTree = true)
                .assertIsDisplayed()
            assertTagIsInsideCard(UNIVERSAL_DELTA_PILL_TAG)
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
                    isEditing = false,
                    onModeSelected = {},
                )
            }
        }

        val barValue = boundsOfText("41")
        val barUnit = boundsOfText("ms")
        val barPill = boundsOfTag(UNIVERSAL_DELTA_PILL_TAG)
        composeRule.runOnIdle { mode = DashboardCardDisplayMode.VALUE }

        // Value mode is Bar mode without the painted track: the value/unit row and the
        // secondary slot must land in exactly the same place, and the unit must sit beside
        // the value rather than on its own line below it.
        assertEquals("Value row must not move between Bar and Value", barValue, boundsOfText("41"))
        assertEquals("Unit must not move between Bar and Value", barUnit, boundsOfText("ms"))
        assertEquals("Delta pill must not move between Bar and Value", barPill, boundsOfTag(UNIVERSAL_DELTA_PILL_TAG))
        assertTrue(
            "Unit must sit beside the value, not below it: value=$barValue, unit=$barUnit",
            barUnit.left >= barValue.right,
        )
        composeRule.onNodeWithTag(UNIVERSAL_BAR_TAG, useUnmergedTree = true).assertDoesNotExist()
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
    fun gaugeMode_centersDeltaPillAndPlainSecondaryBelowCard() {
        val hrvSpecification = requireNotNull(DashboardCardCatalog.spec(CardId.HRV))
        composeRule.setContent {
            TestTheme {
                DashboardMetricCard(
                    presentation =
                        presentation.copy(
                            title = "HRV",
                            valueText = "41",
                            unitText = "ms",
                            secondaryText = "↓ 2",
                            accessibilityDescription = "HRV 41 milliseconds, normal.",
                        ),
                    specification = hrvSpecification,
                    requestedMode = DashboardCardDisplayMode.GAUGE,
                    isEditing = false,
                    onModeSelected = {},
                )
            }
        }

        val cardBounds =
            composeRule
                .onNodeWithTag(UNIVERSAL_METRIC_CARD_TAG)
                .fetchSemanticsNode()
                .boundsInRoot
        val cardCenterX = cardBounds.left + cardBounds.width / 2f

        val pillBounds = boundsOfTag(UNIVERSAL_DELTA_PILL_TAG)
        val pillCenterX = pillBounds.left + pillBounds.width / 2f
        assertTrue(
            "Gauge delta pill must be horizontally centered below the card: " +
                "cardCenter=$cardCenterX, pillCenter=$pillCenterX, pill=$pillBounds",
            kotlin.math.abs(pillCenterX - cardCenterX) <= 1f,
        )
    }

    @Test
    fun gaugeMode_centersPlainSecondaryTextBelowCard() {
        composeRule.setContent {
            TestTheme {
                DashboardMetricCard(
                    presentation =
                        presentation.copy(
                            title = "Sleep time",
                            valueText = "7h 11m",
                            secondaryText = "22:51 → 06:02",
                            accessibilityDescription = "Sleep time 7 hours 11 minutes, normal.",
                        ),
                    specification = requireNotNull(DashboardCardCatalog.spec(CardId.SLEEP_DURATION)),
                    requestedMode = DashboardCardDisplayMode.GAUGE,
                    isEditing = false,
                    onModeSelected = {},
                )
            }
        }

        val cardBounds =
            composeRule
                .onNodeWithTag(UNIVERSAL_METRIC_CARD_TAG)
                .fetchSemanticsNode()
                .boundsInRoot
        val cardCenterX = cardBounds.left + cardBounds.width / 2f

        val secondaryBounds = boundsOfText("22:51 → 06:02")
        val secondaryCenterX = secondaryBounds.left + secondaryBounds.width / 2f
        assertTrue(
            "Gauge plain secondary text must be horizontally centered below the card: " +
                "cardCenter=$cardCenterX, secondaryCenter=$secondaryCenterX, secondary=$secondaryBounds",
            kotlin.math.abs(secondaryCenterX - cardCenterX) <= 1f,
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
    fun plainSecondaryText_isAnchoredToTheValueRowBottomStart() {
        composeRule.setContent {
            TestTheme {
                DashboardMetricCard(
                    presentation =
                        presentation.copy(
                            title = "Sleep duration",
                            valueText = "7h 11m",
                            secondaryText = "22:51 → 06:02",
                        ),
                    specification = requireNotNull(DashboardCardCatalog.spec(CardId.SLEEP_DURATION)),
                    requestedMode = DashboardCardDisplayMode.VALUE,
                    isEditing = false,
                    onModeSelected = {},
                )
            }
        }

        val valueBounds = boundsOfText("7h 11m")
        val secondaryBounds = boundsOfText("22:51 → 06:02")
        assertEquals(
            "Plain secondary text must start at the value row's content edge",
            valueBounds.left,
            secondaryBounds.left,
        )
        assertTrue(
            "Plain secondary text must be below the value row: value=$valueBounds, secondary=$secondaryBounds",
            secondaryBounds.top >= valueBounds.bottom,
        )
    }

    @Test
    fun secondaryContent_sharesBottomEdgeAcrossDifferentValueHeights() {
        composeRule.setContent {
            TestTheme {
                Row(modifier = Modifier.width(400.dp)) {
                    DashboardMetricCard(
                        presentation =
                            presentation.copy(
                                title = "HRV",
                                valueText = "42",
                                unitText = "ms",
                                secondaryText = "↑ 1 ms",
                            ),
                        specification = requireNotNull(DashboardCardCatalog.spec(CardId.HRV)),
                        requestedMode = DashboardCardDisplayMode.VALUE,
                        isEditing = false,
                        onModeSelected = {},
                        modifier = Modifier.weight(1f).height(240.dp),
                    )
                    DashboardMetricCard(
                        presentation =
                            presentation.copy(
                                title = "Sleep Time",
                                valueText = "7h 11m",
                                unitText = "",
                                secondaryText = "22:56 → 06:50",
                            ),
                        specification = requireNotNull(DashboardCardCatalog.spec(CardId.SLEEP_DURATION)),
                        requestedMode = DashboardCardDisplayMode.VALUE,
                        isEditing = false,
                        onModeSelected = {},
                        modifier = Modifier.weight(1f).height(240.dp),
                    )
                }
            }
        }

        val pillBounds = boundsOfTag(UNIVERSAL_DELTA_PILL_TAG)
        val plainBounds = boundsOfText("22:56 → 06:50")
        assertEquals(
            "HRV pill and Sleep Time text must share the secondary slot bottom edge: " +
                "pill=$pillBounds, plain=$plainBounds",
            pillBounds.bottom,
            plainBounds.bottom,
        )
    }

    @Test
    fun barTrack_sharesVerticalPositionAcrossDifferentValueHeights() {
        composeRule.setContent {
            TestTheme {
                Row(modifier = Modifier.width(400.dp)) {
                    DashboardMetricCard(
                        presentation =
                            presentation.copy(
                                title = "HRV",
                                valueText = "42",
                                unitText = "ms",
                                secondaryText = "↑ 1 ms",
                            ),
                        specification = requireNotNull(DashboardCardCatalog.spec(CardId.HRV)),
                        requestedMode = DashboardCardDisplayMode.BAR,
                        isEditing = false,
                        onModeSelected = {},
                        modifier = Modifier.weight(1f).height(240.dp),
                    )
                    DashboardMetricCard(
                        presentation =
                            presentation.copy(
                                title = "Sleep Time",
                                valueText = "7h 11m",
                                unitText = "",
                                secondaryText = "22:56 → 06:50",
                            ),
                        specification = requireNotNull(DashboardCardCatalog.spec(CardId.SLEEP_DURATION)),
                        requestedMode = DashboardCardDisplayMode.BAR,
                        isEditing = false,
                        onModeSelected = {},
                        modifier = Modifier.weight(1f).height(240.dp),
                    )
                }
            }
        }

        val barBounds =
            composeRule
                .onAllNodesWithTag(UNIVERSAL_BAR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .map { it.boundsInRoot }
        assertEquals(
            "HRV and Sleep Time bars must share the same vertical position",
            barBounds[0].top,
            barBounds[1].top,
        )
        assertEquals(
            "HRV and Sleep Time bars must share the same height",
            barBounds[0].bottom,
            barBounds[1].bottom,
        )

        val sleepSecondaryBounds = boundsOfText("22:56 → 06:50")
        assertEquals(
            "Sleep Time bar must start at the same content edge as its plain secondary text",
            barBounds[1].left,
            sleepSecondaryBounds.left,
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
                    isEditing = false,
                    onModeSelected = {},
                )
            }
        }

        titles.forEach { newTitle ->
            composeRule.runOnIdle { title = newTitle }
            val cardBounds =
                composeRule
                    .onNodeWithTag(UNIVERSAL_METRIC_CARD_TAG)
                    .fetchSemanticsNode()
                    .boundsInRoot
            val titleBounds =
                composeRule
                    .onNodeWithText(newTitle, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            val iconBounds =
                composeRule
                    .onNodeWithTag(UNIVERSAL_TITLE_INFO_ICON_TAG, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            val actionBounds =
                composeRule
                    .onNodeWithContentDescription("More information", useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot

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
            assertTrue(
                "Info action must be centered on its glyph so the touch indicator originates " +
                    "at the visible icon for '$newTitle': action=$actionBounds, icon=$iconBounds",
                kotlin.math.abs(actionBounds.center.x - iconBounds.center.x) <= 1f &&
                    kotlin.math.abs(actionBounds.center.y - iconBounds.center.y) <= 1f,
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
}
