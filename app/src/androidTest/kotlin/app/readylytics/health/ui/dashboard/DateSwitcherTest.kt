package app.readylytics.health.ui.dashboard

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class DateSwitcherTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val testToday = LocalDate.of(2026, 6, 18)

    private fun formatted(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))

    @Test
    fun showsTodayLabel_whenSelectedDateIsToday() {
        composeRule.setContent {
            Surface {
                DateSwitcher(
                    selectedDate = testToday,
                    onPreviousDay = {},
                    onNextDay = {},
                    today = testToday,
                )
            }
        }

        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithText(formatted(testToday)).assertIsDisplayed()
    }

    @Test
    fun showsYesterdayLabel_whenSelectedDateIsYesterday() {
        val yesterday = testToday.minusDays(1)
        composeRule.setContent {
            Surface {
                DateSwitcher(
                    selectedDate = yesterday,
                    onPreviousDay = {},
                    onNextDay = {},
                    today = testToday,
                )
            }
        }

        composeRule.onNodeWithText("Yesterday").assertIsDisplayed()
        composeRule.onNodeWithText(formatted(yesterday)).assertIsDisplayed()
    }

    @Test
    fun showsSelectedDateLabel_whenSelectedDateIsOlderThanYesterday() {
        val olderDate = testToday.minusDays(5)
        composeRule.setContent {
            Surface {
                DateSwitcher(
                    selectedDate = olderDate,
                    onPreviousDay = {},
                    onNextDay = {},
                    today = testToday,
                )
            }
        }

        composeRule.onNodeWithText("Selected date").assertIsDisplayed()
        composeRule.onNodeWithText(formatted(olderDate)).assertIsDisplayed()
    }

    @Test
    fun nextDayButtonDisabled_whenSelectedDateIsToday() {
        composeRule.setContent {
            Surface {
                DateSwitcher(
                    selectedDate = testToday,
                    onPreviousDay = {},
                    onNextDay = {},
                    today = testToday,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Next day").assertIsNotEnabled()
    }

    @Test
    fun previousDayButtonDisabled_whenSelectedDateEqualsEarliestDate() {
        val earliest = testToday.minusDays(10)
        composeRule.setContent {
            Surface {
                DateSwitcher(
                    selectedDate = earliest,
                    onPreviousDay = {},
                    onNextDay = {},
                    today = testToday,
                    earliestDate = earliest,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Previous day").assertIsNotEnabled()
    }

    @Test
    fun tappingPill_opensDatePickerDialog() {
        composeRule.setContent {
            Surface {
                DateSwitcher(
                    selectedDate = testToday,
                    onPreviousDay = {},
                    onNextDay = {},
                    today = testToday,
                )
            }
        }

        composeRule.onNodeWithTag("date_pill").performClick()

        composeRule.onNodeWithText("OK").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun tappingPreviousArrow_invokesCallback() {
        var invoked = false
        composeRule.setContent {
            Surface {
                DateSwitcher(
                    selectedDate = testToday.minusDays(2),
                    onPreviousDay = { invoked = true },
                    onNextDay = {},
                    today = testToday,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Previous day").performClick()
        assertTrue(invoked)
    }

    @Test
    fun tappingNextArrow_invokesCallback() {
        var invoked = false
        composeRule.setContent {
            Surface {
                DateSwitcher(
                    selectedDate = testToday.minusDays(2),
                    onPreviousDay = {},
                    onNextDay = { invoked = true },
                    today = testToday,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Next day").performClick()
        assertTrue(invoked)
    }

    @Test
    fun disabledState_blocksAllInteractions() {
        var previousInvoked = false
        var nextInvoked = false
        composeRule.setContent {
            Surface {
                DateSwitcher(
                    selectedDate = testToday.minusDays(2),
                    onPreviousDay = { previousInvoked = true },
                    onNextDay = { nextInvoked = true },
                    today = testToday,
                    enabled = false,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Previous day").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Next day").assertIsNotEnabled()
        composeRule.onNodeWithTag("date_pill").assertIsNotEnabled()
        assertFalse(previousInvoked)
        assertFalse(nextInvoked)
    }
}
