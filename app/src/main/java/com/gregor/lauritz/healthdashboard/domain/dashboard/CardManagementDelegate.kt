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

    fun toggleCardManagement() {
        _isManagingCards.value = !_isManagingCards.value
    }

    fun onToggleCardVisibility(
        currentConfigs: List<CardConfiguration>,
        cardId: CardId,
        visible: Boolean,
    ) = viewModelScope.launch {
        val updated = toggleCardVisibility(currentConfigs, cardId, visible)
        cardConfigRepository.updateDashboardCardConfigurations(updated)
    }

    fun onReorderCards(
        currentConfigs: List<CardConfiguration>,
        newOrder: List<CardConfiguration>,
    ) = viewModelScope.launch {
        val updated = reorderCards(currentConfigs, newOrder)
        cardConfigRepository.updateDashboardCardConfigurations(updated)
    }

    fun onResetToDefaults() =
        viewModelScope.launch {
            val defaults = com.gregor.lauritz.healthdashboard.data.preferences.SettingsDefaults.DEFAULT_DASHBOARD_CARDS
            cardConfigRepository.updateDashboardCardConfigurations(defaults)
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
