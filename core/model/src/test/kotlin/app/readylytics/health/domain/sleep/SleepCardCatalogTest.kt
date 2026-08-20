package app.readylytics.health.domain.sleep

import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepCardCatalogTest {
    private val topScore = SleepTopCardConfiguration(SleepTopCardId.SLEEP_SCORE)
    private val topGauge = SleepTopCardConfiguration(SleepTopCardId.SLEEP_DURATION_GAUGE)
    private val topBar = SleepTopCardConfiguration(SleepTopCardId.SLEEP_BREAKDOWN_BAR)

    private val metricCircadian = SleepMetricCardConfiguration(SleepMetricCardId.CIRCADIAN_CONSISTENCY)
    private val metricDeep = SleepMetricCardConfiguration(SleepMetricCardId.DEEP_SLEEP)
    private val metricNap = SleepMetricCardConfiguration(SleepMetricCardId.NAP_DURATION)

    @Test
    fun `gauge top cards support all modes with gauge default`() {
        assertEquals(
            listOf(
                DashboardCardDisplayMode.GAUGE,
                DashboardCardDisplayMode.BAR,
                DashboardCardDisplayMode.VALUE,
            ),
            SleepCardCatalog.topCardSpec(SleepTopCardId.SLEEP_SCORE)?.supportedModes,
        )
        assertEquals(
            DashboardCardDisplayMode.GAUGE,
            SleepCardCatalog.requestedTopCardMode(topScore),
        )
    }

    @Test
    fun `chart top cards have no spec and resolve to value`() {
        assertNull(SleepCardCatalog.topCardSpec(SleepTopCardId.SLEEP_BREAKDOWN_BAR))
        assertEquals(DashboardCardDisplayMode.VALUE, SleepCardCatalog.requestedTopCardMode(topBar))
    }

    @Test
    fun `percentage metric cards support all modes with value default`() {
        assertEquals(
            listOf(
                DashboardCardDisplayMode.GAUGE,
                DashboardCardDisplayMode.BAR,
                DashboardCardDisplayMode.VALUE,
            ),
            SleepCardCatalog.metricCardSpec(SleepMetricCardId.DEEP_SLEEP)?.supportedModes,
        )
        assertEquals(DashboardCardDisplayMode.VALUE, SleepCardCatalog.requestedMetricCardMode(metricDeep))
    }

    @Test
    fun `nap cards have no spec and resolve to value`() {
        assertNull(SleepCardCatalog.metricCardSpec(SleepMetricCardId.NAP_DURATION))
        assertEquals(DashboardCardDisplayMode.VALUE, SleepCardCatalog.requestedMetricCardMode(metricNap))
    }

    @Test
    fun `requested mode resolves explicit valid mode`() {
        val configured =
            topScore.copy(requestedDisplayMode = DashboardCardDisplayMode.BAR)
        assertEquals(DashboardCardDisplayMode.BAR, SleepCardCatalog.requestedTopCardMode(configured))
    }

    @Test
    fun `apply global mode only touches supported cards`() {
        val result =
            SleepCardCatalog.applyGlobalTopCardMode(
                listOf(topScore, topBar),
                DashboardCardDisplayMode.GAUGE,
            )
        assertEquals(DashboardCardDisplayMode.GAUGE, result[0].requestedDisplayMode)
        assertNull(result[1].requestedDisplayMode)
    }

    @Test
    fun `apply global metric mode skips value-only nap cards`() {
        val result =
            SleepCardCatalog.applyGlobalMetricCardMode(
                listOf(metricCircadian, metricNap),
                DashboardCardDisplayMode.GAUGE,
            )
        assertEquals(DashboardCardDisplayMode.GAUGE, result[0].requestedDisplayMode)
        assertNull(result[1].requestedDisplayMode)
    }

    @Test
    fun `reset clears all modes`() {
        val configured =
            topScore.copy(requestedDisplayMode = DashboardCardDisplayMode.GAUGE)
        assertNull(SleepCardCatalog.resetTopCardModes(listOf(configured))[0].requestedDisplayMode)

        val metricConfigured =
            metricDeep.copy(requestedDisplayMode = DashboardCardDisplayMode.BAR)
        assertNull(SleepCardCatalog.resetMetricCardModes(listOf(metricConfigured))[0].requestedDisplayMode)
    }
}
