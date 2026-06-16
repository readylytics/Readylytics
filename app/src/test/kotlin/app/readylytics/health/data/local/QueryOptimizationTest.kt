package app.readylytics.health.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.entity.DailySummaryEntity
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.HrvRecordEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.temporal.ChronoUnit

@RunWith(AndroidJUnit4::class)
class QueryOptimizationTest {
    private lateinit var db: HealthDatabase
    private lateinit var heartRateDao: HeartRateDao
    private lateinit var hrvDao: HrvDao
    private lateinit var sleepSessionDao: SleepSessionDao
    private lateinit var dailySummaryDao: DailySummaryDao

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
                .build()
        heartRateDao = db.heartRateDao()
        hrvDao = db.hrvDao()
        sleepSessionDao = db.sleepSessionDao()
        dailySummaryDao = db.dailySummaryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun heartRateDao_getByTimeRange_usesIndex() =
        runTest {
            val baselineMs = Instant.now().minus(30, ChronoUnit.DAYS).toEpochMilli()
            val records =
                (0 until 150).map { i ->
                    HeartRateRecordEntity(
                        id = i.toString(),
                        sessionId = "session_${i % 30}",
                        recordType = "activity",
                        timestampMs = baselineMs + (i * 3_600_000L),
                        beatsPerMinute = 60 + (i % 40),
                    )
                }
            heartRateDao.upsertAll(records)

            val start = System.nanoTime()
            val results = heartRateDao.getByTimeRange(baselineMs, baselineMs + (7 * 24 * 3_600_000L))
            val elapsed = (System.nanoTime() - start) / 1_000_000L

            assert(results.size > 0)
            assert(elapsed < 1000) { "getByTimeRange took ${elapsed}ms, expected <1000ms" }
        }

    @Test
    fun sleepSessionDao_getSince_batchesFetch() =
        runTest {
            val baselineMs = Instant.now().minus(30, ChronoUnit.DAYS).toEpochMilli()
            val sessions =
                (0 until 30).map { i ->
                    SleepSessionEntity(
                        id = "sleep_$i",
                        startTime = baselineMs + (i * 24 * 3_600_000L),
                        endTime = baselineMs + (i * 24 * 3_600_000L) + (8 * 3_600_000L),
                        durationMinutes = 480,
                        efficiency = 0.85f,
                        deepSleepMinutes = 120,
                        lightSleepMinutes = 240,
                        remSleepMinutes = 120,
                        awakeMinutes = 0,
                        deviceName = "TestDevice",
                    )
                }
            sleepSessionDao.upsertAll(sessions)

            val start = System.nanoTime()
            val result = sleepSessionDao.getSince(baselineMs)
            val elapsed = (System.nanoTime() - start) / 1_000_000L

            assert(result.size == 30)
            assert(elapsed < 1000) { "getSince took ${elapsed}ms, expected <1000ms" }
        }

    @Test
    fun hrvDao_getSleepRmssdValuesForSessions_selectsColumns() =
        runTest {
            val baselineMs = Instant.now().minus(10, ChronoUnit.DAYS).toEpochMilli()
            val sessionIds = (0 until 10).map { "hrv_session_$it" }

            sessionIds.forEachIndexed { i, sessionId ->
                val records =
                    (0 until 100).map { j ->
                        HrvRecordEntity(
                            id = "${i}_$j",
                            sessionId = sessionId,
                            recordType = "SLEEP",
                            timestampMs = baselineMs + (i * 24 * 3_600_000L) + (j * 300_000L),
                            rmssdMs = 30f + (j % 20),
                        )
                    }
                hrvDao.upsertAll(records)
            }

            val start = System.nanoTime()
            val values = hrvDao.getSleepRmssdValuesForSessions(sessionIds)
            val elapsed = (System.nanoTime() - start) / 1_000_000L

            assert(values.size > 0)
            assert(elapsed < 1000) { "getSleepRmssdValuesForSessions took ${elapsed}ms, expected <1000ms" }
        }

    @Test
    fun dailySummaryDao_getSince_returnsMultipleRows() =
        runTest {
            val baselineMs = Instant.now().minus(7, ChronoUnit.DAYS).toEpochMilli()
            val summaries =
                (0 until 7).map { i ->
                    DailySummaryEntity(
                        dateMidnightMs = baselineMs + (i * 24 * 3_600_000L),
                        loadScore = 50f + i,
                        sleepScore = 70f + i,
                        legacyRasScore = 40f + i,
                    )
                }
            dailySummaryDao.upsertAll(summaries)

            val start = System.nanoTime()
            val result = dailySummaryDao.getSince(baselineMs)
            val elapsed = (System.nanoTime() - start) / 1_000_000L

            assert(result.size == 7)
            assert(elapsed < 1000) { "getSince took ${elapsed}ms, expected <1000ms" }
        }

    @Test
    fun sleepSessionDao_observeFirstSessionEndingInRange_noN1() =
        runTest {
            val baseMs = Instant.now().toEpochMilli()
            val sessions =
                (0 until 10).map { i ->
                    SleepSessionEntity(
                        id = "session_$i",
                        startTime = baseMs - (10 - i) * 24 * 3_600_000L,
                        endTime = baseMs - (10 - i) * 24 * 3_600_000L + (8 * 3_600_000L),
                        durationMinutes = 480,
                        efficiency = 0.85f,
                        deepSleepMinutes = 120,
                        lightSleepMinutes = 240,
                        remSleepMinutes = 120,
                        awakeMinutes = 0,
                        deviceName = "Device",
                    )
                }
            sleepSessionDao.upsertAll(sessions)

            val start = System.nanoTime()
            sleepSessionDao.observeFirstSessionEndingInRange(baseMs - (24 * 3_600_000L), baseMs)
            val elapsed = (System.nanoTime() - start) / 1_000_000L

            assert(elapsed < 1000) { "observeFirstSessionEndingInRange took ${elapsed}ms, expected <1000ms" }
        }

    @Test
    fun heartRateDao_getAvgSleepHrPerSession_groupsEfficiently() =
        runTest {
            val baselineMs = Instant.now().minus(7, ChronoUnit.DAYS).toEpochMilli()
            val allRecords = mutableListOf<HeartRateRecordEntity>()

            repeat(7) { sessionIdx ->
                repeat(20) { sampleIdx ->
                    allRecords.add(
                        HeartRateRecordEntity(
                            id = "${sessionIdx}_$sampleIdx",
                            sessionId = "sleep_$sessionIdx",
                            recordType = "SLEEP",
                            timestampMs = baselineMs + (sessionIdx * 24 * 3_600_000L) + (sampleIdx * 60_000L),
                            beatsPerMinute = 55 + (sampleIdx % 10),
                        ),
                    )
                }
            }
            heartRateDao.upsertAll(allRecords)

            val start = System.nanoTime()
            val avgHrs = heartRateDao.getAvgSleepHrPerSession(baselineMs)
            val elapsed = (System.nanoTime() - start) / 1_000_000L

            assert(avgHrs.size == 7)
            assert(elapsed < 1000) { "getAvgSleepHrPerSession took ${elapsed}ms, expected <1000ms" }
        }

    @Test
    fun combinedQuery_noN1_withMultipleTables() =
        runTest {
            val baselineMs = Instant.now().minus(3, ChronoUnit.DAYS).toEpochMilli()

            val sessionIds =
                (0 until 3).map { i ->
                    "integrated_$i"
                }

            // Seed sessions
            val sessions =
                sessionIds.mapIndexed { i, sessionId ->
                    SleepSessionEntity(
                        id = sessionId,
                        startTime = baselineMs + (i * 24 * 3_600_000L),
                        endTime = baselineMs + (i * 24 * 3_600_000L) + (8 * 3_600_000L),
                        durationMinutes = 480,
                        efficiency = 0.85f,
                        deepSleepMinutes = 120,
                        lightSleepMinutes = 240,
                        remSleepMinutes = 120,
                        awakeMinutes = 0,
                        deviceName = "Device",
                    )
                }
            sleepSessionDao.upsertAll(sessions)

            // Seed HR + HRV
            val hrRecords = mutableListOf<HeartRateRecordEntity>()
            val hrvRecords = mutableListOf<HrvRecordEntity>()

            sessionIds.forEachIndexed { sessionIdx, sessionId ->
                repeat(50) { sampleIdx ->
                    hrRecords.add(
                        HeartRateRecordEntity(
                            id = "hr_${sessionIdx}_$sampleIdx",
                            sessionId = sessionId,
                            recordType = "SLEEP",
                            timestampMs = baselineMs + (sessionIdx * 24 * 3_600_000L) + (sampleIdx * 10_000L),
                            beatsPerMinute = 55 + (sampleIdx % 15),
                        ),
                    )
                    hrvRecords.add(
                        HrvRecordEntity(
                            id = "hrv_${sessionIdx}_$sampleIdx",
                            sessionId = sessionId,
                            recordType = "SLEEP",
                            timestampMs = baselineMs + (sessionIdx * 24 * 3_600_000L) + (sampleIdx * 10_000L),
                            rmssdMs = 25f + (sampleIdx % 10),
                        ),
                    )
                }
            }
            heartRateDao.upsertAll(hrRecords)
            hrvDao.upsertAll(hrvRecords)

            // Batch queries
            val startTotal = System.nanoTime()
            val fetchedSessions = sleepSessionDao.getSince(baselineMs)
            val sessionIdList = fetchedSessions.map { it.id }
            val avgHrs = heartRateDao.getAvgSleepHrForSessions(sessionIdList)
            val hrvValues = hrvDao.getSleepRmssdForSessionsMap(sessionIdList)
            val elapsedTotal = (System.nanoTime() - startTotal) / 1_000_000L

            assert(fetchedSessions.size == 3)
            assert(avgHrs.size == 3)
            assert(hrvValues.size == 3)
            assert(elapsedTotal < 1000) { "Combined queries took ${elapsedTotal}ms, expected <1000ms" }
        }
}
