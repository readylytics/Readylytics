package com.gregor.lauritz.healthdashboard.data.preferences

import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CardConfigurationsSerializerTest {

    @Test
    fun defaultValue_hasCorrectDashboardDefaults() {
        val defaultValue = CardConfigurationsSerializer.defaultValue
        
        // SettingsDefaults.DEFAULT_DASHBOARD_CARDS has 10 cards by default
        assertTrue(defaultValue.dashboardCardsCount >= 10)
        assertEquals(CardId.SLEEP_SCORE.name, defaultValue.getDashboardCards(0).cardId)
        assertTrue(defaultValue.getDashboardCards(0).isVisible)
    }

    @Test
    fun defaultValue_hasCorrectSleepDefaults() {
        val defaultValue = CardConfigurationsSerializer.defaultValue
        
        assertTrue(defaultValue.sleepCardsCount >= 7)
        assertEquals(CardId.SLEEP_SCORE.name, defaultValue.getSleepCards(0).cardId)
    }

    @Test
    fun defaultValue_hasCorrectWorkoutDefaults() {
        val defaultValue = CardConfigurationsSerializer.defaultValue
        
        assertTrue(defaultValue.workoutCardsCount >= 5)
        assertEquals(CardId.READINESS.name, defaultValue.getWorkoutCards(0).cardId)
    }
}
