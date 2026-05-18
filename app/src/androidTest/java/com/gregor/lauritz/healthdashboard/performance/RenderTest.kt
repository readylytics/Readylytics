package com.gregor.lauritz.healthdashboard.performance

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gregor.lauritz.healthdashboard.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RenderTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun dashboardScrollFrameDrops() {
        val frameDurationsMs = mutableListOf<Long>()
        val frameCount = 50

        repeat(frameCount) {
            val start = System.nanoTime()
            composeRule
                .onNodeWithTag("dashboard_lazy_column", useUnmergedTree = true)
                .performTouchInput { swipeUp(startY = 800f, endY = 200f, durationMillis = 300) }
            composeRule.waitForIdle()
            frameDurationsMs.add((System.nanoTime() - start) / 1_000_000L)
        }

        val droppedFrames = frameDurationsMs.count { it > 16L }
        val dropPercent = droppedFrames * 100 / frameCount
        assertTrue(
            "Frame drop rate should be <2% during scroll, got $dropPercent% ($droppedFrames/$frameCount frames >16ms)",
            dropPercent < 2,
        )
    }
}
