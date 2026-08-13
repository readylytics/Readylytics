package app.readylytics.health.domain.sleep

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SleepChartManagementState(
    val isManaging: Boolean = false,
    val pendingConfigs: List<SleepChartConfiguration>? = null,
)

sealed interface SleepChartManagementEvent {
    data class EnterEditMode(val currentConfigs: List<SleepChartConfiguration>) : SleepChartManagementEvent
    data object SaveChanges : SleepChartManagementEvent
    data object CancelChanges : SleepChartManagementEvent
    data object ResetToDefaults : SleepChartManagementEvent
    data class ToggleVisibility(
        val currentConfigs: List<SleepChartConfiguration>,
        val chartId: SleepChartId,
        val visible: Boolean,
    ) : SleepChartManagementEvent
    data class Reorder(
        val currentConfigs: List<SleepChartConfiguration>,
        val newOrder: List<SleepChartConfiguration>,
    ) : SleepChartManagementEvent
}

class SleepChartManagementDelegate(
    private val defaultConfigurations: List<SleepChartConfiguration>,
    private val persist: suspend (List<SleepChartConfiguration>) -> Unit,
    private val scope: CoroutineScope,
) {
    private val _isManaging = MutableStateFlow(false)
    private val _pendingConfigs = MutableStateFlow<List<SleepChartConfiguration>?>(null)
    private val persistTrigger = MutableStateFlow<List<SleepChartConfiguration>?>(null)

    init {
        scope.launch { persistTrigger.filterNotNull().collect { configs -> persist(configs) } }
    }

    val state: StateFlow<SleepChartManagementState> =
        combine(_isManaging, _pendingConfigs) { managing, pending ->
            SleepChartManagementState(managing, pending)
        }.stateIn(scope, SharingStarted.Lazily, SleepChartManagementState())

    val isManaging: StateFlow<Boolean> = _isManaging.asStateFlow()
    val pendingConfigs: StateFlow<List<SleepChartConfiguration>?> = _pendingConfigs.asStateFlow()

    fun onEvent(event: SleepChartManagementEvent) {
        when (event) {
            is SleepChartManagementEvent.EnterEditMode -> {
                _pendingConfigs.value = event.currentConfigs
                _isManaging.value = true
            }
            SleepChartManagementEvent.SaveChanges -> {
                _pendingConfigs.value?.let { persistTrigger.value = it }
                _isManaging.value = false
                _pendingConfigs.value = null
            }
            SleepChartManagementEvent.CancelChanges -> {
                _isManaging.value = false
                _pendingConfigs.value = null
            }
            SleepChartManagementEvent.ResetToDefaults -> {
                _pendingConfigs.value = defaultConfigurations
            }
            is SleepChartManagementEvent.ToggleVisibility -> {
                val base = _pendingConfigs.value ?: event.currentConfigs
                _pendingConfigs.value = base.map {
                    if (it.chartId == event.chartId) it.copy(isVisible = event.visible) else it
                }
            }
            is SleepChartManagementEvent.Reorder -> {
                val base = _pendingConfigs.value ?: event.currentConfigs
                val reorderedIds = event.newOrder.map { it.chartId }.toSet()
                val hidden = base.filter { it.chartId !in reorderedIds }
                _pendingConfigs.value = (event.newOrder + hidden).mapIndexed { index, config ->
                    config.copy(position = index)
                }
            }
        }
    }

    fun enterEditMode(currentConfigs: List<SleepChartConfiguration>) =
        onEvent(SleepChartManagementEvent.EnterEditMode(currentConfigs))
    fun saveChanges() = onEvent(SleepChartManagementEvent.SaveChanges)
    fun cancelChanges() = onEvent(SleepChartManagementEvent.CancelChanges)
    fun onResetToDefaults() = onEvent(SleepChartManagementEvent.ResetToDefaults)
    fun onToggleVisibility(currentConfigs: List<SleepChartConfiguration>, chartId: SleepChartId, visible: Boolean) =
        onEvent(SleepChartManagementEvent.ToggleVisibility(currentConfigs, chartId, visible))
    fun onReorder(currentConfigs: List<SleepChartConfiguration>, newOrder: List<SleepChartConfiguration>) =
        onEvent(SleepChartManagementEvent.Reorder(currentConfigs, newOrder))
}
