package app.readylytics.health.feature.workouts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.readylytics.health.core.designsystem.FitDashboardTheme
import app.readylytics.health.core.model.domain.workouts.FatigueCurvePoint
import app.readylytics.health.core.model.domain.workouts.FatigueCurveRange
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResidualFatigueCurveChartTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun residualFatigueCurveChart_emptyData_displaysEmptyMessage() {
        composeRule.setContent {
            FitDashboardTheme {
                ResidualFatigueCurveChart(
                    points = emptyList(),
                    range = FatigueCurveRange.ONE_DAY,
                    isLoading = false,
                )
            }
        }

        composeRule.onNodeWithText("No residual fatigue data available for this range.").assertIsDisplayed()
    }

    @Test
    fun residualFatigueCurveChart_withPoints1D_rendersChart() {
        val points =
            listOf(
                FatigueCurvePoint(timestampMs = 0L, timeMinutesFromStart = 0f, fatigueValue = 10f),
                FatigueCurvePoint(timestampMs = 900_000L, timeMinutesFromStart = 15f, fatigueValue = 25f),
                FatigueCurvePoint(timestampMs = 1_800_000L, timeMinutesFromStart = 30f, fatigueValue = 20f),
            )

        composeRule.setContent {
            FitDashboardTheme {
                ResidualFatigueCurveChart(
                    points = points,
                    range = FatigueCurveRange.ONE_DAY,
                    isLoading = false,
                )
            }
        }

        composeRule.onNodeWithTag("ResidualFatigueCurveChartCanvas").assertIsDisplayed()
    }

    @Test
    fun residualFatigueCurveChart_withPoints3DAnd7D_rendersChart() {
        val points =
            listOf(
                FatigueCurvePoint(timestampMs = 0L, timeMinutesFromStart = 0f, fatigueValue = 10f),
                FatigueCurvePoint(timestampMs = 86_400_000L, timeMinutesFromStart = 1440f, fatigueValue = 25f),
            )

        composeRule.setContent {
            FitDashboardTheme {
                ResidualFatigueCurveChart(
                    points = points,
                    range = FatigueCurveRange.THREE_DAYS,
                    isLoading = false,
                )
            }
        }

        composeRule.onNodeWithTag("ResidualFatigueCurveChartCanvas").assertIsDisplayed()
    }
}
