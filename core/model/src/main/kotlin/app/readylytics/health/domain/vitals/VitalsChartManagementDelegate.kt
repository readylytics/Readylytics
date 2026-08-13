package app.readylytics.health.domain.vitals

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class VitalsChartManagementState(
    val isManagingCharts: Boolean = false,
    val pendingConfigs: List<VitalsChartConfiguration>? = null,
)

sealed interface VitalsChartManagementEvent {
    data class EnterEditMode(val currentConfigs: List<VitalsChartConfiguration>) : VitalsChartManagementEvent
    data object SaveChanges : VitalsChartManagementEvent
    data object CancelChanges : VitalsChartManagementEvent
    data object ResetToDefaults : VitalsChartManagementEvent
    data class ToggleVisibility(
        val currentConfigs: List<VitalsChartConfiguration>,
        val chartId: VitalsChartId,
        val visible: Boolean,
    ) : VitalsChartManagementEvent
    data class ReorderCharts(
        val currentConfigs: List<VitalsChartConfiguration>,
        val newOrder: List<VitalsChartConfiguration>,
    ) : VitalsChartManagementEvent
}

class VitalsChartManagementDelegate(
    private val defaultConfigurations: List<VitalsChartConfiguration>,
    private val persist: suspend (List<VitalsChartConfiguration>) -> Unit,
    private val scope: CoroutineScope,
) {
    private val _isManagingCharts = MutableStateFlow(false)
    private val _pendingConfigs = MutableStateFlow<List<VitalsChartConfiguration>?>(null)
    private val persistTrigger = MutableStateFlow<List<VitalsChartConfiguration>?>(null)

    init {
        scope.launch { persistTrigger.filterNotNull().collect { configs -> persist(configs) } }
    }

    val state: StateFlow<VitalsChartManagementState> =
        combine(_isManagingCharts, _pendingConfigs) { managing, pending ->
            VitalsChartManagementState(managing, pending)
        }.stateIn(scope, SharingStarted.Lazily, VitalsChartManagementState())

    val isManagingCharts: StateFlow<Boolean> = _isManagingCharts.asStateFlow()
    val pendingConfigs: StateFlow<List<VitalsChartConfiguration>?> = _pendingConfigs.asStateFlow()

    fun onEvent(event: VitalsChartManagementEvent) {
        when (event) {
            is VitalsChartManagementEvent.EnterEditMode -> {
                _pendingConfigs.value = event.currentConfigs
                _isManagingCharts.value = true
            }
            VitalsChartManagementEvent.SaveChanges -> {
                _pendingConfigs.value?.let { persistTrigger.value = it }
                _isManagingCharts.value = false
                _pendingConfigs.value = null
            }
            VitalsChartManagementEvent.CancelChanges -> {
                _isManagingCharts.value = false
                _pendingConfigs.value = null
            }
            VitalsChartManagementEvent.ResetToDefaults -> {
                _pendingConfigs.value = defaultConfigurations
            }
            is VitalsChartManagementEvent.ToggleVisibility -> {
                val base = _pendingConfigs.value ?: event.currentConfigs
                _pendingConfigs.value = base.map {
                    if (it.chartId == event.chartId) it.copy(isVisible = event.visible) else it
                }
            }
            is VitalsChartManagementEvent.ReorderCharts -> {
                val base = _pendingConfigs.value ?: event.currentConfigs
                val reorderedIds = event.newOrder.map { it.chartId }.toSet()
                val hidden = base.filter { it.chartId !in reorderedIds }
                _pendingConfigs.value = (event.newOrder + hidden).mapIndexed { index, config ->
                    config.copy(position = index)
                }
            }
        }
    }

    fun enterEditMode(currentConfigs: List<VitalsChartConfiguration>) = onEvent(VitalsChartManagementEvent.EnterEditMode(currentConfigs))
    fun saveChanges() = onEvent(VitalsChartManagementEvent.SaveChanges)
    fun cancelChanges() = onEvent(VitalsChartManagementEvent.CancelChanges)
    fun onResetToDefaults() = onEvent(VitalsChartManagementEvent.ResetToDefaults)
    fun onToggleChartVisibility(currentConfigs: List<VitalsChartConfiguration>, chartId: VitalsChartId, visible: Boolean) =
        onEvent(VitalsChartManagementEvent.ToggleVisibility(currentConfigs, chartId, visible))
    fun onReorderCharts(currentConfigs: List<VitalsChartConfiguration>, newOrder: List<VitalsChartConfiguration>) =
        onEvent(VitalsChartManagementEvent.ReorderCharts(currentConfigs, newOrder))
}