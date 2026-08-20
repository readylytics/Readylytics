package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.readylytics.health.core.designsystem.FitDashboardTheme
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.preferences.UnitSystem
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.util.ProjectedPoint
import app.readylytics.health.core.scoring.domain.scoring.WorkoutLoadClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RouteContourCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun routeUiState_defaultValues() {
        val state = RouteUiState()
        assertEquals(RouteDataState.NotAvailable, state.state)
        assertTrue(state.projectedPoints.isEmpty())
        assertEquals("", state.scaleLabel)
        assertEquals(0f, state.scaleWidthFraction, 0.001f)
    }

    @Test
    fun routeDataState_allEntriesPresent() {
        val entries = RouteDataState.entries
        assertEquals(3, entries.size)
        assertTrue(entries.contains(RouteDataState.Available))
        assertTrue(entries.contains(RouteDataState.PermissionRequired))
        assertTrue(entries.contains(RouteDataState.NotAvailable))
    }

    @Test
    fun routeContourCard_notAvailable_doesNotRender() {
        composeRule.setContent {
            FitDashboardTheme {
                RouteContourCard(
                    uiState = RouteUiState(state = RouteDataState.NotAvailable),
                    onGrantPermissionClick = {},
                )
            }
        }

        composeRule.onNodeWithText("Route Contour").assertDoesNotExist()
    }

    @Test
    fun routeContourCard_permissionRequired_showsPromptAndHandlesClick() {
        var clicked = false
        composeRule.setContent {
            FitDashboardTheme {
                RouteContourCard(
                    uiState = RouteUiState(state = RouteDataState.PermissionRequired),
                    onGrantPermissionClick = { clicked = true },
                )
            }
        }

        composeRule.onNodeWithText("Route Contour").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Grant exercise route permissions in Health Connect to view GPS routes.",
            ).assertIsDisplayed()
        val button = composeRule.onNodeWithText("Grant permission")
        button.assertIsDisplayed()
        button.performClick()
        assertTrue(clicked)
    }

    @Test
    fun routeContourCard_available_showsContourAndScale() {
        val points =
            listOf(
                ProjectedPoint(0.1f, 0.2f, 52.5, 13.4, 50.0, 1000L),
                ProjectedPoint(0.9f, 0.8f, 52.6, 13.5, 55.0, 2000L),
            )
        val uiState =
            RouteUiState(
                state = RouteDataState.Available,
                projectedPoints = points,
                scaleLabel = "500 m",
                scaleWidthFraction = 0.5f,
            )

        composeRule.setContent {
            FitDashboardTheme {
                RouteContourCard(
                    uiState = uiState,
                    onGrantPermissionClick = {},
                )
            }
        }

        composeRule.onNodeWithText("Route Contour").assertIsDisplayed()
        // The legend names the grid pitch, so a square on the map reads as a distance.
        composeRule.onNodeWithText("Grid: 500 m").assertIsDisplayed()
    }

    @Test
    fun workoutMetricsDisplay_withGpsRunningMetrics_showsPaceAndDistance() {
        val workout =
            WorkoutData(
                id = "w1",
                startTime = 1000L,
                endTime = 70000L,
                exerciseType = "Running",
                durationMinutes = 30,
                zone1Minutes = 5f,
                zone2Minutes = 10f,
                zone3Minutes = 10f,
                zone4Minutes = 5f,
                zone5Minutes = 0f,
                trimp = 45f,
                avgHr = 155f,
                totalDistanceMeters = 5000f,
                avgSpeedKmh = 10f,
                elevationGainMeters = 50f,
            )

        composeRule.setContent {
            FitDashboardTheme {
                WorkoutMetricsDisplayHarness(
                    workout = workout,
                    computedTrimp = 45,
                    gainedStrain = 10.5f,
                    gainedStrainDisplay = "10.5",
                    ras = 15f,
                    classification = null,
                    unitSystem = UnitSystem.METRIC,
                )
            }
        }

        composeRule.onNodeWithText("Distance", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("5.0", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("km", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Avg. Pace", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("6:00", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("min/km", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Elevation Gain", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("50", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("m", useUnmergedTree = true).assertExists()
    }

    @Test
    fun workoutMetricsDisplay_withCyclingMetrics_showsSpeedInsteadOfPace() {
        val workout =
            WorkoutData(
                id = "w2",
                startTime = 1000L,
                endTime = 70000L,
                exerciseType = "Cycling",
                durationMinutes = 60,
                zone1Minutes = 10f,
                zone2Minutes = 20f,
                zone3Minutes = 20f,
                zone4Minutes = 10f,
                zone5Minutes = 0f,
                trimp = 80f,
                avgHr = 140f,
                totalDistanceMeters = 25000f,
                avgSpeedKmh = 25f,
                elevationGainMeters = 120f,
            )

        composeRule.setContent {
            FitDashboardTheme {
                WorkoutMetricsDisplayHarness(
                    workout = workout,
                    computedTrimp = 80,
                    gainedStrain = 14.0f,
                    gainedStrainDisplay = "14.0",
                    ras = 25f,
                    classification = null,
                    unitSystem = UnitSystem.METRIC,
                )
            }
        }

        composeRule.onNodeWithText("Distance", useUnmergedTree = true).assertExists()
        composeRule.onAllNodesWithText("25.0", useUnmergedTree = true).assertCountEquals(2)
        composeRule.onNodeWithText("km", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Avg. Speed", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("km/h", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Elevation Gain", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("120", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("m", useUnmergedTree = true).assertExists()
    }

    @Test
    fun workoutMetricsDisplay_withoutGpsMetrics_doesNotShowGpsRows() {
        val workout =
            WorkoutData(
                id = "w3",
                startTime = 1000L,
                endTime = 70000L,
                exerciseType = "Strength Training",
                durationMinutes = 45,
                zone1Minutes = 10f,
                zone2Minutes = 20f,
                zone3Minutes = 15f,
                zone4Minutes = 0f,
                zone5Minutes = 0f,
                trimp = 35f,
                avgHr = 125f,
                totalDistanceMeters = null,
                avgSpeedKmh = null,
                elevationGainMeters = null,
            )

        composeRule.setContent {
            FitDashboardTheme {
                WorkoutMetricsDisplayHarness(
                    workout = workout,
                    computedTrimp = 35,
                    gainedStrain = 8.0f,
                    gainedStrainDisplay = "8.0",
                    ras = 10f,
                    classification = null,
                    unitSystem = UnitSystem.METRIC,
                )
            }
        }

        composeRule.onNodeWithText("Training load", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Distance", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Avg. Pace", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Avg. Speed", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Elevation Gain", useUnmergedTree = true).assertDoesNotExist()
    }
}

@Composable
private fun WorkoutMetricsDisplayHarness(
    workout: WorkoutData,
    computedTrimp: Int?,
    gainedStrain: Float?,
    gainedStrainDisplay: String,
    ras: Float?,
    classification: WorkoutLoadClassification?,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    displayElevationGainMeters: Float? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        WorkoutDetailHeader(workout)

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                TrainingLoadTile(computedTrimp, workout, Modifier.weight(1f))
                AvgPulseTile(workout, Modifier.weight(1f))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                GainedStrainTile(gainedStrain, gainedStrainDisplay, Modifier.weight(1f))
                RasTile(ras, Modifier.weight(1f))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                OverallLoadTile(classification, Modifier.weight(1f))
                IntensityTile(classification, Modifier.weight(1f))
            }

            val hasGpsMetrics =
                workout.totalDistanceMeters != null ||
                    workout.avgSpeedKmh != null ||
                    workout.elevationGainMeters != null ||
                    displayElevationGainMeters != null

            if (hasGpsMetrics) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    DistanceTile(workout, unitSystem, Modifier.weight(1f))
                    AvgPaceSpeedTile(workout, unitSystem, Modifier.weight(1f))
                }

                val elevationGain = displayElevationGainMeters ?: workout.elevationGainMeters
                if (elevationGain != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        ElevationGainTile(elevationGain, unitSystem, Modifier.weight(1f))
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        ZoneBreakdownCard(workout)
    }
}
