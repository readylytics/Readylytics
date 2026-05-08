package com.gregor.lauritz.healthdashboard.widgets.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.repository.SmallWidgetConfig
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SmallWidgetConfigState(
    val selectedMetric: MetricType = MetricType.HRV,
    val showTrend: Boolean = true,
    val showTimestamp: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSaved: Boolean = false,
)

@HiltViewModel
class SmallWidgetConfigViewModel @Inject constructor(
    private val configRepository: WidgetConfigurationRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val widgetId: Int = savedStateHandle["widgetId"] ?: return

    private val _state = MutableStateFlow(SmallWidgetConfigState(isLoading = true))
    val state: StateFlow<SmallWidgetConfigState> = _state.asStateFlow()

    init {
        loadConfiguration()
    }

    private fun loadConfiguration() {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(isLoading = true, error = null)
                val config = configRepository.observeSmallWidgetConfig(widgetId).let {
                    // Try to get the first value from the flow
                    try {
                        val result = mutableListOf<SmallWidgetConfig?>()
                        it.collect {config ->
                            result.add(config)
                        }
                        result.firstOrNull()
                    } catch (e: Exception) {
                        null
                    }
                }

                if (config != null) {
                    _state.value = SmallWidgetConfigState(
                        selectedMetric = try {
                            MetricType.valueOf(config.metricType)
                        } catch (e: IllegalArgumentException) {
                            MetricType.HRV
                        },
                        showTrend = config.showTrend,
                        showTimestamp = config.showTimestamp,
                        isLoading = false,
                    )
                } else {
                    _state.value = _state.value.copy(isLoading = false)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Failed to load configuration: ${e.message}",
                )
            }
        }
    }

    fun updateMetric(metric: MetricType) {
        _state.value = _state.value.copy(selectedMetric = metric)
    }

    fun updateShowTrend(show: Boolean) {
        _state.value = _state.value.copy(showTrend = show)
    }

    fun updateShowTimestamp(show: Boolean) {
        _state.value = _state.value.copy(showTimestamp = show)
    }

    fun saveConfiguration() {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(isLoading = true, error = null)
                configRepository.saveSmallWidgetConfig(
                    widgetId,
                    SmallWidgetConfig(
                        widgetId = widgetId,
                        metricType = _state.value.selectedMetric.name,
                        showTrend = _state.value.showTrend,
                        showTimestamp = _state.value.showTimestamp,
                    ),
                )
                _state.value = _state.value.copy(isLoading = false, isSaved = true)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Failed to save configuration: ${e.message}",
                )
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
