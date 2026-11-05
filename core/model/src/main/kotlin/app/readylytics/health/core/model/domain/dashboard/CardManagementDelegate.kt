package app.readylytics.health.core.model.domain.dashboard

import app.readylytics.health.core.model.domain.layout.LayoutManagementDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Aggregated card-management UI state derived purely from upstream flows.
 */
data class CardManagementState(
    val isManagingCards: Boolean = false,
    val pendingConfigs: List<CardConfiguration>? = null,
)

/**
 * Events that drive the delegate. All side-effects (persisting via the injected
 * persist lambda) flow through the reactive pipeline; the coroutine scope is
 * injected.
 */
sealed interface CardManagementEvent {
    data class EnterEditMode(
        val currentConfigs: List<CardConfiguration>,
    ) : CardManagementEvent

    data object SaveChanges : CardManagementEvent

    data object CancelChanges : CardManagementEvent

    data object ResetToDefaults : CardManagementEvent

    data class ToggleVisibility(
        val currentConfigs: List<CardConfiguration>,
        val cardId: CardId,
        val visible: Boolean,
    ) : CardManagementEvent

    data class ReorderCards(
        val currentConfigs: List<CardConfiguration>,
        val newOrder: List<CardConfiguration>,
    ) : CardManagementEvent

    data class DisplayModeChanged(
        val cardId: CardId,
        val mode: DashboardCardDisplayMode,
    ) : CardManagementEvent
}

/**
 * Card-management facade over the generic [LayoutManagementDelegate].
 *
 * The edit-mode state machine (pending list, save/cancel/reset, reorder, display mode) lives in
 * [LayoutManagementDelegate]; this class adds what is card-specific: validation of card ids
 * (unknown ids are programming errors and throw) and Health Connect permission gating of the
 * persisted list. Persistence stays only-on-save and cancellation is automatic with [scope].
 */
class CardManagementDelegate(
    defaultConfigurations: List<CardConfiguration>,
    private val persist: suspend (List<CardConfiguration>) -> Unit,
    scope: CoroutineScope,
    // Gates optional-card persistence on Health Connect permissions. Each defaults to
    // "granted"; the dashboard wires the real HealthConnectRepository checks through these.
    private val hasBodyTemperaturePermission: suspend () -> Boolean = { true },
    private val hasStepsPermission: suspend () -> Boolean = { true },
    private val hasWeightPermission: suspend () -> Boolean = { true },
    private val hasBodyFatPermission: suspend () -> Boolean = { true },
    private val hasBloodPressurePermission: suspend () -> Boolean = { true },
    private val hasOxygenSaturationPermission: suspend () -> Boolean = { true },
) {
    // Suspend persistence via the injected persist lambda, invoked by the inner delegate on save.
    //
    // This is the single choke point for persistence, regardless of which onEvent path
    // produced `configs` (manual edits, ResetToDefaults, etc.) -- filtering here, rather
    // than only where pendingConfigs is populated, guarantees optional HC cards can never
    // be written with isVisible = true unless the permission is currently granted. Note
    // this only gates what reaches persistence; the pendingConfigs StateFlow itself
    // (and therefore the management sheet's live display) is filtered separately by
    // DashboardFlowIntermediate's createDashboardCardStateFlow.
    private suspend fun persistConfigs(configs: List<CardConfiguration>) {
        var toPersist = configs
        if (!hasBodyTemperaturePermission()) toPersist = toPersist.filter { it.cardId != CardId.BODY_TEMPERATURE }
        if (!hasStepsPermission()) toPersist = toPersist.filter { it.cardId != CardId.STEPS }
        if (!hasWeightPermission()) toPersist = toPersist.filter { it.cardId != CardId.WEIGHT }
        if (!hasBodyFatPermission()) toPersist = toPersist.filter { it.cardId != CardId.BODY_FAT }
        if (!hasBloodPressurePermission()) toPersist = toPersist.filter { it.cardId != CardId.BLOOD_PRESSURE }
        if (!hasOxygenSaturationPermission()) toPersist = toPersist.filter { it.cardId != CardId.OXYGEN_SATURATION }
        persist(toPersist)
    }

    private val layout =
        LayoutManagementDelegate(
            defaultConfigurations = defaultConfigurations,
            persist = ::persistConfigs,
            scope = scope,
            withVisibility = { config, visible -> config.copy(isVisible = visible) },
            withPosition = { config, position -> config.copy(position = position) },
            withDisplayMode = { config, mode -> config.copy(requestedDisplayMode = mode) },
        )

    /**
     * Aggregated state -- derived via combine().stateIn() from the inner delegate's flows.
     */
    val state: StateFlow<CardManagementState> =
        combine(layout.isManaging, layout.pendingConfigs) { isManaging, pending ->
            CardManagementState(isManagingCards = isManaging, pendingConfigs = pending)
        }.stateIn(
            scope = scope,
            started = SharingStarted.Lazily,
            initialValue = CardManagementState(),
        )

    /** Convenience projections for existing call sites. */
    val isManagingCards: StateFlow<Boolean> = layout.isManaging
    val pendingConfigs: StateFlow<List<CardConfiguration>?> = layout.pendingConfigs

    /**
     * Single event entry point. All mutations route through here.
     */
    fun onEvent(event: CardManagementEvent) {
        when (event) {
            is CardManagementEvent.EnterEditMode -> layout.enterEditMode(event.currentConfigs)
            CardManagementEvent.SaveChanges -> layout.saveChanges()
            CardManagementEvent.CancelChanges -> layout.cancelChanges()
            CardManagementEvent.ResetToDefaults -> layout.onResetToDefaults()
            is CardManagementEvent.ToggleVisibility -> {
                val base = layout.pendingConfigs.value ?: event.currentConfigs
                require(base.any { it.cardId == event.cardId }) {
                    "Card ${event.cardId} not found in configurations"
                }
                layout.onToggleVisibility(event.currentConfigs, event.cardId, event.visible)
            }
            is CardManagementEvent.ReorderCards -> {
                val base = layout.pendingConfigs.value ?: event.currentConfigs
                validateReorder(base, event.newOrder)
                layout.onReorder(event.currentConfigs, event.newOrder)
            }
            is CardManagementEvent.DisplayModeChanged -> {
                val base = layout.pendingConfigs.value ?: return
                require(base.any { it.cardId == event.cardId })
                layout.onDisplayModeChanged(event.cardId, event.mode)
            }
        }
    }

    // Legacy convenience facade — all delegate to onEvent (no manual launches).

    fun enterEditMode(currentConfigs: List<CardConfiguration>) =
        onEvent(CardManagementEvent.EnterEditMode(currentConfigs))

    fun saveChanges() = onEvent(CardManagementEvent.SaveChanges)

    fun cancelChanges() = onEvent(CardManagementEvent.CancelChanges)

    fun onToggleCardVisibility(
        currentConfigs: List<CardConfiguration>,
        cardId: CardId,
        visible: Boolean,
    ) = onEvent(CardManagementEvent.ToggleVisibility(currentConfigs, cardId, visible))

    fun onReorderCards(
        currentConfigs: List<CardConfiguration>,
        newOrder: List<CardConfiguration>,
    ) = onEvent(CardManagementEvent.ReorderCards(currentConfigs, newOrder))

    fun onResetToDefaults() = onEvent(CardManagementEvent.ResetToDefaults)

    private fun validateReorder(
        cardConfigurations: List<CardConfiguration>,
        newOrder: List<CardConfiguration>,
    ) {
        val validIds = cardConfigurations.map { it.cardId }.toSet()
        require(newOrder.all { it.cardId in validIds }) {
            "Invalid card IDs in reorder request: ${newOrder.map { it.cardId }.toSet() - validIds}"
        }
        require(newOrder.size <= cardConfigurations.size) {
            "Reorder list size (${newOrder.size}) exceeds original configuration size (${cardConfigurations.size})"
        }
    }
}
