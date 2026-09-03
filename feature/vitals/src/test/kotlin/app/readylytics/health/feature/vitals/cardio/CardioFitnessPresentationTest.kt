package app.readylytics.health.feature.vitals.cardio

import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.preferences.Gender
import app.readylytics.health.core.scoring.domain.cardio.CooperCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardioFitnessPresentationTest {
    @Test
    fun formatsVo2MaxValueProperly() {
        val value = 48.23f
        val formatted = String.format(java.util.Locale.US, "%.1f", value)
        assertEquals("48.2", formatted)
    }

    @Test
    fun mapsCooperCategoryToMetricStatus() {
        assertEquals(MetricStatus.OPTIMAL, CooperCategory.SUPERIOR.toMetricStatus())
        assertEquals(MetricStatus.OPTIMAL, CooperCategory.EXCELLENT.toMetricStatus())
        assertEquals(MetricStatus.NEUTRAL, CooperCategory.GOOD.toMetricStatus())
        assertEquals(MetricStatus.WARNING, CooperCategory.FAIR.toMetricStatus())
        assertEquals(MetricStatus.POOR, CooperCategory.POOR.toMetricStatus())
    }

    @Test
    fun assessVo2MaxClassifiesWhenValuePresent() {
        val assessment = assessVo2Max(vo2Max = 50f, source = "WEARABLE", age = 25, gender = Gender.MALE)

        assertEquals(50f, assessment.value)
        assertEquals("WEARABLE", assessment.source)
        assertEquals(CooperCategory.EXCELLENT, assessment.category)
        assertEquals(MetricStatus.OPTIMAL, assessment.status)
    }

    @Test
    fun assessVo2MaxDegradesToCalibratingWhenMissing() {
        val assessment = assessVo2Max(vo2Max = null, source = null, age = 25, gender = Gender.MALE)

        assertNull(assessment.value)
        assertNull(assessment.category)
        assertEquals(MetricStatus.CALIBRATING, assessment.status)
    }
}
