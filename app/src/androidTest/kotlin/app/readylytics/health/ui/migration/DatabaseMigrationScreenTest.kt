package app.readylytics.health.ui.migration

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.readylytics.health.domain.migration.DatabaseMigrationProgress
import app.readylytics.health.domain.migration.DatabaseReadiness
import app.readylytics.health.domain.migration.V7MigrationPhase
import app.readylytics.health.ui.theme.FitDashboardTheme
import org.junit.Rule
import org.junit.Test

class DatabaseMigrationScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun preparingFromV5IsIndeterminate() {
        setScreen(DatabaseReadiness.MigrationRequired(fromVersion = 5))

        composeRule.onNodeWithText("Updating your health database").assertIsDisplayed()
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun preparingFromV6IsIndeterminate() {
        setScreen(DatabaseReadiness.MigrationRequired(fromVersion = 6))

        composeRule.onNodeWithText("Updating your health database").assertIsDisplayed()
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun copiedRowsAreShownAsDeterminateProgress() {
        setScreen(
            readiness = DatabaseReadiness.MigrationRequired(fromVersion = 6),
            progress =
                DatabaseMigrationProgress(
                    phase = V7MigrationPhase.COPY_HEART_RATE,
                    copiedRows = 42_000,
                    totalRows = 100_000,
                ),
        )

        composeRule.onNodeWithText("42,000 of 100,000 records").assertIsDisplayed()
        composeRule
            .onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.42f, 0f..1f, 0)))
            .assertIsDisplayed()
    }

    @Test
    fun insufficientSpaceShowsGuidanceAndRetry() {
        setScreen(DatabaseReadiness.InsufficientSpace(requiredBytes = 1_500, availableBytes = 500))

        composeRule.onNode(hasText("More device storage is required", substring = true)).assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun validationFailureIsFailClosedWithoutDestructiveAction() {
        setScreen(DatabaseReadiness.Failed("row-count validation failed"))

        composeRule
            .onNodeWithText(
                "Your existing data is unchanged. Retry the update or export diagnostics before continuing.",
            ).assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.onAllNodesWithText("Reset", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Delete", substring = true).assertCountEquals(0)
    }

    private fun setScreen(
        readiness: DatabaseReadiness,
        progress: DatabaseMigrationProgress? = null,
    ) {
        composeRule.setContent {
            FitDashboardTheme {
                DatabaseMigrationScreen(
                    readiness = readiness,
                    progress = progress,
                    onRetry = {},
                )
            }
        }
    }
}
