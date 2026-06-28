package app.readylytics.health.ui.steps

import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.repository.SelectedDateRepository
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.repository.DailySummaryRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class StepDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: StepDetailViewModel
    private lateinit var repository: DailySummaryRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var selectedDateRepo: SelectedDateRepository

    private val selectedDateFlow = MutableStateFlow(LocalDate.now())
    private val earliestDateFlow = MutableStateFlow<LocalDate?>(null)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        repository =
            mockk {
                every { observeByDate(any()) } returns MutableStateFlow(null)
                every { observeSince(any()) } returns MutableStateFlow(emptyList())
            }
        settingsRepo =
            mockk {
                every { userPreferences } returns MutableStateFlow(UserPreferences())
            }
        selectedDateRepo =
            mockk {
                every { selectedDate } returns selectedDateFlow
                every { earliestDate } returns earliestDateFlow
                coEvery { updateSelectedDate(any()) } answers {
                    selectedDateFlow.value = firstArg()
                }
            }
    }

    private fun createViewModel(): StepDetailViewModel =
        StepDetailViewModel(
            dailySummaryRepository = repository,
            selectedDateRepository = selectedDateRepo,
            settingsRepo = settingsRepo,
            defaultDispatcher = testDispatcher,
        )

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
            testDispatcher.scheduler.advanceUntilIdle()
        }
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is correct`() =
        runTest {
            viewModel = createViewModel()
            val state = viewModel.uiState.value
            assertEquals(TimeRange.SEVEN_DAYS, state.selectedRange)
            assertEquals(emptyList<DailyDataPoint>(), state.dailySteps)
        }

    @Test
    fun `onRangeSelected updates range`() =
        runTest {
            viewModel = createViewModel()
            viewModel.onRangeSelected(TimeRange.THIRTY_DAYS)
            val state = viewModel.uiState.first { it.selectedRange == TimeRange.THIRTY_DAYS }
            assertEquals(TimeRange.THIRTY_DAYS, state.selectedRange)
        }

    @Test
    fun `uiState updates when repository emits data`() =
        runTest {
            val today = LocalDate.now()
            val latestSummary = DailySummary(date = today, stepCount = 8500)

            every { repository.observeByDate(any()) } returns MutableStateFlow(latestSummary)
            every { repository.observeSince(any()) } returns MutableStateFlow(listOf(latestSummary))

            viewModel = createViewModel()

            val state = viewModel.uiState.first { it.latestSummary != null }

            assertEquals(8500, state.latestSummary?.stepCount)
            assertEquals(7, state.dailySteps.size)
            val dataEntry = state.dailySteps.last { it.value != null }
            assertEquals(8500f, dataEntry.value)
            val nullCount = state.dailySteps.count { it.value == null }
            assertEquals(6, nullCount)
        }
}
