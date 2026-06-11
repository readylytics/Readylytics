package app.readylytics.health.ui.bodyfat

import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.data.preferences.Gender
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.repository.SelectedDateRepository
import app.readylytics.health.domain.repository.BodyFatRepository
import app.readylytics.health.ui.common.TimeRange
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BodyFatDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: BodyFatDetailViewModel
    private lateinit var bodyFatRepository: BodyFatRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var selectedDateRepo: SelectedDateRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        bodyFatRepository =
            mockk {
                coEvery { getByDateRange(any(), any()) } returns emptyList()
                coEvery { getLatest() } returns null
            }
        settingsRepo =
            mockk {
                every { userPreferences } returns MutableStateFlow(UserPreferences())
            }
        val mockDao =
            mockk<app.readylytics.health.data.local.dao.DailySummaryDao> {
                every { observeEarliestDateMs() } returns flowOf(null)
            }
        selectedDateRepo =
            SelectedDateRepository(
                dao = mockDao,
                appScope = CoroutineScope(testDispatcher),
            )
    }

    private fun createViewModel(): BodyFatDetailViewModel =
        BodyFatDetailViewModel(
            bodyFatRepository = bodyFatRepository,
            settingsRepo = settingsRepo,
            selectedDateRepository = selectedDateRepo,
        )

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has null bodyFatDisplay`() =
        runTest {
            viewModel = createViewModel()
            val state = viewModel.uiState.value
            assertNull(state.bodyFatDisplay)
        }

    @Test
    fun `bodyFatDisplay formats value with one decimal and percent sign`() =
        runTest {
            val record =
                BodyFatRecordEntity(
                    id = "1",
                    timestampMs = System.currentTimeMillis(),
                    bodyFatPercent = 18.5f,
                )
            coEvery { bodyFatRepository.getLatest() } returns record

            viewModel = createViewModel()

            val state = viewModel.uiState.first { it.bodyFatDisplay != null }

            assertEquals("18.5", state.bodyFatDisplay)
        }

    @Test
    fun `bodyFatDisplay is null when no latest record`() =
        runTest {
            coEvery { bodyFatRepository.getLatest() } returns null

            viewModel = createViewModel()

            val state = viewModel.uiState.first { it.latestBodyFat == null }

            assertNull(state.bodyFatDisplay)
        }

    @Test
    fun `optimalRangeDisplay formats male age 30 correctly`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(
                    UserPreferences(age = 30, gender = Gender.MALE),
                )

            viewModel = createViewModel()

            val state = viewModel.uiState.first { it.optimalRangeMax > 0f }

            assertEquals("0–19.0%", state.optimalRangeDisplay)
        }

    @Test
    fun `optimalRangeDisplay formats female age 50 correctly`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(
                    UserPreferences(age = 50, gender = Gender.FEMALE),
                )

            viewModel = createViewModel()

            val state = viewModel.uiState.first { it.optimalRangeMax > 0f }

            assertEquals("0–34.0%", state.optimalRangeDisplay)
        }

    @Test
    fun `onRangeSelected updates selectedRange`() =
        runTest {
            viewModel = createViewModel()
            viewModel.onRangeSelected(TimeRange.THIRTY_DAYS)
            val state = viewModel.uiState.first { it.selectedRange == TimeRange.THIRTY_DAYS }
            assertEquals(TimeRange.THIRTY_DAYS, state.selectedRange)
        }
}
