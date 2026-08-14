package app.readylytics.health.core.ui.sync

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.readylytics.health.domain.sync.RecalcProgress
import app.readylytics.health.domain.sync.ResyncPhase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncProgressScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `indeterminate INGEST progress renders the page count, never a stale total`() {
        // H1: the daily-sync path streams HR/HRV pages with no real total (total = 0), so this
        // must switch to the page-only string instead of formatting "batch 3 of 0".
        composeTestRule.setContent {
            SyncProgressScreen(
                progress = RecalcProgress(ResyncPhase.INGEST, current = 3, total = 0),
                onDownloadLogs = {},
                onContinueInBackground = {},
                logText = null,
                onLogsVisibilityChanged = {},
            )
        }

        composeTestRule.onNodeWithText("Fetching Health Connect data (page 3)…").assertExists()
        composeTestRule.onAllNodesWithText("Fetching Health Connect data (batch 3 of 0)…").assertCountEquals(0)
    }

    @Test
    fun `determinate INGEST progress still renders batch of total`() {
        composeTestRule.setContent {
            SyncProgressScreen(
                progress = RecalcProgress(ResyncPhase.INGEST, current = 2, total = 5),
                onDownloadLogs = {},
                onContinueInBackground = {},
                logText = null,
                onLogsVisibilityChanged = {},
            )
        }

        composeTestRule.onNodeWithText("Fetching Health Connect data (batch 2 of 5)…").assertExists()
    }
}
