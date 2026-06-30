package app.readylytics.health.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.dao.SleepStageDao
import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.data.local.entity.SleepStageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SleepSessionRepositoryImplTest {
    private lateinit var db: HealthDatabase
    private lateinit var dao: SleepSessionDao
    private lateinit var stageDao: SleepStageDao
    private lateinit var repo: SleepSessionRepositoryImpl

    // Synthetic timestamps (ms)
    private val t1Start = 1_000_000L
    private val t1End = 1_028_800_000L
    private val t2Start = 2_000_000L
    private val t2End = 2_028_800_000L
    private val t3Start = 3_000_000L
    private val t3End = 3_028_800_000L

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()

        dao = db.sleepSessionDao()
        stageDao = db.sleepStageDao()
        repo = SleepSessionRepositoryImpl(dao, stageDao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- helpers ----

    private fun session(
        id: String,
        startTime: Long,
        endTime: Long = startTime + 28_800_000L,
        durationMinutes: Int = 480,
        efficiency: Float = 0.85f,
        deepSleepMinutes: Int = 90,
        remSleepMinutes: Int = 100,
        lightSleepMinutes: Int = 200,
        awakeMinutes: Int = 10,
        sleepScore: Float? = null,
        device: String? = null,
    ) = SleepSessionEntity(
        id = id,
        startTime = startTime,
        endTime = endTime,
        durationMinutes = durationMinutes,
        efficiency = efficiency,
        deepSleepMinutes = deepSleepMinutes,
        remSleepMinutes = remSleepMinutes,
        lightSleepMinutes = lightSleepMinutes,
        awakeMinutes = awakeMinutes,
        sleepScore = sleepScore,
        deviceName = device,
    )

    private fun stage(
        sessionId: String,
        stageType: String,
        startTime: Long,
        endTime: Long,
        durationMinutes: Int,
    ) = SleepStageEntity(
        sessionId = sessionId,
        stageType = stageType,
        startTime = startTime,
        endTime = endTime,
        durationMinutes = durationMinutes,
    )

    // ---- empty database ----

    @Test
    fun getSince_emptyDatabase_returnsEmptyList() =
        runTest {
            assertTrue(repo.getSince(t1Start).isEmpty())
        }

    @Test
    fun getLatest_emptyDatabase_returnsNull() =
        runTest {
            assertNull(dao.getLatest())
        }

    @Test
    fun countSince_emptyDatabase_returnsZero() =
        runTest {
            assertEquals(0, dao.countSince(t1Start))
        }

    // ---- insert + read ----

    @Test
    fun getSince_singleSession_returnsThatSession() =
        runTest {
            dao.upsertAll(listOf(session("s1", t1Start)))

            val result = repo.getSince(t1Start)

            assertEquals(1, result.size)
            assertEquals("s1", result[0].id)
        }

    @Test
    fun getSince_multipleSessionsAfterCutoff_returnsAll() =
        runTest {
            dao.upsertAll(
                listOf(
                    session("s1", t1Start),
                    session("s2", t2Start),
                    session("s3", t3Start),
                ),
            )

            val result = repo.getSince(t1Start)

            assertEquals(3, result.size)
        }

    @Test
    fun getSince_sessionBeforeCutoff_excluded() =
        runTest {
            dao.upsertAll(
                listOf(
                    session("old", t1Start),
                    session("recent", t3Start),
                ),
            )

            val result = repo.getSince(t2Start)

            assertEquals(1, result.size)
            assertEquals("recent", result[0].id)
        }

    @Test
    fun getLatest_returnsNewestSession() =
        runTest {
            dao.upsertAll(
                listOf(
                    session("s1", t1Start),
                    session("s2", t2Start),
                    session("s3", t3Start),
                ),
            )

            val result = dao.getLatest()

            assertNotNull(result)
            assertEquals("s3", result!!.id)
        }

    @Test
    fun getPaged_firstPage_returnsLimitedResults() =
        runTest {
            dao.upsertAll(
                listOf(
                    session("s1", t1Start),
                    session("s2", t2Start),
                    session("s3", t3Start),
                ),
            )

            val page = repo.getPaged(fromMs = t1Start, limit = 2, offset = 0)

            assertEquals(2, page.size)
        }

    @Test
    fun getPaged_secondPage_returnsRemainder() =
        runTest {
            dao.upsertAll(
                listOf(
                    session("s1", t1Start),
                    session("s2", t2Start),
                    session("s3", t3Start),
                ),
            )

            val page = repo.getPaged(fromMs = t1Start, limit = 2, offset = 2)

            assertEquals(1, page.size)
            assertEquals("s3", page[0].id)
        }

    // ---- upsert / update ----

    @Test
    fun upsertAll_updatesExistingSession() =
        runTest {
            dao.upsertAll(listOf(session("s1", t1Start, sleepScore = 70f)))
            dao.upsertAll(listOf(session("s1", t1Start, sleepScore = 85f)))

            val result = repo.getSince(t1Start)

            assertEquals(1, result.size)
            assertEquals(85f, result[0].sleepScore)
        }

    @Test
    fun upsertAll_batchInsert_allSessionsPersisted() =
        runTest {
            val batch = (1..5).map { i -> session("s$i", t1Start + i * 1_000_000L) }
            dao.upsertAll(batch)

            assertEquals(5, dao.count())
        }

    // ---- delete ----

    @Test
    fun deleteById_removesOnlyTargetSession() =
        runTest {
            dao.upsertAll(
                listOf(
                    session("s1", t1Start),
                    session("s2", t2Start),
                ),
            )

            dao.deleteById("s1")

            val result = repo.getSince(t1Start)
            assertEquals(1, result.size)
            assertEquals("s2", result[0].id)
        }

    @Test
    fun deleteBeforeTimestamp_removesOldSessions() =
        runTest {
            dao.upsertAll(
                listOf(
                    session(id = "old", startTime = t1Start, endTime = t2Start - 1),
                    session("recent", t3Start),
                ),
            )

            dao.deleteBeforeTimestamp(t2Start)

            assertEquals(1, dao.count())
            assertEquals("recent", dao.getLatest()!!.id)
        }

    @Test
    fun deleteAll_clearsTable() =
        runTest {
            dao.upsertAll((1..3).map { i -> session("s$i", t1Start + i * 1_000_000L) })
            dao.deleteAll()

            assertEquals(0, dao.count())
        }

    // ---- observe (Flow) ----

    @Test
    fun observeSince_emitsCurrentState() =
        runTest {
            dao.upsertAll(listOf(session("s1", t1Start)))

            val result = repo.observeSince(t1Start).first()

            assertEquals(1, result.size)
            assertEquals("s1", result[0].id)
        }

    @Test
    fun observeLatest_emitsLatestSession() =
        runTest {
            dao.upsertAll(
                listOf(
                    session("s1", t1Start),
                    session("s2", t2Start),
                ),
            )

            val result = dao.observeLatest().first()

            assertNotNull(result)
            assertEquals("s2", result!!.id)
        }

    // ---- field values ----

    @Test
    fun storedFieldsMatchInserted() =
        runTest {
            val s =
                session(
                    id = "detailed",
                    startTime = t1Start,
                    endTime = t1End,
                    durationMinutes = 480,
                    efficiency = 0.90f,
                    deepSleepMinutes = 100,
                    remSleepMinutes = 120,
                    lightSleepMinutes = 230,
                    awakeMinutes = 5,
                    sleepScore = 88f,
                    device = "SmartWatch",
                )
            dao.upsertAll(listOf(s))

            val result = repo.getSince(t1Start).first()

            assertEquals("detailed", result.id)
            assertEquals(t1Start, result.startTime)
            assertEquals(t1End, result.endTime)
            assertEquals(480, result.durationMinutes)
            assertEquals(0.90f, result.efficiency)
            assertEquals(100, result.deepSleepMinutes)
            assertEquals(120, result.remSleepMinutes)
            assertEquals(230, result.lightSleepMinutes)
            assertEquals(5, result.awakeMinutes)
            assertEquals(88f, result.sleepScore)
            assertEquals("SmartWatch", result.deviceName)
        }

    // ---- sleep stages cascade ----

    @Test
    fun deleteSession_cascadesStages() =
        runTest {
            dao.upsertAll(listOf(session("s1", t1Start)))
            stageDao.upsertAll(
                listOf(
                    stage("s1", "DEEP", t1Start, t1Start + 3_600_000L, 60),
                    stage("s1", "REM", t1Start + 3_600_000L, t1Start + 7_200_000L, 60),
                ),
            )

            dao.deleteById("s1")

            val stages = stageDao.observeStagesForSession("s1").first()
            assertTrue(stages.isEmpty())
        }

    @Test
    fun countSince_correctCountAfterInsert() =
        runTest {
            dao.upsertAll(
                listOf(
                    session("s1", t1Start),
                    session("s2", t2Start),
                    session("s3", t3Start),
                ),
            )

            assertEquals(3, dao.countSince(t1Start))
            assertEquals(2, dao.countSince(t2Start))
            assertEquals(1, dao.countSince(t3Start))
        }
}
