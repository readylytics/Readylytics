package com.gregor.lauritz.healthdashboard.domain.dashboard

import com.gregor.lauritz.healthdashboard.data.preferences.CardConfigurationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CardManagementDelegate(
    private val cardConfigRepository: CardConfigurationRepository,
    private val viewModelScope: CoroutineScope,
) {
    private val _isManagingCards = MutableStateFlow(false)
    val isManagingCards: StateFlow<Boolean> = _isManagingCards.asStateFlow()

    private val _pendingConfigs = MutableStateFlow<List<CardConfiguration>?>(null)
    val pendingConfigs: StateFlow<List<CardConfiguration>?> = _pendingConfigs.asStateFlow()

    fun enterEditMode(currentConfigs: List<CardConfiguration>) {
        _pendingConfigs.value = currentConfigs
        _isManagingCards.value = true
    }

    fun saveChanges() =
        viewModelScope.launch {
            _pendingConfigs.value?.let { configs ->
                cardConfigRepository.updateDashboardCardConfigurations(configs)
            }
            _isManagingCards.value = false
            _pendingConfigs.value = null
        }

    fun cancelChanges() {
        _isManagingCards.value = false
        _pendingConfigs.value = null
    }

    fun toggleCardManagement() {
        // Legacy toggle support if needed, but preferred to use enterEditMode/saveChanges
        if (_isManagingCards.value) {
            cancelChanges()
        } else {
            _isManagingCards.value = true
        }
    }

    fun onToggleCardVisibility(
        currentConfigs: List<CardConfiguration>,
        cardId: CardId,
        visible: Boolean,
    ) {
        val baseConfigs = _pendingConfigs.value ?: currentConfigs
        val updated = toggleCardVisibility(baseConfigs, cardId, visible)
        _pendingConfigs.value = updated
    }

    fun onReorderCards(
        currentConfigs: List<CardConfiguration>,
        newOrder: List<CardConfiguration>,
    ) {
        val baseConfigs = _pendingConfigs.value ?: currentConfigs
        val updated = reorderCards(baseConfigs, newOrder)
        _pendingConfigs.value = updated
    }

    fun onResetToDefaults() =
        viewModelScope.launch {
            val defaults = com.gregor.lauritz.healthdashboard.data.preferences.SettingsDefaults.DEFAULT_DASHBOARD_CARDS
            _pendingConfigs.value = defaults
        }

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
