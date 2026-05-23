package com.gregor.lauritz.healthdashboard.domain.dashboard

import com.gregor.lauritz.healthdashboard.data.preferences.CardConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Aggregated card-management UI state derived purely from upstream flows.
 */
data class CardManagementState(
    val isManagingCards: Boolean = false,
    val pendingConfigs: List<CardConfiguration>? = null,
)

/**
 * Events that drive the delegate. All side-effects (persisting to repository)
 * flow through the reactive pipeline; no manual viewModelScope.launch is used.
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
}

/**
 * Reactive delegate for card-management state.
 *
 * State derived from MutableStateFlow.combine().stateIn() — no manual
 * viewModelScope.launch. Persistence side effects from SaveChanges flow
 * through flatMapLatest so cancellation is automatic when the parent
 * scope is cancelled (preventing the memory leak from orphaned coroutines).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardManagementDelegate(
    private val cardConfigRepository: CardConfigurationRepository,
    private val scope: CoroutineScope,
) {
    private val _isManagingCards = MutableStateFlow(false)
    private val _pendingConfigs = MutableStateFlow<List<CardConfiguration>?>(null)
    private val _persistTrigger = MutableStateFlow<List<CardConfiguration>?>(null)

    // Suspend persistence via repository. Called reactively on SaveChanges event.
    private suspend fun persistConfigs(configs: List<CardConfiguration>) {
        cardConfigRepository.updateDashboardCardConfigurations(configs)
    }

    init {
        scope.launch {
            _persistTrigger
                .filterNotNull()
                .collect { configs ->
                    persistConfigs(configs)
                }
        }
    }

    /**
     * Aggregated state — derived via combine().stateIn() from the source flows.
     */
    val state: StateFlow<CardManagementState> =
        combine(_isManagingCards, _pendingConfigs) { isManaging, pending ->
            CardManagementState(isManagingCards = isManaging, pendingConfigs = pending)
        }.stateIn(
            scope = scope,
            started = SharingStarted.Lazily,
            initialValue = CardManagementState(),
        )

    /** Convenience projections for existing call sites. */
    val isManagingCards: StateFlow<Boolean> = _isManagingCards.asStateFlow()
    val pendingConfigs: StateFlow<List<CardConfiguration>?> = _pendingConfigs.asStateFlow()

    /**
     * Single event entry point. All mutations route through here.
     */
    fun onEvent(event: CardManagementEvent) {
        when (event) {
            is CardManagementEvent.EnterEditMode -> {
                _pendingConfigs.value = event.currentConfigs
                _isManagingCards.value = true
            }
            CardManagementEvent.SaveChanges -> {
                _pendingConfigs.value?.let { configs ->
                    _persistTrigger.value = configs
                }
                _isManagingCards.value = false
                _pendingConfigs.value = null
            }
            CardManagementEvent.CancelChanges -> {
                _isManagingCards.value = false
                _pendingConfigs.value = null
            }
            CardManagementEvent.ResetToDefaults -> {
                _pendingConfigs.value = SettingsDefaults.DEFAULT_DASHBOARD_CARDS
            }
            is CardManagementEvent.ToggleVisibility -> {
                val base = _pendingConfigs.value ?: event.currentConfigs
                _pendingConfigs.value = toggleCardVisibility(base, event.cardId, event.visible)
            }
            is CardManagementEvent.ReorderCards -> {
                val base = _pendingConfigs.value ?: event.currentConfigs
                _pendingConfigs.value = reorderCards(base, event.newOrder)
            }
        }
    }

    // Legacy convenience facade — all delegate to onEvent (no manual launches).

    fun enterEditMode(currentConfigs: List<CardConfiguration>) =
        onEvent(CardManagementEvent.EnterEditMode(currentConfigs))

    fun saveChanges() = onEvent(CardManagementEvent.SaveChanges)

    fun cancelChanges() = onEvent(CardManagementEvent.CancelChanges)

    fun toggleCardManagement() {
        if (_isManagingCards.value) {
            onEvent(CardManagementEvent.CancelChanges)
        } else {
            _isManagingCards.value = true
        }
    }

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

    private fun toggleCardVisibility(
        cardConfigurations: List<CardConfiguration>,
        cardId: CardId,
        visible: Boolean,
    ): List<CardConfiguration> {
        require(cardConfigurations.any { it.cardId == cardId }) {
            "Card $cardId not found in configurations"
        }

        return cardConfigurations.map { config ->
            if (config.cardId == cardId) config.copy(isVisible = visible) else config
        }
    }

    private fun reorderCards(
        cardConfigurations: List<CardConfiguration>,
        newOrder: List<CardConfiguration>,
    ): List<CardConfiguration> {
        val validIds = cardConfigurations.map { it.cardId }.toSet()
        require(newOrder.all { it.cardId in validIds }) {
            "Invalid card IDs in reorder request: ${newOrder.map { it.cardId }.toSet() - validIds}"
        }
        require(newOrder.size <= cardConfigurations.size) {
            "Reorder list size (${newOrder.size}) exceeds original configuration size (${cardConfigurations.size})"
        }

        val reorderedIds = newOrder.map { it.cardId }.toSet()
        val hiddenCards = cardConfigurations.filter { it.cardId !in reorderedIds }

        return (newOrder + hiddenCards).mapIndexed { index, config ->
            config.copy(position = index)
        }
    }
}
