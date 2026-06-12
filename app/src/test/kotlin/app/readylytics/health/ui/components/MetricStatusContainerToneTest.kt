package app.readylytics.health.ui.components

import app.readylytics.health.domain.model.MetricStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MetricStatusContainerToneTest {
    @Test
    fun neutralStatusUsesDefaultCardContainerTone() {
        assertEquals(MetricContainerTone.DEFAULT_CARD, MetricStatus.NEUTRAL.containerTone())
    }
}
