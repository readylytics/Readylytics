package app.readylytics.health.data.preferences

import app.readylytics.health.domain.dashboard.CardId
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
}
