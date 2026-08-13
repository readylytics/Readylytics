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

data class WorkoutHistoryManagementState(
    val isManagingHistory: Boolean = false,
    val pendingConfigs: List<WorkoutHistoryConfiguration>? = null,
)

sealed interface WorkoutHistoryManagementEvent {
    data class EnterEditMode(val currentConfigs: List<WorkoutHistoryConfiguration>) : WorkoutHistoryManagementEvent
    data object SaveChanges : WorkoutHistoryManagementEvent
    data object CancelChanges : WorkoutHistoryManagementEvent
    data object ResetToDefaults : WorkoutHistoryManagementEvent
    data class ToggleVisibility(
        val currentConfigs: List<WorkoutHistoryConfiguration>,
        val historyId: WorkoutHistoryId,
        val visible: Boolean,
    ) : WorkoutHistoryManagementEvent
    data class ReorderHistory(
        val currentConfigs: List<WorkoutHistoryConfiguration>,
        val newOrder: List<WorkoutHistoryConfiguration>,
    ) : WorkoutHistoryManagementEvent
}

/**
 * Reactive delegate for the Workouts tab's history-section (workout list, status legend)
 * management. Same shape as [WorkoutChartManagementDelegate] — visibility and position only.
 */
class WorkoutHistoryManagementDelegate(
    private val defaultConfigurations: List<WorkoutHistoryConfiguration>,
    private val persist: suspend (List<WorkoutHistoryConfiguration>) -> Unit,
    private val scope: CoroutineScope,
) {
    private val _isManagingHistory = MutableStateFlow(false)
    private val _pendingConfigs = MutableStateFlow<List<WorkoutHistoryConfiguration>?>(null)
    private val persistTrigger = MutableStateFlow<List<WorkoutHistoryConfiguration>?>(null)

    init {
        scope.launch { persistTrigger.filterNotNull().collect { configs -> persist(configs) } }
    }

    val state: StateFlow<WorkoutHistoryManagementState> =
        combine(_isManagingHistory, _pendingConfigs) { managing, pending ->
            WorkoutHistoryManagementState(managing, pending)
        }.stateIn(scope, SharingStarted.Lazily, WorkoutHistoryManagementState())

    val isManagingHistory: StateFlow<Boolean> = _isManagingHistory.asStateFlow()
    val pendingConfigs: StateFlow<List<WorkoutHistoryConfiguration>?> = _pendingConfigs.asStateFlow()

    fun onEvent(event: WorkoutHistoryManagementEvent) {
        when (event) {
            is WorkoutHistoryManagementEvent.EnterEditMode -> {
                _pendingConfigs.value = event.currentConfigs
                _isManagingHistory.value = true
            }
            WorkoutHistoryManagementEvent.SaveChanges -> {
                _pendingConfigs.value?.let { persistTrigger.value = it }
                _isManagingHistory.value = false
                _pendingConfigs.value = null
            }
            WorkoutHistoryManagementEvent.CancelChanges -> {
                _isManagingHistory.value = false
                _pendingConfigs.value = null
            }
            WorkoutHistoryManagementEvent.ResetToDefaults -> {
                _pendingConfigs.value = defaultConfigurations
            }
            is WorkoutHistoryManagementEvent.ToggleVisibility -> {
                val base = _pendingConfigs.value ?: event.currentConfigs
                _pendingConfigs.value =
                    base.map {
                        if (it.historyId == event.historyId) it.copy(isVisible = event.visible) else it
                    }
            }
            is WorkoutHistoryManagementEvent.ReorderHistory -> {
                val base = _pendingConfigs.value ?: event.currentConfigs
                val reorderedIds = event.newOrder.map { it.historyId }.toSet()
                val hidden = base.filter { it.historyId !in reorderedIds }
                _pendingConfigs.value =
                    (event.newOrder + hidden).mapIndexed { index, config -> config.copy(position = index) }
            }
        }
    }

    fun enterEditMode(currentConfigs: List<WorkoutHistoryConfiguration>) =
        onEvent(WorkoutHistoryManagementEvent.EnterEditMode(currentConfigs))
    fun saveChanges() = onEvent(WorkoutHistoryManagementEvent.SaveChanges)
    fun cancelChanges() = onEvent(WorkoutHistoryManagementEvent.CancelChanges)
    fun onResetToDefaults() = onEvent(WorkoutHistoryManagementEvent.ResetToDefaults)
    fun onToggleHistoryVisibility(
        currentConfigs: List<WorkoutHistoryConfiguration>,
        historyId: WorkoutHistoryId,
        visible: Boolean,
    ) = onEvent(WorkoutHistoryManagementEvent.ToggleVisibility(currentConfigs, historyId, visible))
    fun onReorderHistory(
        currentConfigs: List<WorkoutHistoryConfiguration>,
        newOrder: List<WorkoutHistoryConfiguration>,
    ) = onEvent(WorkoutHistoryManagementEvent.ReorderHistory(currentConfigs, newOrder))
}
