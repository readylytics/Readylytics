package com.gregor.lauritz.healthdashboard.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId
import com.gregor.lauritz.healthdashboard.domain.dashboard.ScreenType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CardConfigurationRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val DASHBOARD_CARDS = stringPreferencesKey("dashboard_cards")
        val SLEEP_CARDS = stringPreferencesKey("sleep_cards")
        val WORKOUT_CARDS = stringPreferencesKey("workout_cards")
    }

    fun dashboardCardConfigurations(): Flow<List<CardConfiguration>> =
        getCardConfigurations(Keys.DASHBOARD_CARDS, SettingsDefaults.DEFAULT_DASHBOARD_CARDS)

    fun sleepCardConfigurations(): Flow<List<CardConfiguration>> =
        getCardConfigurations(Keys.SLEEP_CARDS, SettingsDefaults.DEFAULT_SLEEP_CARDS)

    fun workoutCardConfigurations(): Flow<List<CardConfiguration>> =
        getCardConfigurations(Keys.WORKOUT_CARDS, SettingsDefaults.DEFAULT_WORKOUT_CARDS)

    private fun getCardConfigurations(
        key: Preferences.Key<String>,
        defaults: List<CardConfiguration>,
    ): Flow<List<CardConfiguration>> =
        dataStore.data.map { prefs ->
            prefs[key]
                ?.let { CardConfigurationSerializer.deserialize(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: defaults
        }

    suspend fun updateCardConfigurations(
        screenType: ScreenType,
        cards: List<CardConfiguration>,
    ) {
        val key = when (screenType) {
            ScreenType.DASHBOARD -> Keys.DASHBOARD_CARDS
            ScreenType.SLEEP -> Keys.SLEEP_CARDS
            ScreenType.WORKOUTS -> Keys.WORKOUT_CARDS
        }
        dataStore.edit { it[key] = CardConfigurationSerializer.serialize(cards) }
    }

    suspend fun toggleCardVisibility(
        cardConfigurations: List<CardConfiguration>,
        cardId: CardId,
        visible: Boolean,
    ): List<CardConfiguration> {
        // Validate that cardId exists in configurations
        require(cardConfigurations.any { it.cardId == cardId }) {
            "Card $cardId not found in configurations"
        }

        return cardConfigurations.map { config ->
            if (config.cardId == cardId) config.copy(isVisible = visible) else config
        }
    }

    suspend fun reorderCards(
        cardConfigurations: List<CardConfiguration>,
        newOrder: List<CardConfiguration>,
    ): List<CardConfiguration> {
        // Validate that newOrder contains only valid cards from cardConfigurations
        val validIds = cardConfigurations.map { it.cardId }.toSet()
        require(newOrder.all { it.cardId in validIds }) {
            "Invalid card IDs in reorder request: ${newOrder.map { it.cardId }.toSet() - validIds}"
        }
        require(newOrder.size <= cardConfigurations.size) {
            "Reorder list size (${newOrder.size}) exceeds original configuration size (${cardConfigurations.size})"
        }

        return newOrder.mapIndexed { index, config ->
            config.copy(position = index)
        }
    }
}
