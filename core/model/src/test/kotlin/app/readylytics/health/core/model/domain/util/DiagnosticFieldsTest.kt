package app.readylytics.health.core.model.domain.util

import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import org.junit.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SEC-101 / OD-1: the emittable diagnostic field set stays enumerable. */
class DiagnosticFieldsTest {
    private val fields =
        DiagnosticFields::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }

    @Test
    fun `every field is an enum or a boxed Int`() {
        assertTrue(fields.isNotEmpty())
        fields.forEach { field ->
            assertTrue(
                field.type.isEnum || field.type == Integer::class.java,
                "DiagnosticFields.${field.name} is ${field.type.name}; only enums or a relative Int may be emitted",
            )
        }
    }

    @Test
    fun `the only Int is the relative day offset`() {
        assertEquals(listOf("dayOffsetFromToday"), fields.filter { it.type == Integer::class.java }.map { it.name })
    }

    @Test
    fun `enum fields are exactly the approved bounded enums`() {
        assertEquals(
            setOf(HealthDataType::class.java, ResyncPhase::class.java, CountBucket::class.java),
            fields.filter { it.type.isEnum }.map { it.type }.toSet(),
        )
    }

    @Test
    fun `render emits enum names and the offset only`() {
        val all = DiagnosticFields(HealthDataType.HEART_RATE, ResyncPhase.INGEST, -3, CountBucket.DOZENS)
        assertEquals("dataType=HEART_RATE phase=INGEST dayOffset=-3 records=DOZENS", all.render())
        assertEquals("", DiagnosticFields().render())
    }

    @Test
    fun `count buckets are coarse ranges`() {
        assertEquals(CountBucket.NONE, CountBucket.of(-1))
        assertEquals(CountBucket.NONE, CountBucket.of(0))
        assertEquals(CountBucket.FEW, CountBucket.of(1))
        assertEquals(CountBucket.FEW, CountBucket.of(10))
        assertEquals(CountBucket.DOZENS, CountBucket.of(11))
        assertEquals(CountBucket.DOZENS, CountBucket.of(100))
        assertEquals(CountBucket.MANY, CountBucket.of(101))
    }
}
