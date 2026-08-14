package app.readylytics.health.feature.dashboard
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import app.readylytics.health.core.designsystem.FitDashboardTheme
import app.readylytics.health.core.ui.components.metriccard.UNIVERSAL_BAR_TAG
import app.readylytics.health.core.ui.components.metriccard.UNIVERSAL_METRIC_CARD_TAG
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.dashboard.ModeSpec
import app.readylytics.health.domain.model.MetricStatus
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
abstract class DashboardVisualizationRegressionTestBase {
    @get:Rule
    val composeRule = createComposeRule()

    protected val specification =
        ModeSpec(
            legacyDefaultMode = DashboardCardDisplayMode.VALUE,
            supportedModes = DashboardCardDisplayMode.entries,
        )

    protected val presentation =
        UniversalMetricPresentation(
            title = "Metric",
            valueText = "0",
            unitText = "",
            secondaryText = null,
            status = MetricStatus.NEUTRAL,
            tooltip = "Metric context",
            accessibilityDescription = "Metric value, normal.",
            visual =
                UniversalMetricVisual.Score(
                    rawValue = 0f,
                    minValue = 0f,
                    maxValue = 100f,
                    markerFraction = 0f,
                    unavailableReason = null,
                ),
        )

    protected fun setMetricCard(
        mode: DashboardCardDisplayMode,
        presentation: UniversalMetricPresentation,
        specification: ModeSpec = this.specification,
        usesDeltaPill: Boolean = false,
    ) {
        composeRule.setContent {
            TestTheme {
                DashboardMetricCard(
                    presentation = presentation,
                    specification = specification,
                    requestedMode = mode,
                    isEditing = false,
                    onModeSelected = {},
                    usesDeltaPill = usesDeltaPill,
                )
            }
        }
    }

    protected fun presentationFor(
        cardId: CardId,
        valueText: String,
    ): UniversalMetricPresentation {
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
                UniversalMetricVisual.Score(
                    rawValue = 74f,
                    minValue = 0f,
                    maxValue = 100f,
                    markerFraction = 0.74f,
                    unavailableReason = null,
                ),
        )
    }

    protected fun assertVisualizationIsInsideCard(tag: String) {
        val cardBounds =
            composeRule
                .onNodeWithTag(UNIVERSAL_METRIC_CARD_TAG)
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

    protected fun assertTagIsInsideCard(tag: String) {
        val cardBounds =
            composeRule
                .onNodeWithTag(UNIVERSAL_METRIC_CARD_TAG)
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

    protected fun boundsOfText(text: String) =
        composeRule
            .onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

    protected fun boundsOfTag(tag: String) =
        composeRule
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

    protected fun titleHeightPx(title: String): Float =
        composeRule
            .onNodeWithText(title, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .height

    protected fun textColorArgb(text: String): Int {
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

    protected fun assertTextIsAboveBar(text: String) {
        val textBounds =
            composeRule
                .onNodeWithText(text, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val barBounds =
            composeRule
                .onNodeWithTag(UNIVERSAL_BAR_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        // Allow overlap because M3 components expand their semantic bounds to 48dp minimum.
        assertTrue(
            "$text must not overlap the Bar track: text=$textBounds, bar=$barBounds",
            textBounds.bottom <= barBounds.top + 20.0,
        )
    }

    protected fun assertTextIsBelowBar(text: String) {
        val textBounds =
            composeRule
                .onNodeWithText(text, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val barBounds =
            composeRule
                .onNodeWithTag(UNIVERSAL_BAR_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        // Allow overlap because M3 components expand their semantic bounds to 48dp minimum.
        assertTrue(
            "$text must stay below the Bar track: text=$textBounds, bar=$barBounds",
            textBounds.top >= barBounds.bottom - 20.0,
        )
    }
}

@androidx.compose.runtime.Composable
internal fun TestTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    FitDashboardTheme(dynamicColor = false, content = content)
}
