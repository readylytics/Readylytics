package app.readylytics.health.feature.workouts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.readylytics.health.core.designsystem.FitDashboardTheme
import app.readylytics.health.core.model.domain.service.DateRange
import app.readylytics.health.core.scoring.domain.workouts.weekly.DailyTrainingVolume
import app.readylytics.health.core.scoring.domain.workouts.weekly.PeriodComparison
import app.readylytics.health.core.scoring.domain.workouts.weekly.PeriodTotals
import app.readylytics.health.core.scoring.domain.workouts.weekly.WeeklyTrainingStats
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeeklyVolumeTrendChartTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val weekStart = LocalDate.of(2026, 8, 17)

    private fun sampleStats(): WeeklyTrainingStats {
        val daily =
            (0 until 7).map { offset ->
                DailyTrainingVolume(
                    dayOffset = offset,
                    date = weekStart.plusDays(offset.toLong()),
                    currentWeekDurationMinutes = if (offset <= 3) 30 else null,
                    previousWeekDurationMinutes = 25,
                    currentWeekCumulativeMinutes = if (offset <= 3) (offset + 1) * 30 else null,
                    previousWeekCumulativeMinutes = (offset + 1) * 25,
                )
            }
        return WeeklyTrainingStats(
            currentPeriod = DateRange(weekStart, weekStart.plusDays(3)),
            previousPeriod = DateRange(weekStart.minusWeeks(1), weekStart.minusWeeks(1).plusDays(3)),
            currentWeek = PeriodTotals(totalDurationMinutes = 222, workoutCount = 4, activeDays = 4),
            previousWeek = PeriodTotals(totalDurationMinutes = 100, workoutCount = 3, activeDays = 3),
            comparison =
                PeriodComparison(
                    durationDeltaMinutes = 122,
                    durationPercentChange = 122f,
                    workoutCountDelta = 1,
                    activeDaysDelta = 1,
                ),
            cumulativeDailyTraining = daily,
            activityVolumes = emptyList(),
            trainingMix = emptyList(),
        )
    }

    private fun emptyStats(): WeeklyTrainingStats {
        val daily =
            (0 until 7).map { offset ->
                DailyTrainingVolume(
                    dayOffset = offset,
                    date = weekStart.plusDays(offset.toLong()),
                    currentWeekDurationMinutes = 0,
                    previousWeekDurationMinutes = 0,
                    currentWeekCumulativeMinutes = 0,
                    previousWeekCumulativeMinutes = 0,
                )
            }
        return WeeklyTrainingStats(
            currentPeriod = DateRange(weekStart, weekStart),
            previousPeriod = DateRange(weekStart.minusWeeks(1), weekStart.minusWeeks(1)),
            currentWeek = PeriodTotals(totalDurationMinutes = 0, workoutCount = 0, activeDays = 0),
            previousWeek = PeriodTotals(totalDurationMinutes = 0, workoutCount = 0, activeDays = 0),
            comparison =
                PeriodComparison(
                    durationDeltaMinutes = 0,
                    durationPercentChange = null,
                    workoutCountDelta = 0,
                    activeDaysDelta = 0,
                ),
            cumulativeDailyTraining = daily,
            activityVolumes = emptyList(),
            trainingMix = emptyList(),
        )
    }

    @Test
    fun weeklyVolumeTrendChartCard_withData_rendersHeadlineAndTitle() {
        composeRule.setContent {
            FitDashboardTheme {
                WeeklyVolumeTrendChartCard(stats = sampleStats(), isLoading = false)
            }
        }

        composeRule.onNodeWithText("This week vs last week").assertIsDisplayed()
        composeRule.onNodeWithText("3h 42m").assertIsDisplayed()
    }

    @Test
    fun weeklyVolumeTrendChartCard_noData_showsEmptyState() {
        composeRule.setContent {
            FitDashboardTheme {
                WeeklyVolumeTrendChartCard(stats = emptyStats(), isLoading = false)
            }
        }

        composeRule.onNodeWithText("No data available").assertIsDisplayed()
    }

    @Test
    fun weeklyVolumeTrendChartCard_loading_doesNotCrashOrShowTitle() {
        composeRule.setContent {
            FitDashboardTheme {
                WeeklyVolumeTrendChartCard(stats = null, isLoading = true)
            }
        }

        composeRule.onNodeWithText("This week vs last week").assertDoesNotExist()
    }
}
