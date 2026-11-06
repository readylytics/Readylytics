package app.readylytics.health.core.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChartAutoDismissTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `dismisses using latest lambda after recomposition`() {
        var generation by mutableStateOf(0)
        var parentScrolling by mutableStateOf(false)
        var firstCalls = 0
        var secondCalls = 0

        composeTestRule.setContent {
            AutoDismissChartSelection(
                scrollState = rememberVicoScrollState(),
                parentScrollInProgress = { parentScrolling },
                onDismiss = if (generation == 0) ({ firstCalls++ }) else ({ secondCalls++ }),
            )
        }
        composeTestRule.waitForIdle()

        generation = 1
        composeTestRule.waitForIdle()
        val firstBefore = firstCalls
        val secondBefore = secondCalls

        parentScrolling = true
        composeTestRule.waitForIdle()

        assertEquals("stale lambda must not be called", firstBefore, firstCalls)
        assertEquals("latest lambda must be called once", secondBefore + 1, secondCalls)
    }
}
