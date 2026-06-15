package app.readylytics.health.ui.insights

import app.readylytics.health.domain.insights.detail.InsightDetailType
import app.readylytics.health.domain.model.InsightType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class InsightDetailResourceSpecTest {
    @Test
    fun `every emitted insight type has detail resources`() {
        val missing = InsightType.entries.filter { InsightDetailResourceSpec.forType(it) == null }

        assertEquals(emptyList<InsightType>(), missing)
    }

    @Test
    fun `physiology insights have confidence`() {
        InsightType.entries.forEach { type ->
            val spec = requireNotNull(InsightDetailResourceSpec.forType(type))
            if (spec.type == InsightDetailType.PHYSIOLOGY) {
                assertNotNull("$type confidence", spec.confidence)
            }
        }
    }
}
