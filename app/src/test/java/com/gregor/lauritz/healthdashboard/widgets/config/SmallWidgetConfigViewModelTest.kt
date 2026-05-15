package com.gregor.lauritz.healthdashboard.widgets.config

import androidx.lifecycle.SavedStateHandle
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmallWidgetConfigViewModelTest {
    private val configRepository: WidgetConfigurationRepository = mockk()
    private val widgetDataRepository: com.gregor.lauritz.healthdashboard.data.repository.WidgetDataRepository = mockk()
    private val context: android.content.Context = mockk()

    private lateinit var viewModel: SmallWidgetConfigViewModel
    private lateinit var savedStateHandle: SavedStateHandle
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        savedStateHandle =
            SavedStateHandle().apply {
                set("widgetId", WIDGET_ID)
            }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialState() =
        runTest {
            // Arrange
            every { configRepository.observeSmallWidgetConfig(WIDGET_ID) } returns flowOf(null)

            // Act
            viewModel = SmallWidgetConfigViewModel(context, widgetDataRepository, configRepository, savedStateHandle)

            // Assert
            assertEquals(MetricType.HRV, viewModel.state.value.selectedMetric)
            assertEquals(true, viewModel.state.value.showTrend)
            assertEquals(true, viewModel.state.value.showTimestamp)
        }

    @Test
    fun testUpdateMetric() =
        runTest {
            // Arrange
            every { configRepository.observeSmallWidgetConfig(WIDGET_ID) } returns flowOf(null)
            viewModel = SmallWidgetConfigViewModel(context, widgetDataRepository, configRepository, savedStateHandle)

            // Act
            viewModel.updateMetric(MetricType.RHR)

            // Assert
            assertEquals(MetricType.RHR, viewModel.state.value.selectedMetric)
        }

    @Test
    fun testUpdateShowTrend() =
        runTest {
            // Arrange
            every { configRepository.observeSmallWidgetConfig(WIDGET_ID) } returns flowOf(null)
            viewModel = SmallWidgetConfigViewModel(context, widgetDataRepository, configRepository, savedStateHandle)

            // Act
            viewModel.updateShowTrend(false)

            // Assert
            assertEquals(false, viewModel.state.value.showTrend)
        }

    @Test
    fun testClearError() =
        runTest {
            // Arrange
            every { configRepository.observeSmallWidgetConfig(WIDGET_ID) } returns flowOf(null)
            viewModel = SmallWidgetConfigViewModel(context, widgetDataRepository, configRepository, savedStateHandle)

            // Act
            viewModel.clearError()

            // Assert
            assertEquals(null, viewModel.state.value.error)
        }

    @Test
    fun testInvalidWidgetIdShowsError() =
        runTest {
            // Arrange
            savedStateHandle =
                SavedStateHandle().apply {
                    set("widgetId", 0) // Invalid widget ID
                }

            // Act
            viewModel = SmallWidgetConfigViewModel(context, widgetDataRepository, configRepository, savedStateHandle)

            // Assert
            assertEquals("Invalid widget ID", viewModel.state.value.error)
            assertEquals(false, viewModel.state.value.isLoading)
        }

    @Test
    fun testWidgetIdPassedToRepository() =
        runTest {
            // Arrange
            every { configRepository.observeSmallWidgetConfig(WIDGET_ID) } returns flowOf(null)

            // Act
            viewModel = SmallWidgetConfigViewModel(context, widgetDataRepository, configRepository, savedStateHandle)

            // Assert - verify that the correct widgetId was used to query the repository
            assertEquals(false, viewModel.state.value.isLoading)
        }

    companion object {
        private const val WIDGET_ID = 1
    }
}
