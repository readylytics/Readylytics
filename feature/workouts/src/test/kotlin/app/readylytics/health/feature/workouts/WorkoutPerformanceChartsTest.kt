package app.readylytics.health.feature.workouts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.readylytics.health.core.designsystem.FitDashboardTheme
import app.readylytics.health.core.model.domain.preferences.UnitSystem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkoutPerformanceChartsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun workoutPerformanceCharts_emptyData_doesNotRender() {
        composeRule.setContent {
            FitDashboardTheme {
                WorkoutPerformanceCharts(
                    paceSpeedData = emptyList(),
                    elevationData = emptyList(),
                    isPaceMode = true,
                )
            }
        }

        composeRule.onNodeWithText("Pace Profile").assertDoesNotExist()
        composeRule.onNodeWithText("Speed Profile").assertDoesNotExist()
        composeRule.onNodeWithText("Elevation Profile").assertDoesNotExist()
    }

    @Test
    fun workoutPerformanceCharts_paceMode_rendersPaceProfile() {
        val paceData = listOf(0.0 to 5.5, 1.0 to 5.2, 2.0 to 5.0)
        composeRule.setContent {
            FitDashboardTheme {
                WorkoutPerformanceCharts(
                    paceSpeedData = paceData,
                    elevationData = emptyList(),
                    isPaceMode = true,
                    unitSystem = UnitSystem.METRIC,
                )
            }
        }

        composeRule.onNodeWithText("Pace Profile").assertIsDisplayed()
        composeRule.onNodeWithText("Elevation Profile").assertDoesNotExist()
    }

    @Test
    fun workoutPerformanceCharts_speedMode_rendersSpeedProfile() {
        val speedData = listOf(0.0 to 25.0, 5.0 to 28.0, 10.0 to 26.0)
        composeRule.setContent {
            FitDashboardTheme {
                WorkoutPerformanceCharts(
                    paceSpeedData = speedData,
                    elevationData = emptyList(),
                    isPaceMode = false,
                    unitSystem = UnitSystem.METRIC,
                )
            }
        }

        composeRule.onNodeWithText("Speed Profile").assertIsDisplayed()
        composeRule.onNodeWithText("Elevation Profile").assertDoesNotExist()
    }

    @Test
    fun workoutPerformanceCharts_elevationData_rendersElevationProfile() {
        val elevationData = listOf(0.0 to 100.0, 1.0 to 120.0, 2.0 to 150.0)
        composeRule.setContent {
            FitDashboardTheme {
                WorkoutPerformanceCharts(
                    paceSpeedData = emptyList(),
                    elevationData = elevationData,
                    isPaceMode = true,
                    unitSystem = UnitSystem.METRIC,
                )
            }
        }

        composeRule.onNodeWithText("Elevation Profile").assertIsDisplayed()
        composeRule.onNodeWithText("Pace Profile").assertDoesNotExist()
    }

    @Test
    fun workoutPerformanceCharts_bothPaceAndElevation_rendersBothCards() {
        val paceData = listOf(0.0 to 6.0, 1.0 to 5.5)
        val elevationData = listOf(0.0 to 50.0, 1.0 to 60.0)
        composeRule.setContent {
            FitDashboardTheme {
                WorkoutPerformanceCharts(
                    paceSpeedData = paceData,
                    elevationData = elevationData,
                    isPaceMode = true,
                    unitSystem = UnitSystem.METRIC,
                )
            }
        }

        composeRule.onNodeWithText("Pace Profile").assertIsDisplayed()
        composeRule.onNodeWithText("Elevation Profile").assertIsDisplayed()
    }
}
