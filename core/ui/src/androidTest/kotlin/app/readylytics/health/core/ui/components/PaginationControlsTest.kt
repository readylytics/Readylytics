package app.readylytics.health.core.ui.components

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.core.ui.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaginationControlsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun string(
        id: Int,
        vararg formatArgs: Any,
    ): String = context.getString(id, *formatArgs)

    @Test
    fun controlsAreHiddenForASinglePage() {
        composeRule.setContent {
            PaginationControls(
                currentPage = 1,
                totalPages = 1,
                onPreviousPage = {},
                onNextPage = {},
            )
        }

        composeRule.onNodeWithText(string(R.string.pagination_page_info, 1, 1)).assertDoesNotExist()
    }

    @Test
    fun previousAndNextButtonsFollowPageBoundaries() {
        composeRule.setContent {
            PaginationControls(
                currentPage = 2,
                totalPages = 3,
                onPreviousPage = {},
                onNextPage = {},
            )
        }

        composeRule.onNodeWithContentDescription(string(R.string.pagination_button_previous)).assertIsEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.pagination_button_next)).assertIsEnabled()
    }

    @Test
    fun previousButtonDisabledOnFirstPage() {
        composeRule.setContent {
            PaginationControls(
                currentPage = 1,
                totalPages = 3,
                onPreviousPage = {},
                onNextPage = {},
            )
        }

        composeRule.onNodeWithContentDescription(string(R.string.pagination_button_previous)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.pagination_button_next)).assertIsEnabled()
    }

    @Test
    fun nextButtonDisabledOnLastPage() {
        composeRule.setContent {
            PaginationControls(
                currentPage = 3,
                totalPages = 3,
                onPreviousPage = {},
                onNextPage = {},
            )
        }

        composeRule.onNodeWithContentDescription(string(R.string.pagination_button_previous)).assertIsEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.pagination_button_next)).assertIsNotEnabled()
    }

    @Test
    fun callbacksAreDispatchedByButtons() {
        var previous = 0
        var next = 0
        composeRule.setContent {
            PaginationControls(
                currentPage = 2,
                totalPages = 3,
                onPreviousPage = { previous++ },
                onNextPage = { next++ },
            )
        }

        composeRule.onNodeWithContentDescription(string(R.string.pagination_button_previous)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.pagination_button_next)).performClick()
        assertEquals(1, previous)
        assertEquals(1, next)
    }
}
