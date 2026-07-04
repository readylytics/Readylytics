package app.readylytics.health.core.ui.components

import app.readylytics.health.domain.model.MetricStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MetricStatusContainerToneTest {
    @Test
    fun neutralStatusUsesNeutralContainerTone() {
        assertEquals(MetricContainerTone.NEUTRAL, MetricStatus.NEUTRAL.containerTone())
    }

    @Test
    fun noDataStatusKeepsDefaultCardContainerTone() {
        assertEquals(MetricContainerTone.DEFAULT_CARD, MetricStatus.NO_DATA.containerTone())
    }
}
