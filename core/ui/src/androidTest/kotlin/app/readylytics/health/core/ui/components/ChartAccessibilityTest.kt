package app.readylytics.health.core.ui.components

import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.domain.repository.SleepSessionData
import app.readylytics.health.domain.repository.SleepStageData
import app.readylytics.health.core.ui.model.HrSample
import app.readylytics.health.ui.workouts.TrimpBreakdownChart
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class ChartAccessibilityTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun hasStateDescription(value: String): SemanticsMatcher =
        SemanticsMatcher("stateDescription is '$value'") { node ->
            node.config.getOrNull(SemanticsProperties.StateDescription) == value
        }

    private fun stateDescriptionStartsWith(prefix: String): SemanticsMatcher =
        SemanticsMatcher("stateDescription starts with '$prefix'") { node ->
            node.config.getOrNull(SemanticsProperties.StateDescription)?.startsWith(prefix) == true
        }

    private fun stateDescriptionContains(substring: String): SemanticsMatcher =
        SemanticsMatcher("stateDescription contains '$substring'") { node ->
            node.config.getOrNull(SemanticsProperties.StateDescription)?.contains(substring) == true
        }

    @Test
    fun hrTimelineChart_accessibilitySemantics() {
        val samples =
            listOf(
                HrSample(1700000000000L, 70),
                HrSample(1700000060000L, 75),
                HrSample(1700000120000L, 80),
            )
        composeTestRule.setContent {
            Surface {
                HrTimelineChart(
                    samples = samples,
                    dayStartMs = 1700000000000L,
                    dayEndMs = 1700000120000L,
                    zone1MinBpm = 60,
                    zone1MaxBpm = 70,
                    zone2MaxBpm = 80,
                    zone3MaxBpm = 90,
                    zone4MaxBpm = 100,
                    zoneId = ZoneId.of("UTC"),
                )
            }
        }

        val canvasNode = composeTestRule.onNodeWithTag("HrTimelineChartCanvas")
        canvasNode.assertExists()

        // Assert initial summary and no selection state
        canvasNode.assertContentDescriptionEquals(
            "Resting heart rate timeline chart showing heart rate values in beats per minute over the sleep period.",
        )
        canvasNode.assert(hasStateDescription("No point selected"))

        // Perform "Next point" action (should select the first point)
        canvasNode.performCustomAccessibilityActionWithLabel("Next point")
        canvasNode.assert(stateDescriptionStartsWith("Selected: 70 bpm at "))

        // Perform "Next point" again (should select the second point)
        canvasNode.performCustomAccessibilityActionWithLabel("Next point")
        canvasNode.assert(stateDescriptionStartsWith("Selected: 75 bpm at "))

        // Perform "Previous point" (should select the first point again)
        canvasNode.performCustomAccessibilityActionWithLabel("Previous point")
        canvasNode.assert(stateDescriptionStartsWith("Selected: 70 bpm at "))

        // Perform "Clear selection"
        canvasNode.performCustomAccessibilityActionWithLabel("Clear selection")
        canvasNode.assert(hasStateDescription("No point selected"))
    }

    @Test
    fun sleepStagesChart_accessibilitySemantics() {
        val session =
            SleepSessionData(
                id = "session1",
                deviceName = "Phone",
                startTime = 1700000000000L,
                endTime = 1700000120000L,
                durationMinutes = 2,
                efficiency = 1f,
                deepSleepMinutes = 1,
                lightSleepMinutes = 1,
                remSleepMinutes = 0,
                awakeMinutes = 0,
            )
        val stages =
            listOf(
                SleepStageData("deep", 1700000000000L, 1700000060000L, 1),
                SleepStageData("light", 1700000060000L, 1700000120000L, 1),
            )
        composeTestRule.setContent {
            Surface {
                SleepStagesChart(
                    session = session,
                    stageTimeline = stages,
                )
            }
        }

        val canvasNode = composeTestRule.onNodeWithTag("SleepStagesChartCanvas")
        canvasNode.assertExists()

        canvasNode.assertContentDescriptionEquals(
            "Sleep stages timeline chart showing Awake, REM, Light, and Deep sleep segments.",
        )
        canvasNode.assert(hasStateDescription("No point selected"))

        // Perform "Next point" to select first segment
        canvasNode.performCustomAccessibilityActionWithLabel("Next point")
        canvasNode.assert(stateDescriptionStartsWith("Selected: Deep stage, duration 1m, starting at "))

        // Perform "Next point" to select second segment
        canvasNode.performCustomAccessibilityActionWithLabel("Next point")
        canvasNode.assert(stateDescriptionStartsWith("Selected: Light stage, duration 1m, starting at "))

        // Perform "Clear selection"
        canvasNode.performCustomAccessibilityActionWithLabel("Clear selection")
        canvasNode.assert(hasStateDescription("No point selected"))
    }

    @Test
    fun stepsBar_accessibilitySemantics() {
        composeTestRule.setContent {
            Surface {
                StepsBar(
                    stepCount = 5000,
                    stepGoal = 10000,
                    dateForTooltip = LocalDate.of(2026, 6, 20),
                )
            }
        }

        val canvasNode = composeTestRule.onNodeWithTag("StepsBarCanvas")
        canvasNode.assertExists()

        canvasNode.assertContentDescriptionEquals("Steps progress bar showing steps count against daily goal.")
        canvasNode.assert(hasStateDescription("No point selected"))

        // Select the steps bar
        canvasNode.performCustomAccessibilityActionWithLabel("Next point")
        canvasNode.assert(stateDescriptionContains("5"))
        canvasNode.assert(stateDescriptionContains("10"))

        // Clear selection
        canvasNode.performCustomAccessibilityActionWithLabel("Clear selection")
        canvasNode.assert(hasStateDescription("No point selected"))
    }

    @Test
    fun trimpBreakdownChart_accessibilitySemantics() {
        val chartData =
            listOf(
                0.0 to 120.0,
                1.0 to 130.0,
                2.0 to 140.0,
            )
        composeTestRule.setContent {
            Surface {
                TrimpBreakdownChart(
                    chartData = chartData,
                    durationMinutes = 3,
                )
            }
        }

        val canvasNode = composeTestRule.onNodeWithTag("TrimpBreakdownChartCanvas")
        canvasNode.assertExists()

        canvasNode.assertContentDescriptionEquals(
            "Heart rate intensity timeline chart showing workout heart rate over the duration of the exercise.",
        )
        canvasNode.assert(hasStateDescription("No point selected"))

        // Select first point
        canvasNode.performCustomAccessibilityActionWithLabel("Next point")
        canvasNode.assert(hasStateDescription("Selected: 120 bpm at 0 min"))

        // Select next point
        canvasNode.performCustomAccessibilityActionWithLabel("Next point")
        canvasNode.assert(hasStateDescription("Selected: 130 bpm at 1 min"))

        // Clear selection
        canvasNode.performCustomAccessibilityActionWithLabel("Clear selection")
        canvasNode.assert(hasStateDescription("No point selected"))
    }
}
