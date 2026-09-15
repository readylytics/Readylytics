package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private val minuteBucketDao = fakeDao<MinuteBucketDao>()
    private val repository =
        ScoringHistoryRepositoryImpl(
            heartRateDao = heartRateDao,
            hrvDao = hrvDao,
            sleepSessionDao = sleepSessionDao,
            dailySummaryDao = dailySummaryDao,
            minuteBucketDao = minuteBucketDao,
        )

    @Test
    fun `getHeartRateRecordsByTimeRange returns pure domain HeartRateRecord`() =
        runTest {
            val entity =
                HeartRateRecordEntity(
                    sourceRecordRef = 1L,
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

    // ─── countEligibleSleepDaysThrough{,Batch} (Task C2 / OD-2) ────────────────────────────────
    // Uses real mockk-backed DAOs plus the repository's real (default) ScoringCalculator so
    // eligibility is exercised via genuine validateNight behavior, not a stubbed boolean.

    private fun testSession(
        id: String,
        startTime: Long,
        durationMinutes: Int = 480,
    ) = SleepSessionEntity(
        id = id,
        startTime = startTime,
        endTime = startTime + durationMinutes * 60_000L,
        durationMinutes = durationMinutes,
        deepSleepMinutes = 60,
        remSleepMinutes = 60,
        lightSleepMinutes = (durationMinutes - 120).coerceAtLeast(0),
        efficiency = 0.9f,
        awakeMinutes = 10,
    )

    private fun repositoryWithSessions(
        sessions: List<SleepSessionEntity>,
        rmssdBySession: Map<String, List<Float>> = sessions.associate { it.id to listOf(40f) },
    ): ScoringHistoryRepositoryImpl {
        val heartRateDao = mockk<HeartRateDao>()
        val hrvDao = mockk<HrvDao>()
        val sleepSessionDao = mockk<SleepSessionDao>()
        val dailySummaryDao = mockk<DailySummaryDao>()
        val minuteBucketDao = mockk<MinuteBucketDao>(relaxed = true)
        coEvery { sleepSessionDao.getBetween(any(), any()) } returns sessions
        coEvery { hrvDao.getSleepRmssdForSessionsMap(any()) } returns rmssdBySession
        coEvery { heartRateDao.getSleepHrProjectionForSessions(any()) } returns emptyList()
        return ScoringHistoryRepositoryImpl(heartRateDao, hrvDao, sleepSessionDao, dailySummaryDao, minuteBucketDao)
    }

    @Test
    fun `countEligibleSleepDaysThrough returns null when there is no retained session data`() =
        runTest {
            val repository = repositoryWithSessions(emptyList())

            assertNull(repository.countEligibleSleepDaysThrough(LocalDate.of(2026, 1, 1), ZoneOffset.UTC))
        }

    @Test
    fun `countEligibleSleepDaysThrough dedupes two eligible sessions on the same score day`() =
        runTest {
            val day = LocalDate.of(2026, 1, 10)
            val dayStartMs = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val sessionA = testSession("a", dayStartMs)
            val sessionB = testSession("b", dayStartMs + 9 * 3_600_000L, durationMinutes = 300)
            val repository = repositoryWithSessions(listOf(sessionA, sessionB))

            val result = repository.countEligibleSleepDaysThrough(day, ZoneOffset.UTC)

            assertEquals(1, result)
        }

    @Test
    fun `countEligibleSleepDaysThrough excludes a session that fails validateNight`() =
        runTest {
            val day = LocalDate.of(2026, 1, 10)
            val dayStartMs = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            // 100 minutes is below MIN_VALID_SLEEP_DURATION_MINUTES (240) -- not eligible.
            val tooShort = testSession("short", dayStartMs, durationMinutes = 100)
            val repository = repositoryWithSessions(listOf(tooShort))

            val result = repository.countEligibleSleepDaysThrough(day, ZoneOffset.UTC)

            assertEquals(0, result)
        }

    @Test
    fun `countEligibleSleepDaysThroughBatch returns a cumulative count per requested day`() =
        runTest {
            val day1 = LocalDate.of(2026, 1, 1)
            val day2 = LocalDate.of(2026, 1, 2)
            val day3 = LocalDate.of(2026, 1, 3)
            val sessions =
                listOf(day1, day2, day3).mapIndexed { index, day ->
                    testSession("s$index", day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                }
            val repository = repositoryWithSessions(sessions)

            val result = repository.countEligibleSleepDaysThroughBatch(listOf(day1, day2, day3), ZoneOffset.UTC)

            assertEquals(1, result[day1])
            assertEquals(2, result[day2])
            assertEquals(3, result[day3])
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
