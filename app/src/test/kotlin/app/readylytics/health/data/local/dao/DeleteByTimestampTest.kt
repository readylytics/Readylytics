package app.readylytics.health.data.local.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.data.local.entity.DailySummaryEntity
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.HrvRecordEntity
import app.readylytics.health.data.local.entity.OxygenSaturationRecordEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.data.local.entity.WeightRecordEntity
import app.readylytics.health.data.local.entity.WorkoutRecordEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class DeleteByTimestampTest {
    private lateinit var database: HealthDatabase
    private lateinit var sleepDao: SleepSessionDao
    private lateinit var heartRateDao: HeartRateDao
    private lateinit var hrvDao: HrvDao
    private lateinit var workoutDao: WorkoutDao
    private lateinit var dailySummaryDao: DailySummaryDao
    private lateinit var weightDao: WeightRecordDao
    private lateinit var bodyFatDao: BodyFatRecordDao
    private lateinit var bloodPressureDao: BloodPressureRecordDao
    private lateinit var oxygenSaturationDao: OxygenSaturationRecordDao

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        sleepDao = database.sleepSessionDao()
        heartRateDao = database.heartRateDao()
        hrvDao = database.hrvDao()
        workoutDao = database.workoutDao()
        dailySummaryDao = database.dailySummaryDao()
        weightDao = database.weightRecordDao()
        bodyFatDao = database.bodyFatRecordDao()
        bloodPressureDao = database.bloodPressureRecordDao()
        oxygenSaturationDao = database.oxygenSaturationRecordDao()
    }

    @After
    fun cleanup() {
        database.close()
    }

    @Test
    fun `sleep session delete before timestamp only deletes old records`() =
        runTest {
            val now = System.currentTimeMillis()
            val oldTime = now - (60L * 24 * 60 * 60 * 1000) // 60 days ago
            val newTime = now - (10L * 24 * 60 * 60 * 1000) // 10 days ago

            sleepDao.upsertAll(
                listOf(
                    SleepSessionEntity(
                        id = "old",
                        startTime = oldTime,
                        endTime = oldTime + 3600000,
                        durationMinutes = 60,
                        efficiency = 0.85f,
                        deepSleepMinutes = 20,
                        remSleepMinutes = 20,
                        lightSleepMinutes = 20,
                        awakeMinutes = 0,
                    ),
                    SleepSessionEntity(
                        id = "new",
                        startTime = newTime,
                        endTime = newTime + 3600000,
                        durationMinutes = 60,
                        efficiency = 0.85f,
                        deepSleepMinutes = 20,
                        remSleepMinutes = 20,
                        lightSleepMinutes = 20,
                        awakeMinutes = 0,
                    ),
                ),
            )

            val cutoffTime = now - (30L * 24 * 60 * 60 * 1000) // 30 days ago
            sleepDao.deleteBeforeTimestamp(cutoffTime)

            val remaining = sleepDao.getSince(0)
            assertEquals(1, remaining.size)
            assertEquals("new", remaining[0].id)
        }

    @Test
    fun `heart rate delete before timestamp only deletes old records`() =
        runTest {
            val now = System.currentTimeMillis()
            val oldTime = now - (60L * 24 * 60 * 60 * 1000)
            val newTime = now - (10L * 24 * 60 * 60 * 1000)

            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        id = "old",
                        timestampMs = oldTime,
                        beatsPerMinute = 75,
                        recordType = "SLEEP",
                        sessionId = "session1",
                    ),
                    HeartRateRecordEntity(
                        id = "new",
                        timestampMs = newTime,
                        beatsPerMinute = 80,
                        recordType = "SLEEP",
                        sessionId = "session2",
                    ),
                ),
            )

            val cutoffTime = now - (30L * 24 * 60 * 60 * 1000)
            heartRateDao.deleteBeforeTimestamp(cutoffTime)

            val remaining = heartRateDao.getByTimeRange(0, now + 1000000)
            assertEquals(1, remaining.size)
            assertEquals("new", remaining[0].id)
        }

    @Test
    fun `hrv delete before timestamp only deletes old records`() =
        runTest {
            val now = System.currentTimeMillis()
            val oldTime = now - (60L * 24 * 60 * 60 * 1000)
            val newTime = now - (10L * 24 * 60 * 60 * 1000)

            hrvDao.upsertAll(
                listOf(
                    HrvRecordEntity(
                        id = "old",
                        timestampMs = oldTime,
                        rmssdMs = 50f,
                        recordType = "SLEEP",
                        sessionId = "session1",
                    ),
                    HrvRecordEntity(
                        id = "new",
                        timestampMs = newTime,
                        rmssdMs = 45f,
                        recordType = "SLEEP",
                        sessionId = "session2",
                    ),
                ),
            )

            val cutoffTime = now - (30L * 24 * 60 * 60 * 1000)
            hrvDao.deleteBeforeTimestamp(cutoffTime)

            val remaining = hrvDao.observeSince(0).first()
            assertEquals(1, remaining.size)
            assertEquals("new", remaining[0].id)
        }

    @Test
    fun `workout delete before timestamp only deletes old records`() =
        runTest {
            val now = System.currentTimeMillis()
            val oldTime = now - (60L * 24 * 60 * 60 * 1000)
            val newTime = now - (10L * 24 * 60 * 60 * 1000)

            workoutDao.upsertAll(
                listOf(
                    WorkoutRecordEntity(
                        id = "old",
                        startTime = oldTime,
                        endTime = oldTime + 3600000,
                        exerciseType = "Running",
                        durationMinutes = 60,
                        zone1Minutes = 10f,
                        zone2Minutes = 10f,
                        zone3Minutes = 20f,
                        zone4Minutes = 10f,
                        zone5Minutes = 10f,
                        trimp = 100f,
                        avgHr = 150f,
                    ),
                    WorkoutRecordEntity(
                        id = "new",
                        startTime = newTime,
                        endTime = newTime + 3600000,
                        exerciseType = "Running",
                        durationMinutes = 60,
                        zone1Minutes = 10f,
                        zone2Minutes = 10f,
                        zone3Minutes = 20f,
                        zone4Minutes = 10f,
                        zone5Minutes = 10f,
                        trimp = 100f,
                        avgHr = 150f,
                    ),
                ),
            )

            val cutoffTime = now - (30L * 24 * 60 * 60 * 1000)
            workoutDao.deleteBeforeTimestamp(cutoffTime)

            val remaining = workoutDao.observeSince(0).first()
            assertEquals(1, remaining.size)
            assertEquals("new", remaining[0].id)
        }

    @Test
    fun `daily summary delete before timestamp only deletes old records`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(zoneId)
            val oldDateMs =
                today
                    .minusDays(60)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val newDateMs =
                today
                    .minusDays(10)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()

            dailySummaryDao.upsert(DailySummaryEntity(dateMidnightMs = oldDateMs))
            dailySummaryDao.upsert(DailySummaryEntity(dateMidnightMs = newDateMs))

            val cutoffTime =
                today
                    .minusDays(30)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            dailySummaryDao.deleteBeforeTimestamp(cutoffTime)

            val remaining = dailySummaryDao.getSince(0)
            assertEquals(1, remaining.size)
            assertEquals(newDateMs, remaining[0].dateMidnightMs)
        }

    @Test
    fun `weight delete before timestamp only deletes old records`() =
        runTest {
            val now = System.currentTimeMillis()
            val oldTime = now - (60L * 24 * 60 * 60 * 1000)
            val newTime = now - (10L * 24 * 60 * 60 * 1000)

            weightDao.upsertAll(
                listOf(
                    WeightRecordEntity(id = "old", timestampMs = oldTime, weightKg = 70f),
                    WeightRecordEntity(id = "new", timestampMs = newTime, weightKg = 72f),
                ),
            )

            val cutoffTime = now - (30L * 24 * 60 * 60 * 1000)
            weightDao.deleteBeforeTimestamp(cutoffTime)

            val remaining = weightDao.getSince(0)
            assertEquals(1, remaining.size)
            assertEquals("new", remaining[0].id)
        }

    @Test
    fun `body fat delete before timestamp only deletes old records`() =
        runTest {
            val now = System.currentTimeMillis()
            val oldTime = now - (60L * 24 * 60 * 60 * 1000)
            val newTime = now - (10L * 24 * 60 * 60 * 1000)

            bodyFatDao.upsertAll(
                listOf(
                    BodyFatRecordEntity(id = "old", timestampMs = oldTime, bodyFatPercent = 15f),
                    BodyFatRecordEntity(id = "new", timestampMs = newTime, bodyFatPercent = 14f),
                ),
            )

            val cutoffTime = now - (30L * 24 * 60 * 60 * 1000)
            bodyFatDao.deleteBeforeTimestamp(cutoffTime)

            val remaining = bodyFatDao.getSince(0)
            assertEquals(1, remaining.size)
            assertEquals("new", remaining[0].id)
        }

    @Test
    fun `blood pressure delete before timestamp only deletes old records`() =
        runTest {
            val now = System.currentTimeMillis()
            val oldTime = now - (60L * 24 * 60 * 60 * 1000)
            val newTime = now - (10L * 24 * 60 * 60 * 1000)

            bloodPressureDao.upsertAll(
                listOf(
                    BloodPressureRecordEntity(
                        id = "old",
                        timestampMs = oldTime,
                        systolicMmHg = 120,
                        diastolicMmHg = 80,
                    ),
                    BloodPressureRecordEntity(
                        id = "new",
                        timestampMs = newTime,
                        systolicMmHg = 118,
                        diastolicMmHg = 78,
                    ),
                ),
            )

            val cutoffTime = now - (30L * 24 * 60 * 60 * 1000)
            bloodPressureDao.deleteBeforeTimestamp(cutoffTime)

            val remaining = bloodPressureDao.getSince(0)
            assertEquals(1, remaining.size)
            assertEquals("new", remaining[0].id)
        }

    @Test
    fun `oxygen saturation delete before timestamp only deletes old records`() =
        runTest {
            val now = System.currentTimeMillis()
            val oldTime = now - (60L * 24 * 60 * 60 * 1000)
            val newTime = now - (10L * 24 * 60 * 60 * 1000)

            oxygenSaturationDao.upsertAll(
                listOf(
                    OxygenSaturationRecordEntity(id = "old", timestampMs = oldTime, percentage = 98f),
                    OxygenSaturationRecordEntity(id = "new", timestampMs = newTime, percentage = 99f),
                ),
            )

            val cutoffTime = now - (30L * 24 * 60 * 60 * 1000)
            oxygenSaturationDao.deleteBeforeTimestamp(cutoffTime)

            val remaining = oxygenSaturationDao.getSince(0)
            assertEquals(1, remaining.size)
            assertEquals("new", remaining[0].id)
        }

    @Test
    fun `delete all removes all records`() =
        runTest {
            sleepDao.upsertAll(
                listOf(
                    SleepSessionEntity(
                        id = "s1",
                        startTime = System.currentTimeMillis(),
                        endTime =
                            System.currentTimeMillis() + 3600000,
                        durationMinutes = 60,
                        efficiency = 0.85f,
                        deepSleepMinutes = 20,
                        remSleepMinutes = 20,
                        lightSleepMinutes = 20,
                        awakeMinutes = 0,
                    ),
                    SleepSessionEntity(
                        id = "s2",
                        startTime = System.currentTimeMillis(),
                        endTime =
                            System.currentTimeMillis() + 3600000,
                        durationMinutes = 60,
                        efficiency = 0.85f,
                        deepSleepMinutes = 20,
                        remSleepMinutes = 20,
                        lightSleepMinutes = 20,
                        awakeMinutes = 0,
                    ),
                ),
            )

            sleepDao.deleteAll()
            val remaining = sleepDao.getSince(0)
            assertEquals(0, remaining.size)
        }
}
