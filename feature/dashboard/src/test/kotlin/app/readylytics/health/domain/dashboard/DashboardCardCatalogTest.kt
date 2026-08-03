package app.readylytics.health.domain.dashboard

import app.readylytics.health.data.preferences.SettingsDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `Insights is not in catalog`() {
        assertNull(DashboardCardCatalog.spec(CardId.INSIGHTS))
    }

    @Test
    fun `every default dashboard card except Insights has a catalog spec`() {
        SettingsDefaults.DEFAULT_DASHBOARD_CARDS
            .map { it.cardId }
            .filter { it != CardId.INSIGHTS }
            .forEach { cardId ->
                assertNotNull("Missing catalog spec for $cardId", DashboardCardCatalog.spec(cardId))
            }
    }
}
