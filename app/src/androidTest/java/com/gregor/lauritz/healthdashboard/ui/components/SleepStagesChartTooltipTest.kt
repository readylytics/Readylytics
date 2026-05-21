package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.material3.Surface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gregor.lauritz.healthdashboard.domain.model.SleepStageType
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionData
import com.gregor.lauritz.healthdashboard.domain.repository.SleepStageData
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SleepStagesChartTooltipTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun sleepStagesChart_showsTooltipOnSegmentTap() {
        val startTime = 1716159600000L // 2024-05-20 01:00:00 UTC
        val endTime = 1716188400000L // 2024-05-20 09:00:00 UTC

        val session =
            SleepSessionData(
                id = "session1",
                startTime = startTime,
                endTime = endTime,
                durationMinutes = 8 * 60,
                efficiency = 0.9f,
                deepSleepMinutes = 60,
                remSleepMinutes = 60,
                lightSleepMinutes = 300,
                awakeMinutes = 60,
                sleepScore = 85f,
                startZoneOffsetSeconds = 0,
                endZoneOffsetSeconds = 0,
                deviceName = "Test Device",
            )

        val stageTimeline =
            listOf(
                SleepStageData(
                    SleepStageType.AWAKE.value,
                    startTime,
                    startTime + 60 * 60_000L,
                    60,
                ), // 60 min
                SleepStageData(
                    SleepStageType.LIGHT.value,
                    startTime + 60 * 60_000L,
                    startTime + 120 * 60_000L,
                    120,
                ), // 120 min
            )

        composeTestRule.setContent {
            Surface {
                SleepStagesChart(
                    session = session,
                    stageTimeline = stageTimeline,
                )
            }
        }

        // Initially, tooltip should not be visible
        composeTestRule.onNodeWithText("60 min").assertDoesNotExist()

        // Tap the first segment (Awake: fraction 0.0 to 0.125, so we tap at 0.06)
        composeTestRule.onNodeWithTag("SleepStagesChartCanvas").performTouchInput {
            click(position = Offset(x = width * 0.06f, y = height * 0.5f))
        }

        // Tooltip showing "60 min" should be visible
        composeTestRule.onNodeWithText("60 min").assertExists()
        composeTestRule.onNodeWithText("60 min").assertIsDisplayed()
    }
}
