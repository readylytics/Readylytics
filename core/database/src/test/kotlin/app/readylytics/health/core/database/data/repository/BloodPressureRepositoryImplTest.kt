package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.core.model.domain.model.BloodPressureRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class BloodPressureRepositoryImplTest {
    private val results = mutableMapOf<String, Any?>()
    private val dao = fakeDao<BloodPressureRecordDao>(results)
    private val repository = BloodPressureRepositoryImpl(dao)

    @Test
    fun `getByDateRangePaged delegates to DAO and maps entities to domain`() =
        runTest {
            val entity =
                BloodPressureRecordEntity(
                    id = "bp1",
                    timestampMs = 150L,
                    systolicMmHg = 120,
                    diastolicMmHg = 80,
                    deviceName = "Watch",
                )
            results["getPagedByTimeRange"] = listOf(entity)

            val result = repository.getByDateRangePaged(100L, 200L, 10, 0)

            assertEquals(1, result.size)
            val mapped = result.first()
            assertEquals("bp1", mapped.id)
            assertEquals(150L, mapped.time.toEpochMilli())
            assertEquals(120, mapped.systolicMmHg)
            assertEquals(80, mapped.diastolicMmHg)
            assertEquals("Watch", mapped.deviceName)
        }

    @Test
    fun `countByDateRange delegates to DAO`() =
        runTest {
            results["countByTimeRange"] = 42

            val result = repository.countByDateRange(100L, 200L)

            assertEquals(42, result)
        }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> fakeDao(results: MutableMap<String, Any?>): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            results[method.name]
                ?: when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
        } as T
}
