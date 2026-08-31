package app.readylytics.health.data.preferences

import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.core.model.domain.vitals.VitalsChartId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VitalsLayoutConfigurationsSerializerTest {
    @Test
    fun defaultValue_seedsVitalsCardsAndTrendCharts() {
        val defaultValue = VitalsLayoutConfigurationsSerializer.defaultValue

        // SettingsDefaults.DEFAULT_VITALS_CARDS/DEFAULT_VITALS_CHARTS drive the default value;
        // the assertions are lower bounds so adding a future default card/chart does not break them.
        assertTrue(defaultValue.vitalsCardsCount >= 4)
        assertEquals(CardId.RESTING_HR.name, defaultValue.getVitalsCards(0).cardId)
        assertTrue(defaultValue.getVitalsCards(0).isVisible)

        assertTrue(defaultValue.trendChartsCount >= 4)
        assertEquals(VitalsChartId.HRV_TREND.name, defaultValue.getTrendCharts(0).chartId)
    }

    @Test
    fun `missing card mode maps to null`() {
        val proto =
            VitalsCardConfigurationProto
                .newBuilder()
                .setCardId(CardId.HRV.name)
                .setIsVisible(true)
                .setPosition(1)
                .build()
        assertNull(requireNotNull(VitalsLayoutMapper.toCardDomain(proto)).requestedDisplayMode)
    }

    @Test
    fun `unknown card mode string maps to null`() {
        val proto =
            VitalsCardConfigurationProto
                .newBuilder()
                .setCardId(CardId.HRV.name)
                .setIsVisible(true)
                .setPosition(1)
                .setRequestedDisplayMode("TREND")
                .build()
        assertNull(requireNotNull(VitalsLayoutMapper.toCardDomain(proto)).requestedDisplayMode)
    }

    @Test
    fun `different cards round trip different modes`() {
        val cards =
            listOf(
                CardConfiguration(CardId.RESTING_HR, requestedDisplayMode = DashboardCardDisplayMode.GAUGE),
                CardConfiguration(CardId.HRV, requestedDisplayMode = DashboardCardDisplayMode.VALUE),
                CardConfiguration(CardId.OXYGEN_SATURATION, requestedDisplayMode = DashboardCardDisplayMode.BAR),
            )
        val restored =
            cards
                .map(VitalsLayoutMapper::toCardProto)
                .mapNotNull(VitalsLayoutMapper::toCardDomain)
        assertEquals(cards, restored)
    }

    @Test
    fun `charts round trip positions and visibility`() {
        val charts =
            listOf(
                VitalsChartConfiguration(VitalsChartId.HRV_TREND, isVisible = true, position = 0),
                VitalsChartConfiguration(VitalsChartId.BODY_TEMP_TREND, isVisible = false, position = 3),
            )
        val restored =
            charts
                .map(VitalsLayoutMapper::toChartProto)
                .mapNotNull(VitalsLayoutMapper::toChartDomain)
        assertEquals(charts, restored)
    }

    @Test
    fun `unknown chart id maps to null`() {
        val proto =
            VitalsChartConfigurationProto
                .newBuilder()
                .setChartId("SOME_FUTURE_CHART")
                .setIsVisible(true)
                .setPosition(0)
                .build()
        assertNull(VitalsLayoutMapper.toChartDomain(proto))
    }
}
