package app.readylytics.health.data.repository

import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.entity.DailySummaryEntity
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.lang.reflect.Proxy

class ScoringHistoryRepositoryImplTest {
    private val heartRateResults = mutableMapOf<String, Any?>()
    private val dailySummaryResults = mutableMapOf<String, Any?>()
    private val heartRateDao = fakeDao<HeartRateDao>(heartRateResults)
    private val hrvDao = fakeDao<HrvDao>()
    private val sleepSessionDao = fakeDao<SleepSessionDao>()
    private val dailySummaryDao = fakeDao<DailySummaryDao>(dailySummaryResults)
    private val repository =
        ScoringHistoryRepositoryImpl(
            heartRateDao = heartRateDao,
            hrvDao = hrvDao,
            sleepSessionDao = sleepSessionDao,
            dailySummaryDao = dailySummaryDao,
        )

    @Test
    fun `getHeartRateRecordsByTimeRange returns pure domain HeartRateRecord`() =
        runTest {
            val entity =
                HeartRateRecordEntity(
                    id = "hr1_500",
                    timestampMs = 500L,
                    beatsPerMinute = 55,
                    recordType = "SLEEP",
                    sessionId = "s1",
                )
            heartRateResults["getByTimeRange"] = listOf(entity)

            val result = repository.getHeartRateRecordsByTimeRange(0L, 1_000L)

            assertEquals(1, result.size)
            assertEquals(55, result.first().beatsPerMinute)
            assertEquals("s1", result.first().sessionId)
        }

    @Test
    fun `getPreciseHrMax delegates to DailySummaryDao`() =
        runTest {
            dailySummaryResults["getPreciseHrMax"] = 185.0

            assertEquals(185.0, repository.getPreciseHrMax(1_000L))
        }

    @Test
    fun `hasAnyWorkoutOnlyTrimpData delegates to DailySummaryDao`() =
        runTest {
            dailySummaryResults["hasAnyWorkoutOnlyTrimpData"] = true

            assertEquals(true, repository.hasAnyWorkoutOnlyTrimpData())
        }

    @Test
    fun `getAllDailySummaries maps entities to domain DailySummary using the given zone`() =
        runTest {
            val entity = DailySummaryEntity(dateMidnightMs = 0L)
            dailySummaryResults["getAllSummaries"] = listOf(entity)

            val result = repository.getAllDailySummaries(ZoneOffset.UTC)

            assertEquals(1, result.size)
            assertEquals(LocalDate.of(1970, 1, 1), result.first().date)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> fakeDao(results: MutableMap<String, Any?> = mutableMapOf()): T =
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
