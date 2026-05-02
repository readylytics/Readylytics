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
        screenType: ScreenType,
        currentConfigs: List<CardConfiguration>,
        cardId: CardId,
    ) = viewModelScope.launch {
        val isCurrentlyVisible = currentConfigs.find { it.cardId == cardId }?.isVisible ?: false
        // toggleCardVisibility is a pure function, no need to launch
        val updated = cardConfigRepository.toggleCardVisibility(
            currentConfigs,
            cardId,
            !isCurrentlyVisible
        )
        // Only the persistence operation needs to be suspended
        cardConfigRepository.updateCardConfigurations(screenType, updated)
    }

    fun onReorderCards(
        screenType: ScreenType,
        currentConfigs: List<CardConfiguration>,
        newOrder: List<CardConfiguration>,
    ) = viewModelScope.launch {
        // reorderCards is a pure function, no need for suspension
        val updated = cardConfigRepository.reorderCards(currentConfigs, newOrder)
        // Only the persistence operation needs to be suspended
        cardConfigRepository.updateCardConfigurations(screenType, updated)
    }

    fun onResetToDefaults(
        screenType: ScreenType,
    ) = viewModelScope.launch {
        val defaults = when (screenType) {
            ScreenType.DASHBOARD -> com.gregor.lauritz.healthdashboard.data.preferences.SettingsDefaults.DEFAULT_DASHBOARD_CARDS
            ScreenType.SLEEP -> com.gregor.lauritz.healthdashboard.data.preferences.SettingsDefaults.DEFAULT_SLEEP_CARDS
            ScreenType.WORKOUTS -> com.gregor.lauritz.healthdashboard.data.preferences.SettingsDefaults.DEFAULT_WORKOUT_CARDS
        }
        cardConfigRepository.updateCardConfigurations(screenType, defaults)
    }
}
