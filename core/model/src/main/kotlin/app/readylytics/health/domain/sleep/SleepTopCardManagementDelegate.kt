package app.readylytics.health.domain.sleep

import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SleepTopCardManagementState(
    val isManaging: Boolean = false,
    val pendingConfigs: List<SleepTopCardConfiguration>? = null,
)

sealed interface SleepTopCardManagementEvent {
    data class EnterEditMode(val currentConfigs: List<SleepTopCardConfiguration>) : SleepTopCardManagementEvent
    data object SaveChanges : SleepTopCardManagementEvent
    data object CancelChanges : SleepTopCardManagementEvent
    data object ResetToDefaults : SleepTopCardManagementEvent
    data class ToggleVisibility(
        val currentConfigs: List<SleepTopCardConfiguration>,
        val cardId: SleepTopCardId,
        val visible: Boolean,
    ) : SleepTopCardManagementEvent
    data class Reorder(
        val currentConfigs: List<SleepTopCardConfiguration>,
        val newOrder: List<SleepTopCardConfiguration>,
    ) : SleepTopCardManagementEvent
    data class DisplayModeChanged(
        val cardId: SleepTopCardId,
        val mode: DashboardCardDisplayMode?,
    ) : SleepTopCardManagementEvent
}

class SleepTopCardManagementDelegate(
    private val defaultConfigurations: List<SleepTopCardConfiguration>,
    private val persist: suspend (List<SleepTopCardConfiguration>) -> Unit,
    private val scope: CoroutineScope,
) {
    private val _isManaging = MutableStateFlow(false)
    private val _pendingConfigs = MutableStateFlow<List<SleepTopCardConfiguration>?>(null)
    private val persistTrigger = MutableStateFlow<List<SleepTopCardConfiguration>?>(null)

    init {
        scope.launch { persistTrigger.filterNotNull().collect { configs -> persist(configs) } }
    }

    val state: StateFlow<SleepTopCardManagementState> =
        combine(_isManaging, _pendingConfigs) { managing, pending ->
            SleepTopCardManagementState(managing, pending)
        }.stateIn(scope, SharingStarted.Lazily, SleepTopCardManagementState())

    val isManaging: StateFlow<Boolean> = _isManaging.asStateFlow()
    val pendingConfigs: StateFlow<List<SleepTopCardConfiguration>?> = _pendingConfigs.asStateFlow()

    fun onEvent(event: SleepTopCardManagementEvent) {
        when (event) {
            is SleepTopCardManagementEvent.EnterEditMode -> {
                _pendingConfigs.value = event.currentConfigs
                _isManaging.value = true
            }
            SleepTopCardManagementEvent.SaveChanges -> {
                _pendingConfigs.value?.let { persistTrigger.value = it }
                _isManaging.value = false
                _pendingConfigs.value = null
            }
            SleepTopCardManagementEvent.CancelChanges -> {
                _isManaging.value = false
                _pendingConfigs.value = null
            }
            SleepTopCardManagementEvent.ResetToDefaults -> {
                _pendingConfigs.value = defaultConfigurations
            }
            is SleepTopCardManagementEvent.ToggleVisibility -> {
                val base = _pendingConfigs.value ?: event.currentConfigs
                _pendingConfigs.value = base.map {
                    if (it.cardId == event.cardId) it.copy(isVisible = event.visible) else it
                }
            }
            is SleepTopCardManagementEvent.Reorder -> {
                val base = _pendingConfigs.value ?: event.currentConfigs
                val reorderedIds = event.newOrder.map { it.cardId }.toSet()
                val hidden = base.filter { it.cardId !in reorderedIds }
                _pendingConfigs.value = (event.newOrder + hidden).mapIndexed { index, config ->
                    config.copy(position = index)
                }
            }
            is SleepTopCardManagementEvent.DisplayModeChanged -> {
                val base = _pendingConfigs.value ?: emptyList()
                _pendingConfigs.value = base.map {
                    if (it.cardId == event.cardId) it.copy(requestedDisplayMode = event.mode) else it
                }
            }
        }
    }

    fun enterEditMode(currentConfigs: List<SleepTopCardConfiguration>) =
        onEvent(SleepTopCardManagementEvent.EnterEditMode(currentConfigs))
    fun saveChanges() = onEvent(SleepTopCardManagementEvent.SaveChanges)
    fun cancelChanges() = onEvent(SleepTopCardManagementEvent.CancelChanges)
    fun onResetToDefaults() = onEvent(SleepTopCardManagementEvent.ResetToDefaults)
    fun onToggleVisibility(currentConfigs: List<SleepTopCardConfiguration>, cardId: SleepTopCardId, visible: Boolean) =
        onEvent(SleepTopCardManagementEvent.ToggleVisibility(currentConfigs, cardId, visible))
    fun onReorder(currentConfigs: List<SleepTopCardConfiguration>, newOrder: List<SleepTopCardConfiguration>) =
        onEvent(SleepTopCardManagementEvent.Reorder(currentConfigs, newOrder))
    fun onDisplayModeChanged(cardId: SleepTopCardId, mode: DashboardCardDisplayMode?) =
        onEvent(SleepTopCardManagementEvent.DisplayModeChanged(cardId, mode))
}
