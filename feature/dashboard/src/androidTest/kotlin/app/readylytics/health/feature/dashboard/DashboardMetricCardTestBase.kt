package app.readylytics.health.feature.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.core.ui.components.metriccard.UniversalCardDisplayMode
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCardSpec
import app.readylytics.health.domain.model.MetricStatus
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
abstract class UniversalMetricCardTestBase {
    @get:Rule
    val composeRule = createComposeRule()

    // Resource-backed string lookups (rather than hardcoded literals) so semantics assertions
    // stay correct if wording is localized/changed, matching the pattern used by
    // core/ui's DateSwitcherTest.
    protected val context = InstrumentationRegistry.getInstrumentation().targetContext

    protected fun string(id: Int): String = context.getString(id)

    protected fun string(
        id: Int,
        vararg args: Any,
    ): String = context.getString(id, *args)

    protected val testSpec =
        UniversalMetricCardSpec(
            cardId = CardId.SLEEP_SCORE,
            legacyDefaultMode = UniversalCardDisplayMode.GAUGE,
            supportedModes =
                listOf(
                    UniversalCardDisplayMode.GAUGE,
                    UniversalCardDisplayMode.BAR,
                    UniversalCardDisplayMode.VALUE,
                ),
        )

    protected val defaultPresentation =
        UniversalMetricPresentation(
            title = "Test Metric",
            valueText = "85",
            unitText = "pts",
            secondaryText = "Good",
            status = MetricStatus.OPTIMAL,
            tooltip = "Tooltip text",
            accessibilityDescription = "Card description",
            visual =
                UniversalMetricVisual.Score(
                    rawValue = 85f,
                    minValue = 0f,
                    maxValue = 100f,
                    markerFraction = 0.85f,
                    unavailableReason = null,
                ),
        )
}
