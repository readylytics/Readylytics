package app.readylytics.health.data.preferences

import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun `missing proto mode maps to null`() {
        val proto =
            CardConfigurationProto
                .newBuilder()
                .setCardId(CardId.HRV.name)
                .setIsVisible(true)
                .setPosition(2)
                .build()
        assertNull(requireNotNull(CardConfigurationMapper.toDomain(proto)).requestedDisplayMode)
    }

    @Test
    fun `unknown proto mode string maps to null`() {
        val proto =
            CardConfigurationProto
                .newBuilder()
                .setCardId(CardId.HRV.name)
                .setIsVisible(true)
                .setPosition(2)
                .setRequestedDisplayMode("TREND")
                .build()
        assertNull(requireNotNull(CardConfigurationMapper.toDomain(proto)).requestedDisplayMode)
    }

    @Test
    fun `different cards round trip different modes`() {
        val cards =
            listOf(
                CardConfiguration(CardId.HRV, requestedDisplayMode = DashboardCardDisplayMode.BAR),
                CardConfiguration(CardId.READINESS, requestedDisplayMode = DashboardCardDisplayMode.VALUE),
            )
        val restored =
            cards
                .map(CardConfigurationMapper::toProto)
                .mapNotNull(CardConfigurationMapper::toDomain)
        assertEquals(cards, restored)
    }
}
