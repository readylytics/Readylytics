package app.readylytics.health.domain.layout

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Aggregated edit-mode UI state shared by every visibility+position-only reorderable list
 * (Vitals/Workouts/Sleep charts, Workouts history). Cards with a display-mode concept
 * (e.g. dashboard cards) are not covered by this delegate.
 */
data class LayoutManagementState<T : ReorderableItem<Id>, Id>(
    val isManaging: Boolean = false,
    val pendingConfigs: List<T>? = null,
)

/**
 * Generic reactive delegate for visibility+position-only layout management. Replaces the
 * near-identical per-tab chart/history management delegate classes. [withVisibility] and
 * [withPosition] are injected because [ReorderableItem] exposes read-only properties (no
 * generic `copy()` across an interface); callers pass `{ config, value -> config.copy(...) }`
 * since [T] is always a concrete data class at the call site.
 *
 * Persistence is only-on-save, exactly as in the delegates this replaces: edits accumulate in
 * [pendingConfigs] while in edit mode; [saveChanges] pushes them onto an internal trigger that
 * a reactive collector persists, while [cancelChanges] discards the pending set without ever
 * touching persistence.
 */
class LayoutManagementDelegate<T : ReorderableItem<Id>, Id>(
    private val defaultConfigurations: List<T>,
    private val persist: suspend (List<T>) -> Unit,
    private val scope: CoroutineScope,
    private val withVisibility: (T, Boolean) -> T,
    private val withPosition: (T, Int) -> T,
) {
    private val _isManaging = MutableStateFlow(false)
    private val _pendingConfigs = MutableStateFlow<List<T>?>(null)
    private val persistTrigger = MutableStateFlow<List<T>?>(null)

    init {
        scope.launch { persistTrigger.filterNotNull().collect { configs -> persist(configs) } }
    }

    val state: StateFlow<LayoutManagementState<T, Id>> =
        combine(_isManaging, _pendingConfigs) { managing, pending ->
            LayoutManagementState(managing, pending)
        }.stateIn(scope, SharingStarted.Lazily, LayoutManagementState())

    val isManaging: StateFlow<Boolean> = _isManaging.asStateFlow()
    val pendingConfigs: StateFlow<List<T>?> = _pendingConfigs.asStateFlow()

    fun enterEditMode(currentConfigs: List<T>) {
        _pendingConfigs.value = currentConfigs
        _isManaging.value = true
    }

    fun saveChanges() {
        _pendingConfigs.value?.let { persistTrigger.value = it }
        _isManaging.value = false
        _pendingConfigs.value = null
    }

    fun cancelChanges() {
        _isManaging.value = false
        _pendingConfigs.value = null
    }

    fun onResetToDefaults() {
        _pendingConfigs.value = defaultConfigurations
    }

    fun onToggleVisibility(
        currentConfigs: List<T>,
        id: Id,
        visible: Boolean,
    ) {
        val base = _pendingConfigs.value ?: currentConfigs
        _pendingConfigs.value = base.map { if (it.id == id) withVisibility(it, visible) else it }
    }

    fun onReorder(
        currentConfigs: List<T>,
        newOrder: List<T>,
    ) {
        val base = _pendingConfigs.value ?: currentConfigs
        val reorderedIds = newOrder.map { it.id }.toSet()
        val hidden = base.filter { it.id !in reorderedIds }
        _pendingConfigs.value = (newOrder + hidden).mapIndexed { index, config -> withPosition(config, index) }
    }
}
