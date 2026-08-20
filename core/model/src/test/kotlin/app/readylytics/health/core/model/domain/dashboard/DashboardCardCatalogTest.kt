package app.readylytics.health.core.model.domain.dashboard

import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardCardCatalogTest {
    private val expectedDefaults =
        mapOf(
            CardId.SLEEP_SCORE to DashboardCardDisplayMode.GAUGE,
            CardId.READINESS to DashboardCardDisplayMode.GAUGE,
            CardId.STEPS to DashboardCardDisplayMode.BAR,
            CardId.HRV to DashboardCardDisplayMode.VALUE,
            CardId.SLEEP_RHR to DashboardCardDisplayMode.VALUE,
            CardId.SLEEP_DURATION to DashboardCardDisplayMode.VALUE,
            CardId.STRAIN_RATIO to DashboardCardDisplayMode.VALUE,
            CardId.RAS_DAILY to DashboardCardDisplayMode.VALUE,
            CardId.CIRCADIAN_CONSISTENCY to DashboardCardDisplayMode.VALUE,
            CardId.RESTING_HR to DashboardCardDisplayMode.VALUE,
            CardId.SLEEP_EFFICIENCY to DashboardCardDisplayMode.VALUE,
            CardId.HEART_RATE to DashboardCardDisplayMode.VALUE,
            CardId.WEIGHT to DashboardCardDisplayMode.VALUE,
            CardId.BODY_FAT to DashboardCardDisplayMode.VALUE,
            CardId.BLOOD_PRESSURE to DashboardCardDisplayMode.VALUE,
            CardId.OXYGEN_SATURATION to DashboardCardDisplayMode.VALUE,
        )

    @Test
    fun `catalog has correct legacy defaults and supported modes`() {
        expectedDefaults.forEach { (cardId, defaultMode) ->
            val spec = DashboardCardCatalog.spec(cardId)
            assertNotNull("Missing spec for $cardId", spec)
            assertEquals(defaultMode, spec!!.legacyDefaultMode)

            when (cardId) {
                CardId.HEART_RATE, CardId.BLOOD_PRESSURE -> {
                    assertEquals(listOf(DashboardCardDisplayMode.VALUE), spec.supportedModes)
                }
                CardId.STEPS -> {
                    assertEquals(listOf(DashboardCardDisplayMode.BAR), spec.supportedModes)
                }
                else -> {
                    assertEquals(
                        listOf(
                            DashboardCardDisplayMode.GAUGE,
                            DashboardCardDisplayMode.BAR,
                            DashboardCardDisplayMode.VALUE,
                        ),
                        spec.supportedModes,
                    )
                }
            }
        }
    }

    @Test
    fun `unsupported request safely renders legacy default`() {
        val config =
            CardConfiguration(
                cardId = CardId.HEART_RATE,
                requestedDisplayMode = DashboardCardDisplayMode.GAUGE, // Not supported by HEART_RATE
            )

        assertEquals(DashboardCardDisplayMode.VALUE, DashboardCardCatalog.requestedMode(config))
    }

    @Test
    fun `Insights and AI recommendation are not in catalog`() {
        assertNull(DashboardCardCatalog.spec(CardId.INSIGHTS))
        assertNull(DashboardCardCatalog.spec(CardId.AI_RECOMMENDATION))
    }

    @Test
    fun `every default dashboard card except Insights and AI recommendation has a catalog spec`() {
        SettingsDefaults.DEFAULT_DASHBOARD_CARDS
            .map { it.cardId }
            .filter { it != CardId.INSIGHTS && it != CardId.AI_RECOMMENDATION }
            .forEach { cardId ->
                assertNotNull("Missing catalog spec for $cardId", DashboardCardCatalog.spec(cardId))
            }
    }

    @Test
    fun `AI recommendation card is a visible default appended last`() {
        val aiCard = SettingsDefaults.DEFAULT_DASHBOARD_CARDS.last()

        assertEquals(CardId.AI_RECOMMENDATION, aiCard.cardId)
        assertTrue(aiCard.isVisible)
        assertEquals(1, SettingsDefaults.DEFAULT_DASHBOARD_CARDS.count { it.cardId == CardId.AI_RECOMMENDATION })
    }

    @Test
    fun `applyGlobalDisplayMode overrides cards that support the requested mode`() {
        val configs =
            listOf(
                CardConfiguration(cardId = CardId.SLEEP_SCORE, requestedDisplayMode = null),
                CardConfiguration(cardId = CardId.HRV, requestedDisplayMode = DashboardCardDisplayMode.BAR),
            )

        val result = DashboardCardCatalog.applyGlobalDisplayMode(configs, DashboardCardDisplayMode.GAUGE)

        assertEquals(
            DashboardCardDisplayMode.GAUGE,
            result.first { it.cardId == CardId.SLEEP_SCORE }.requestedDisplayMode,
        )
        assertEquals(
            DashboardCardDisplayMode.GAUGE,
            result.first { it.cardId == CardId.HRV }.requestedDisplayMode,
        )
    }

    @Test
    fun `applyGlobalDisplayMode leaves cards unchanged when the mode is unsupported`() {
        val configs =
            listOf(
                CardConfiguration(cardId = CardId.HEART_RATE, requestedDisplayMode = null),
                CardConfiguration(cardId = CardId.STEPS, requestedDisplayMode = null),
            )

        val result = DashboardCardCatalog.applyGlobalDisplayMode(configs, DashboardCardDisplayMode.GAUGE)

        assertNull(result.first { it.cardId == CardId.HEART_RATE }.requestedDisplayMode)
        assertNull(result.first { it.cardId == CardId.STEPS }.requestedDisplayMode)
    }

    @Test
    fun `applyGlobalDisplayMode leaves cards with no catalog spec unchanged`() {
        val configs = listOf(CardConfiguration(cardId = CardId.INSIGHTS, requestedDisplayMode = null))

        val result = DashboardCardCatalog.applyGlobalDisplayMode(configs, DashboardCardDisplayMode.VALUE)

        assertNull(result.first { it.cardId == CardId.INSIGHTS }.requestedDisplayMode)
    }

    @Test
    fun `resetAllDisplayModes clears every card's requestedDisplayMode`() {
        val configs =
            listOf(
                CardConfiguration(cardId = CardId.SLEEP_SCORE, requestedDisplayMode = DashboardCardDisplayMode.GAUGE),
                CardConfiguration(cardId = CardId.HRV, requestedDisplayMode = DashboardCardDisplayMode.BAR),
                CardConfiguration(cardId = CardId.INSIGHTS, requestedDisplayMode = null),
            )

        val result = DashboardCardCatalog.resetAllDisplayModes(configs)

        result.forEach { assertNull(it.requestedDisplayMode) }
    }

    @Test
    fun `resetAllDisplayModes preserves card identity, visibility, and position`() {
        val configs =
            listOf(
                CardConfiguration(
                    cardId = CardId.STEPS,
                    isVisible = false,
                    position = 3,
                    requestedDisplayMode = DashboardCardDisplayMode.BAR,
                ),
            )

        val result = DashboardCardCatalog.resetAllDisplayModes(configs)

        assertEquals(CardId.STEPS, result.single().cardId)
        assertFalse(result.single().isVisible)
        assertEquals(3, result.single().position)
    }
}
