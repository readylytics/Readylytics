package app.readylytics.health.core.model.domain.sleep

import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SleepMetricCardManagementState(
    val isManaging: Boolean = false,
    val pendingConfigs: List<SleepMetricCardConfiguration>? = null,
)

sealed interface SleepMetricCardManagementEvent {
    data class EnterEditMode(val currentConfigs: List<SleepMetricCardConfiguration>) : SleepMetricCardManagementEvent
    data object SaveChanges : SleepMetricCardManagementEvent
    data object CancelChanges : SleepMetricCardManagementEvent
    data object ResetToDefaults : SleepMetricCardManagementEvent
    data class ToggleVisibility(
        val currentConfigs: List<SleepMetricCardConfiguration>,
        val cardId: SleepMetricCardId,
        val visible: Boolean,
    ) : SleepMetricCardManagementEvent
    data class Reorder(
        val currentConfigs: List<SleepMetricCardConfiguration>,
        val newOrder: List<SleepMetricCardConfiguration>,
    ) : SleepMetricCardManagementEvent
    data class DisplayModeChanged(
        val cardId: SleepMetricCardId,
        val mode: DashboardCardDisplayMode?,
    ) : SleepMetricCardManagementEvent
}

class SleepMetricCardManagementDelegate(
    private val defaultConfigurations: List<SleepMetricCardConfiguration>,
    private val persist: suspend (List<SleepMetricCardConfiguration>) -> Unit,
    private val scope: CoroutineScope,
) {
    private val _isManaging = MutableStateFlow(false)
    private val _pendingConfigs = MutableStateFlow<List<SleepMetricCardConfiguration>?>(null)
    private val persistTrigger = MutableStateFlow<List<SleepMetricCardConfiguration>?>(null)

    init {
        scope.launch { persistTrigger.filterNotNull().collect { configs -> persist(configs) } }
    }

    val state: StateFlow<SleepMetricCardManagementState> =
        combine(_isManaging, _pendingConfigs) { managing, pending ->
            SleepMetricCardManagementState(managing, pending)
        }.stateIn(scope, SharingStarted.Lazily, SleepMetricCardManagementState())

    val isManaging: StateFlow<Boolean> = _isManaging.asStateFlow()
    val pendingConfigs: StateFlow<List<SleepMetricCardConfiguration>?> = _pendingConfigs.asStateFlow()

    fun onEvent(event: SleepMetricCardManagementEvent) {
        when (event) {
            is SleepMetricCardManagementEvent.EnterEditMode -> {
                _pendingConfigs.value = event.currentConfigs
                _isManaging.value = true
            }
            SleepMetricCardManagementEvent.SaveChanges -> {
                _pendingConfigs.value?.let { persistTrigger.value = it }
                _isManaging.value = false
                _pendingConfigs.value = null
            }
            SleepMetricCardManagementEvent.CancelChanges -> {
                _isManaging.value = false
                _pendingConfigs.value = null
            }
            SleepMetricCardManagementEvent.ResetToDefaults -> {
                _pendingConfigs.value = defaultConfigurations
            }
            is SleepMetricCardManagementEvent.ToggleVisibility -> {
                val base = _pendingConfigs.value ?: event.currentConfigs
                _pendingConfigs.value = base.map {
                    if (it.cardId == event.cardId) it.copy(isVisible = event.visible) else it
                }
            }
            is SleepMetricCardManagementEvent.Reorder -> {
                val base = _pendingConfigs.value ?: event.currentConfigs
                val reorderedIds = event.newOrder.map { it.cardId }.toSet()
                val hidden = base.filter { it.cardId !in reorderedIds }
                _pendingConfigs.value = (event.newOrder + hidden).mapIndexed { index, config ->
                    config.copy(position = index)
                }
            }
            is SleepMetricCardManagementEvent.DisplayModeChanged -> {
                val base = _pendingConfigs.value ?: emptyList()
                _pendingConfigs.value = base.map {
                    if (it.cardId == event.cardId) it.copy(requestedDisplayMode = event.mode) else it
                }
            }
        }
    }

    fun enterEditMode(currentConfigs: List<SleepMetricCardConfiguration>) =
        onEvent(SleepMetricCardManagementEvent.EnterEditMode(currentConfigs))
    fun saveChanges() = onEvent(SleepMetricCardManagementEvent.SaveChanges)
    fun cancelChanges() = onEvent(SleepMetricCardManagementEvent.CancelChanges)
    fun onResetToDefaults() = onEvent(SleepMetricCardManagementEvent.ResetToDefaults)
    fun onToggleVisibility(currentConfigs: List<SleepMetricCardConfiguration>, cardId: SleepMetricCardId, visible: Boolean) =
        onEvent(SleepMetricCardManagementEvent.ToggleVisibility(currentConfigs, cardId, visible))
    fun onReorder(currentConfigs: List<SleepMetricCardConfiguration>, newOrder: List<SleepMetricCardConfiguration>) =
        onEvent(SleepMetricCardManagementEvent.Reorder(currentConfigs, newOrder))
    fun onDisplayModeChanged(cardId: SleepMetricCardId, mode: DashboardCardDisplayMode?) =
        onEvent(SleepMetricCardManagementEvent.DisplayModeChanged(cardId, mode))
}
