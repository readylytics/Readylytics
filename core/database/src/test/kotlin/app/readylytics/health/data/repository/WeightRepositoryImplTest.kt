package app.readylytics.health.data.repository

import app.readylytics.health.data.local.dao.WeightRecordDao
import app.readylytics.health.data.local.entity.WeightRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class WeightRepositoryImplTest {
    private val results = mutableMapOf<String, Any?>()
    private val dao = fakeDao<WeightRecordDao>(results)
    private val repository = WeightRepositoryImpl(dao)

    @Test
    fun `getByDateRangePaged delegates to DAO and maps entities to domain`() =
        runTest {
            val entity =
                WeightRecordEntity(
                    id = "w1",
                    timestampMs = 150L,
                    weightKg = 75.5f,
                    deviceName = "Watch",
                )
            results["getPagedByTimeRange"] = listOf(entity)

            val result = repository.getByDateRangePaged(100L, 200L, 10, 0)

            assertEquals(1, result.size)
            val mapped = result.first()
            assertEquals("w1", mapped.id)
            assertEquals(150L, mapped.time.toEpochMilli())
            assertEquals(75.5f, mapped.weightKg)
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
