package app.readylytics.health.feature.vitals.heartrate

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import app.readylytics.health.core.designsystem.FitDashboardTheme
import app.readylytics.health.domain.model.MetricStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HeartRateDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `average card omits classifier status`() {
        composeRule.setContent {
            FitDashboardTheme {
                HeartRateDetailScreen(
                    uiState =
                        HeartRateDetailUiState(
                            minBpm = 50,
                            maxBpm = 100,
                            avgBpm = 72,
                            averageStatus = MetricStatus.NEUTRAL,
                            isLoading = false,
                        ),
                    onBack = {},
                    onPreviousDay = {},
                    onNextDay = {},
                )
            }
        }

        composeRule.onAllNodesWithText("Neutral").assertCountEquals(0)
    }
}
