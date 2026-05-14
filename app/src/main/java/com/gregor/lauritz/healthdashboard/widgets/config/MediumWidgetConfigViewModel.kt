package com.gregor.lauritz.healthdashboard.widgets.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.repository.MediumWidgetConfig
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.repository.WidgetMode
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MediumWidgetConfigState(
    val mode: WidgetMode = WidgetMode.DUAL_METRIC,
    val metric1: MetricType = MetricType.HRV,
    val metric2: MetricType = MetricType.RHR,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSaved: Boolean = false,
)

@HiltViewModel
class MediumWidgetConfigViewModel
    @Inject
    constructor(
        private val configRepository: WidgetConfigurationRepository,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val widgetId: Int =
            savedStateHandle.get<Int>("widgetId") ?: 0

        private val _state =
            MutableStateFlow(MediumWidgetConfigState(isLoading = widgetId > 0))
        val state: StateFlow<MediumWidgetConfigState> = _state.asStateFlow()

        init {
            if (widgetId > 0) {
                loadConfiguration()
            }
        }

        private fun loadConfiguration() {
            viewModelScope.launch {
                try {
                    _state.value = _state.value.copy(isLoading = true, error = null)
                    val config =
                        configRepository.observeMediumWidgetConfig(widgetId).let {
                            try {
                                val result = mutableListOf<MediumWidgetConfig?>()
                                it.collect { config ->
                                    result.add(config)
                                }
                                result.firstOrNull()
                            } catch (e: Exception) {
                                null
                            }
                        }

                    if (config != null) {
                        _state.value =
                            MediumWidgetConfigState(
                                mode =
                                    try {
                                        WidgetMode.valueOf(config.mode)
                                    } catch (e: IllegalArgumentException) {
                                        WidgetMode.DUAL_METRIC
                                    },
                                metric1 =
                                    try {
                                        MetricType.valueOf(config.metric1 ?: "HRV")
                                    } catch (e: IllegalArgumentException) {
                                        MetricType.HRV
                                    },
                                metric2 =
                                    try {
                                        MetricType.valueOf(config.metric2 ?: "RHR")
                                    } catch (e: IllegalArgumentException) {
                                        MetricType.RHR
                                    },
                                isLoading = false,
                            )
                    } else {
                        _state.value = _state.value.copy(isLoading = false)
                    }
                } catch (e: Exception) {
                    _state.value =
                        _state.value.copy(
                            isLoading = false,
                            error = "Failed to load configuration: ${e.message}",
                        )
                }
            }
        }

        fun updateMode(mode: WidgetMode) {
            _state.value = _state.value.copy(mode = mode)
        }

        fun updateMetric1(metric: MetricType) {
            _state.value = _state.value.copy(metric1 = metric)
        }

        fun updateMetric2(metric: MetricType) {
            _state.value = _state.value.copy(metric2 = metric)
        }

        fun saveConfiguration() {
            viewModelScope.launch {
                try {
                    _state.value = _state.value.copy(isLoading = true, error = null)
                    configRepository.saveMediumWidgetConfig(
                        widgetId,
                        MediumWidgetConfig(
                            widgetId = widgetId,
                            mode = _state.value.mode.name,
                            metric1 =
                                if (_state.value.mode ==
                                    WidgetMode.DUAL_METRIC
                                ) {
                                    _state.value.metric1.name
                                } else {
                                    null
                                },
                            metric2 =
                                if (_state.value.mode ==
                                    WidgetMode.DUAL_METRIC
                                ) {
                                    _state.value.metric2.name
                                } else {
                                    null
                                },
                        ),
                    )
                    _state.value = _state.value.copy(isLoading = false, isSaved = true)
                } catch (e: Exception) {
                    _state.value =
                        _state.value.copy(
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
