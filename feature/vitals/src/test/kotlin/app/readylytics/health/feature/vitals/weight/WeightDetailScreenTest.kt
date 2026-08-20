package app.readylytics.health.feature.vitals.weight

import app.readylytics.health.core.model.domain.model.MetricStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class WeightDetailScreenTest {
    @Test
    fun `bmi card status uses canonical boundaries`() {
        assertEquals(MetricStatus.WARNING, bmiCardStatus(18.4f))
        assertEquals(MetricStatus.OPTIMAL, bmiCardStatus(18.5f))
        assertEquals(MetricStatus.WARNING, bmiCardStatus(25f))
        assertEquals(MetricStatus.POOR, bmiCardStatus(30f))
    }
}
