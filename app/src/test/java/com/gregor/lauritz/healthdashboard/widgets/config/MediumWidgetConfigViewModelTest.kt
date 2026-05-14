package com.gregor.lauritz.healthdashboard.widgets.config

import androidx.lifecycle.SavedStateHandle
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.repository.WidgetMode
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MediumWidgetConfigViewModelTest {
    private val configRepository: WidgetConfigurationRepository = mockk()
    private val widgetDataRepository: com.gregor.lauritz.healthdashboard.data.repository.WidgetDataRepository = mockk()
    private val context: android.content.Context = mockk()

    private lateinit var viewModel: MediumWidgetConfigViewModel
    private lateinit var savedStateHandle: SavedStateHandle

    @Before
    fun setUp() {
        savedStateHandle =
            SavedStateHandle().apply {
                set("widgetId", WIDGET_ID)
            }
    }

    @Test
    fun testInitialState() {
        // Arrange
        every { configRepository.observeMediumWidgetConfig(WIDGET_ID) } returns flowOf(null)

        // Act
        viewModel = MediumWidgetConfigViewModel(context, widgetDataRepository, configRepository, savedStateHandle)

        // Assert
        assertEquals(WidgetMode.DUAL_METRIC, viewModel.state.value.mode)
        assertEquals(MetricType.HRV, viewModel.state.value.metric1)
        assertEquals(MetricType.RHR, viewModel.state.value.metric2)
    }

    @Test
    fun testWidgetIdFromSavedStateHandle() {
        // Arrange
        every { configRepository.observeMediumWidgetConfig(WIDGET_ID) } returns flowOf(null)

        // Act
        viewModel = MediumWidgetConfigViewModel(context, widgetDataRepository, configRepository, savedStateHandle)

        // Assert - widget loads configuration when widgetId is valid
        assertEquals(false, viewModel.state.value.isLoading)
    }

    @Test
    fun testUpdateMode() {
        // Arrange
        every { configRepository.observeMediumWidgetConfig(WIDGET_ID) } returns flowOf(null)
        viewModel = MediumWidgetConfigViewModel(context, widgetDataRepository, configRepository, savedStateHandle)

        // Act
        viewModel.updateMode(WidgetMode.STEPS_PROGRESS)

        // Assert
        assertEquals(WidgetMode.STEPS_PROGRESS, viewModel.state.value.mode)
    }

    @Test
    fun testUpdateMetric1() {
        // Arrange
        every { configRepository.observeMediumWidgetConfig(WIDGET_ID) } returns flowOf(null)
        viewModel = MediumWidgetConfigViewModel(context, widgetDataRepository, configRepository, savedStateHandle)

        // Act
        viewModel.updateMetric1(MetricType.SLEEP_SCORE)

        // Assert
        assertEquals(MetricType.SLEEP_SCORE, viewModel.state.value.metric1)
    }

    @Test
    fun testUpdateMetric2() {
        // Arrange
        every { configRepository.observeMediumWidgetConfig(WIDGET_ID) } returns flowOf(null)
        viewModel = MediumWidgetConfigViewModel(context, widgetDataRepository, configRepository, savedStateHandle)

        // Act
        viewModel.updateMetric2(MetricType.READINESS)

        // Assert
        assertEquals(MetricType.READINESS, viewModel.state.value.metric2)
    }

    @Test
    fun testClearError() {
        // Arrange
        every { configRepository.observeMediumWidgetConfig(WIDGET_ID) } returns flowOf(null)
        viewModel = MediumWidgetConfigViewModel(context, widgetDataRepository, configRepository, savedStateHandle)

        // Act
        viewModel.clearError()

        // Assert
        assertEquals(null, viewModel.state.value.error)
    }

    companion object {
        private const val WIDGET_ID = 2
    }
}
