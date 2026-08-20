package app.readylytics.health.core.database.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.StepRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.StepRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WeightRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeysetPaginationTest {
    private lateinit var db: HealthDatabase
    private lateinit var hrDao: HeartRateDao
    private lateinit var hrvDao: HrvDao
    private lateinit var workoutDao: WorkoutDao
    private lateinit var stepDao: StepRecordDao
    private lateinit var weightDao: WeightRecordDao
    private lateinit var bodyFatDao: BodyFatRecordDao
    private lateinit var bloodPressureDao: BloodPressureRecordDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        hrDao = db.heartRateDao()
        hrvDao = db.hrvDao()
        workoutDao = db.workoutDao()
        stepDao = db.stepRecordDao()
        weightDao = db.weightRecordDao()
        bodyFatDao = db.bodyFatRecordDao()
        bloodPressureDao = db.bloodPressureRecordDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedSourceRecordParents(vararg refs: Long) {
        db.sourceRecordDao().insertAll(
            refs.map { ref ->
                HealthSourceRecordEntity(
                    id = ref,
                    sourceRecordId = "seed-$ref",
                    recordType = "HEART_RATE",
                    createdAtMs = 0L,
                )
            },
        )
    }

    @Test
    fun `heartRate pageAfter returns all rows when 3 share a timestamp`() =
        runTest {
            seedSourceRecordParents(10L, 20L, 30L)
            val records =
                listOf(
                    hrEntity(ref = 10, ts = 5000),
                    hrEntity(ref = 20, ts = 5000),
                    hrEntity(ref = 30, ts = 5000),
                )
            records.forEach {
                hrDao.conflictTargetedUpsert(
                    it.sourceRecordRef,
                    it.timestampMs,
                    it.beatsPerMinute,
                    it.recordType,
                    it.sessionId,
                    it.deviceName,
                )
            }

            val page1 = hrDao.pageAfter(0, Long.MIN_VALUE, Long.MIN_VALUE, 2)
            assertEquals(2, page1.size)
            assertEquals(10L, page1[0].sourceRecordRef)
            assertEquals(20L, page1[1].sourceRecordRef)

            val page2 = hrDao.pageAfter(0, page1.last().timestampMs, page1.last().sourceRecordRef, 2)
            assertEquals(1, page2.size)
            assertEquals(30L, page2[0].sourceRecordRef)

            val page3 = hrDao.pageAfter(0, page2.last().timestampMs, page2.last().sourceRecordRef, 2)
            assertEquals(0, page3.size)

            val all = page1 + page2
            assertEquals(3, all.map { it.sourceRecordRef }.distinct().size)
        }

    @Test
    fun `workout pageAfter returns all rows when 3 share a startTime`() =
        runTest {
            workoutDao.upsertAll(
                listOf(
                    workoutEntity("wA", startTime = 1000L),
                    workoutEntity("wB", startTime = 1000L),
                    workoutEntity("wC", startTime = 1000L),
                ),
            )

            val page1 = workoutDao.pageAfter(0, Long.MIN_VALUE, "", 2)
            assertEquals(2, page1.size)
            assertEquals("wA", page1[0].id)
            assertEquals("wB", page1[1].id)

            val page2 = workoutDao.pageAfter(0, page1.last().startTime, page1.last().id, 2)
            assertEquals(1, page2.size)
            assertEquals("wC", page2[0].id)

            assertEquals(3, (page1 + page2).map { it.id }.distinct().size)
        }

    @Test
    fun `step pageAfter returns complete ordered results across pages`() =
        runTest {
            stepDao.upsertAll(
                (1..7).map {
                    StepRecordEntity("s$it", startTime = it * 1000L, endTime = it * 1000L + 500, count = 100)
                },
            )

            val allPaged = mutableListOf<StepRecordEntity>()
            var afterTs = Long.MIN_VALUE
            var afterId = ""
            while (true) {
                val page = stepDao.pageAfter(0, afterTs, afterId, 3)
                if (page.isEmpty()) break
                allPaged.addAll(page)
                afterTs = page.last().startTime
                afterId = page.last().id
            }
            assertEquals(7, allPaged.size)
            assertEquals((1..7).map { "s$it" }, allPaged.map { it.id })
        }

    @Test
    fun testHeartRateKeysetPagination() =
        runTest {
            seedSourceRecordParents(1L, 2L, 3L)
            val records =
                listOf(
                    HeartRateRecordEntity(
                        sourceRecordRef = 1L,
                        timestampMs = 1000L,
                        beatsPerMinute = 60,
                        recordType = "RESTING",
                    ),
                    HeartRateRecordEntity(
                        sourceRecordRef = 2L,
                        timestampMs = 1000L,
                        beatsPerMinute = 65,
                        recordType = "RESTING",
                    ),
                    HeartRateRecordEntity(
                        sourceRecordRef = 3L,
                        timestampMs = 2000L,
                        beatsPerMinute = 70,
                        recordType = "RESTING",
                    ),
                )
            hrDao.upsertAll(records)

            val page1 =
                hrDao.getKeysetPage(
                    startMs = 500L,
                    endMs = 2500L,
                    lastTimestampMs = 0L,
                    lastSourceRecordRef = 0L,
                    limit = 2,
                )
            assertEquals(2, page1.size)
            assertEquals(1L, page1[0].sourceRecordRef)
            assertEquals(2L, page1[1].sourceRecordRef)

            val page2 =
                hrDao.getKeysetPage(
                    startMs = 500L,
                    endMs = 2500L,
                    lastTimestampMs = page1.last().timestampMs,
                    lastSourceRecordRef = page1.last().sourceRecordRef,
                    limit = 2,
                )
            assertEquals(1, page2.size)
            assertEquals(3L, page2[0].sourceRecordRef)
        }

    @Test
    fun testHrvKeysetPagination() =
        runTest {
            seedSourceRecordParents(1L, 2L, 3L)
            val records =
                listOf(
                    HrvRecordEntity(sourceRecordRef = 1L, timestampMs = 1000L, rmssdMs = 45f, recordType = "RESTING"),
                    HrvRecordEntity(sourceRecordRef = 2L, timestampMs = 1000L, rmssdMs = 50f, recordType = "RESTING"),
                    HrvRecordEntity(sourceRecordRef = 3L, timestampMs = 2000L, rmssdMs = 55f, recordType = "RESTING"),
                )
            hrvDao.upsertAll(records)

            val page1 =
                hrvDao.getKeysetPage(
                    startMs = 500L,
                    endMs = 2500L,
                    lastTimestampMs = 0L,
                    lastSourceRecordRef = 0L,
                    limit = 2,
                )
            assertEquals(2, page1.size)
            assertEquals(1L, page1[0].sourceRecordRef)
            assertEquals(2L, page1[1].sourceRecordRef)

            val page2 =
                hrvDao.getKeysetPage(
                    startMs = 500L,
                    endMs = 2500L,
                    lastTimestampMs = page1.last().timestampMs,
                    lastSourceRecordRef = page1.last().sourceRecordRef,
                    limit = 2,
                )
            assertEquals(1, page2.size)
            assertEquals(3L, page2[0].sourceRecordRef)
        }

    @Test
    fun `workout pageAfterInRange returns newest-first within half-open window`() =
        runTest {
            val records = (1..5).map { workoutEntity("w$it", startTime = it * 1000L) }
            workoutDao.upsertAll(records)

            // [2000, 5000): records 2, 3, 4 -> DESC: w4, w3, w2
            val page1 = workoutDao.pageAfterInRange(2000L, 5000L, Long.MAX_VALUE, "\uFFFF", 2)
            assertEquals(2, page1.size)
            assertEquals("w4", page1[0].id)
            assertEquals("w3", page1[1].id)

            val page2 = workoutDao.pageAfterInRange(2000L, 5000L, page1.last().startTime, page1.last().id, 2)
            assertEquals(1, page2.size)
            assertEquals("w2", page2[0].id)

            assertEquals(3, (page1 + page2).map { it.id }.distinct().size)
        }

    @Test
    fun `weight pageAfterByTimeRange returns newest-first within half-open window`() =
        runTest {
            val records = (1..5).map { WeightRecordEntity("wt$it", timestampMs = it * 1000L, weightKg = 70f + it) }
            weightDao.upsertAll(records)

            // [2000, 5000): records 2, 3, 4 -> DESC: wt4, wt3, wt2
            val page1 = weightDao.pageAfterByTimeRange(2000L, 5000L, Long.MAX_VALUE, "\uFFFF", 2)
            assertEquals(2, page1.size)
            assertEquals("wt4", page1[0].id)
            assertEquals("wt3", page1[1].id)

            val page2 = weightDao.pageAfterByTimeRange(2000L, 5000L, page1.last().timestampMs, page1.last().id, 2)
            assertEquals(1, page2.size)
            assertEquals("wt2", page2[0].id)

            assertEquals(3, (page1 + page2).map { it.id }.distinct().size)
        }

    @Test
    fun `bodyFat pageAfterByTimeRange returns newest-first within half-open window`() =
        runTest {
            val records =
                (1..5).map {
                    BodyFatRecordEntity("bf$it", timestampMs = it * 1000L, bodyFatPercent = 15f + it)
                }
            bodyFatDao.upsertAll(records)

            // [2000, 5000): records 2, 3, 4 -> DESC: bf4, bf3, bf2
            val page1 = bodyFatDao.pageAfterByTimeRange(2000L, 5000L, Long.MAX_VALUE, "\uFFFF", 2)
            assertEquals(2, page1.size)
            assertEquals("bf4", page1[0].id)
            assertEquals("bf3", page1[1].id)

            val page2 = bodyFatDao.pageAfterByTimeRange(2000L, 5000L, page1.last().timestampMs, page1.last().id, 2)
            assertEquals(1, page2.size)
            assertEquals("bf2", page2[0].id)

            assertEquals(3, (page1 + page2).map { it.id }.distinct().size)
        }

    @Test
    fun `bloodPressure pageAfterByTimeRange returns newest-first within half-open window`() =
        runTest {
            val records =
                (1..5).map {
                    BloodPressureRecordEntity(
                        "bp$it",
                        timestampMs = it * 1000L,
                        systolicMmHg = 120 + it,
                        diastolicMmHg = 80 + it,
                    )
                }
            bloodPressureDao.upsertAll(records)

            // [2000, 5000): records 2, 3, 4 -> DESC: bp4, bp3, bp2
            val page1 = bloodPressureDao.pageAfterByTimeRange(2000L, 5000L, Long.MAX_VALUE, "\uFFFF", 2)
            assertEquals(2, page1.size)
            assertEquals("bp4", page1[0].id)
            assertEquals("bp3", page1[1].id)

            val page2 =
                bloodPressureDao.pageAfterByTimeRange(
                    2000L,
                    5000L,
                    page1.last().timestampMs,
                    page1.last().id,
                    2,
                )
            assertEquals(1, page2.size)
            assertEquals("bp2", page2[0].id)

            assertEquals(3, (page1 + page2).map { it.id }.distinct().size)
        }

    private fun hrEntity(
        ref: Long,
        ts: Long,
    ) = HeartRateRecordEntity(
        sourceRecordRef = ref,
        timestampMs = ts,
        beatsPerMinute = 72,
        recordType = "RESTING",
        sessionId = null,
        deviceName = null,
    )

    private fun workoutEntity(
        id: String,
        startTime: Long,
    ) = WorkoutRecordEntity(
        id = id,
        startTime = startTime,
        endTime = startTime + 60_000L,
        exerciseType = "Running",
        durationMinutes = 1,
        zone1Minutes = 0f,
        zone2Minutes = 0f,
        zone3Minutes = 0f,
        zone4Minutes = 0f,
        zone5Minutes = 0f,
        trimp = 1f,
        avgHr = 120f,
    )
}

