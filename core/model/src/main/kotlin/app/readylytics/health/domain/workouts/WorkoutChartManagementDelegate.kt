package app.readylytics.health.domain.workouts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WorkoutChartManagementState(
    val isManagingCharts: Boolean = false,
    val pendingConfigs: List<WorkoutChartConfiguration>? = null,
)

sealed interface WorkoutChartManagementEvent {
    data class EnterEditMode(val currentConfigs: List<WorkoutChartConfiguration>) : WorkoutChartManagementEvent
    data object SaveChanges : WorkoutChartManagementEvent
    data object CancelChanges : WorkoutChartManagementEvent
    data object ResetToDefaults : WorkoutChartManagementEvent
    data class ToggleVisibility(
        val currentConfigs: List<WorkoutChartConfiguration>,
        val chartId: WorkoutChartId,
        val visible: Boolean,
    ) : WorkoutChartManagementEvent
    data class ReorderCharts(
        val currentConfigs: List<WorkoutChartConfiguration>,
        val newOrder: List<WorkoutChartConfiguration>,
    ) : WorkoutChartManagementEvent
}

/**
 * Reactive delegate for the Workouts tab's ACWR/TRIMP diagram management.
 * Mirrors [app.readylytics.health.domain.vitals.VitalsChartManagementDelegate] — no display-mode
 * concept, only visibility and position.
 */
class WorkoutChartManagementDelegate(
    private val defaultConfigurations: List<WorkoutChartConfiguration>,
    private val persist: suspend (List<WorkoutChartConfiguration>) -> Unit,
    private val scope: CoroutineScope,
) {
    private val _isManagingCharts = MutableStateFlow(false)
    private val _pendingConfigs = MutableStateFlow<List<WorkoutChartConfiguration>?>(null)
    private val persistTrigger = MutableStateFlow<List<WorkoutChartConfiguration>?>(null)

    init {
        scope.launch { persistTrigger.filterNotNull().collect { configs -> persist(configs) } }
    }

    val state: StateFlow<WorkoutChartManagementState> =
        combine(_isManagingCharts, _pendingConfigs) { managing, pending ->
            WorkoutChartManagementState(managing, pending)
        }.stateIn(scope, SharingStarted.Lazily, WorkoutChartManagementState())

    val isManagingCharts: StateFlow<Boolean> = _isManagingCharts.asStateFlow()
    val pendingConfigs: StateFlow<List<WorkoutChartConfiguration>?> = _pendingConfigs.asStateFlow()

    fun onEvent(event: WorkoutChartManagementEvent) {
        when (event) {
            is WorkoutChartManagementEvent.EnterEditMode -> {
                _pendingConfigs.value = event.currentConfigs
                _isManagingCharts.value = true
            }
            WorkoutChartManagementEvent.SaveChanges -> {
                _pendingConfigs.value?.let { persistTrigger.value = it }
                _isManagingCharts.value = false
                _pendingConfigs.value = null
            }
            WorkoutChartManagementEvent.CancelChanges -> {
                _isManagingCharts.value = false
                _pendingConfigs.value = null
            }
            WorkoutChartManagementEvent.ResetToDefaults -> {
                _pendingConfigs.value = defaultConfigurations
            }
            is WorkoutChartManagementEvent.ToggleVisibility -> {
                val base = _pendingConfigs.value ?: event.currentConfigs
                _pendingConfigs.value =
                    base.map {
                        if (it.chartId == event.chartId) it.copy(isVisible = event.visible) else it
                    }
            }
            is WorkoutChartManagementEvent.ReorderCharts -> {
                val base = _pendingConfigs.value ?: event.currentConfigs
                val reorderedIds = event.newOrder.map { it.chartId }.toSet()
                val hidden = base.filter { it.chartId !in reorderedIds }
                _pendingConfigs.value =
                    (event.newOrder + hidden).mapIndexed { index, config -> config.copy(position = index) }
            }
        }
    }

    fun enterEditMode(currentConfigs: List<WorkoutChartConfiguration>) =
        onEvent(WorkoutChartManagementEvent.EnterEditMode(currentConfigs))
    fun saveChanges() = onEvent(WorkoutChartManagementEvent.SaveChanges)
    fun cancelChanges() = onEvent(WorkoutChartManagementEvent.CancelChanges)
    fun onResetToDefaults() = onEvent(WorkoutChartManagementEvent.ResetToDefaults)
    fun onToggleChartVisibility(
        currentConfigs: List<WorkoutChartConfiguration>,
        chartId: WorkoutChartId,
        visible: Boolean,
    ) = onEvent(WorkoutChartManagementEvent.ToggleVisibility(currentConfigs, chartId, visible))
    fun onReorderCharts(
        currentConfigs: List<WorkoutChartConfiguration>,
        newOrder: List<WorkoutChartConfiguration>,
    ) = onEvent(WorkoutChartManagementEvent.ReorderCharts(currentConfigs, newOrder))
}
