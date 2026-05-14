package com.gregor.lauritz.healthdashboard.widgets.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.repository.LargeWidgetConfig
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LargeWidgetConfigState(
    val selectedCardIds: List<String> = defaultCardIds(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSaved: Boolean = false,
) {
    companion object {
        fun defaultCardIds() =
            listOf(
                "SLEEP_SCORE",
                "READINESS",
                "HRV",
                "STEPS",
            )
    }
}

fun defaultCardIds() =
    listOf(
        "SLEEP_SCORE",
        "READINESS",
        "HRV",
        "STEPS",
    )

@HiltViewModel
class LargeWidgetConfigViewModel
    @Inject
    constructor(
        private val configRepository: WidgetConfigurationRepository,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val widgetId: Int = savedStateHandle.get<Int>("widgetId") ?: 0

        private val _state = MutableStateFlow(LargeWidgetConfigState(isLoading = widgetId > 0))
        val state: StateFlow<LargeWidgetConfigState> = _state.asStateFlow()

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
                        configRepository.observeLargeWidgetConfig(widgetId).let {
                            try {
                                val result = mutableListOf<LargeWidgetConfig?>()
                                it.collect { config ->
                                    result.add(config)
                                }
                                result.firstOrNull()
                            } catch (e: Exception) {
                                null
                            }
                        }

                    if (config != null && config.cardIds.isNotEmpty()) {
                        _state.value =
                            LargeWidgetConfigState(
                                selectedCardIds = config.cardIds.take(4),
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

        fun toggleCard(cardId: String) {
            val current = _state.value.selectedCardIds
            val updated =
                if (current.contains(cardId)) {
                    current - cardId
                } else if (current.size < 4) {
                    current + cardId
                } else {
                    current
                }
            _state.value = _state.value.copy(selectedCardIds = updated)
        }

        fun saveConfiguration() {
            viewModelScope.launch {
                try {
                    _state.value = _state.value.copy(isLoading = true, error = null)
                    configRepository.saveLargeWidgetConfig(
                        widgetId,
                        LargeWidgetConfig(
                            widgetId = widgetId,
                            cardIds = _state.value.selectedCardIds,
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
