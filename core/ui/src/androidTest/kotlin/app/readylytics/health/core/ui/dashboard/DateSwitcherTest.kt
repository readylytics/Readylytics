package app.readylytics.health.core.ui.dashboard

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.core.ui.R
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

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testToday = LocalDate.of(2026, 6, 18)

    private fun string(id: Int): String = context.getString(id)

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

        composeRule.onNodeWithText(string(R.string.date_switcher_label_today)).assertIsDisplayed()
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

        composeRule.onNodeWithText(string(R.string.date_switcher_label_yesterday)).assertIsDisplayed()
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

        composeRule.onNodeWithText(string(R.string.date_switcher_label_selected)).assertIsDisplayed()
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

        composeRule.onNodeWithContentDescription(string(R.string.accessibility_next_day)).assertIsNotEnabled()
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

        composeRule.onNodeWithContentDescription(string(R.string.accessibility_prev_day)).assertIsNotEnabled()
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

        composeRule.onNodeWithText(string(android.R.string.ok)).assertIsDisplayed()
        composeRule.onNodeWithText(string(android.R.string.cancel)).assertIsDisplayed()
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

        composeRule.onNodeWithContentDescription(string(R.string.accessibility_prev_day)).performClick()
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

        composeRule.onNodeWithContentDescription(string(R.string.accessibility_next_day)).performClick()
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

        composeRule.onNodeWithContentDescription(string(R.string.accessibility_prev_day)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.accessibility_next_day)).assertIsNotEnabled()
        composeRule.onNodeWithTag("date_pill").assertIsNotEnabled()
        assertFalse(previousInvoked)
        assertFalse(nextInvoked)
    }
}
