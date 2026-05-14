package com.gregor.lauritz.healthdashboard.widgets.config

import androidx.lifecycle.SavedStateHandle
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.whenever

@RunWith(MockitoJUnitRunner::class)
class LargeWidgetConfigViewModelTest {
    @Mock
    private lateinit var configRepository: WidgetConfigurationRepository

    private lateinit var viewModel: LargeWidgetConfigViewModel
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
        whenever(configRepository.observeLargeWidgetConfig(WIDGET_ID)).thenReturn(flowOf(null))

        // Act
        viewModel = LargeWidgetConfigViewModel(configRepository, savedStateHandle)

        // Assert - default state has 4 cards selected
        assertEquals(4, viewModel.state.value.selectedCardIds.size)
    }

    @Test
    fun testWidgetIdFromSavedStateHandle() {
        // Arrange
        whenever(configRepository.observeLargeWidgetConfig(WIDGET_ID)).thenReturn(flowOf(null))

        // Act
        viewModel = LargeWidgetConfigViewModel(configRepository, savedStateHandle)

        // Assert - widget loads configuration when widgetId is valid
        assertEquals(false, viewModel.state.value.isLoading)
    }

    @Test
    fun testToggleCardSelection() {
        // Arrange
        whenever(configRepository.observeLargeWidgetConfig(WIDGET_ID)).thenReturn(flowOf(null))
        viewModel = LargeWidgetConfigViewModel(configRepository, savedStateHandle)
        val initialCount = viewModel.state.value.selectedCardIds.size

        // Act - remove a card
        viewModel.toggleCard("SLEEP_SCORE")

        // Assert
        assertEquals(initialCount - 1, viewModel.state.value.selectedCardIds.size)
    }

    @Test
    fun testCannotExceedFourCards() {
        // Arrange
        whenever(configRepository.observeLargeWidgetConfig(WIDGET_ID)).thenReturn(flowOf(null))
        viewModel = LargeWidgetConfigViewModel(configRepository, savedStateHandle)

        // Act - try to add a 5th card (should be rejected)
        // First ensure we have exactly 4
        var state = viewModel.state.value
        while (state.selectedCardIds.size < 4) {
            viewModel.toggleCard("PAI")
            state = viewModel.state.value
        }

        val cardBefore = state.selectedCardIds.size
        // Try to add when already at max
        viewModel.toggleCard("CALORIES")

        // Assert
        assertEquals(cardBefore, viewModel.state.value.selectedCardIds.size)
    }

    @Test
    fun testClearError() {
        // Arrange
        whenever(configRepository.observeLargeWidgetConfig(WIDGET_ID)).thenReturn(flowOf(null))
        viewModel = LargeWidgetConfigViewModel(configRepository, savedStateHandle)

        // Act
        viewModel.clearError()

        // Assert
        assertEquals(null, viewModel.state.value.error)
    }

    companion object {
        private const val WIDGET_ID = 3
    }
}
