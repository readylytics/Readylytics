package app.readylytics.health.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.data.local.dao.*
import app.readylytics.health.data.local.entity.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetentionCleanupTest {
    private lateinit var database: HealthDatabase
    private lateinit var sleepDao: SleepSessionDao
    private lateinit var sleepStageDao: SleepStageDao
    private lateinit var heartRateDao: HeartRateDao
    private lateinit var hrvDao: HrvDao
    private lateinit var workoutDao: WorkoutDao
    private lateinit var dailySummaryDao: DailySummaryDao
    private lateinit var weightDao: WeightRecordDao
    private lateinit var bodyFatDao: BodyFatRecordDao
    private lateinit var bloodPressureDao: BloodPressureRecordDao
    private lateinit var oxygenSaturationDao: OxygenSaturationRecordDao
    private lateinit var retentionCleanup: RetentionCleanup

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        sleepDao = database.sleepSessionDao()
        sleepStageDao = database.sleepStageDao()
        heartRateDao = database.heartRateDao()
        hrvDao = database.hrvDao()
        workoutDao = database.workoutDao()
        dailySummaryDao = database.dailySummaryDao()
        weightDao = database.weightRecordDao()
        bodyFatDao = database.bodyFatRecordDao()
        bloodPressureDao = database.bloodPressureRecordDao()
        oxygenSaturationDao = database.oxygenSaturationRecordDao()

        val transactionRunner = RoomTransactionRunner(database)
        retentionCleanup =
            RetentionCleanup(
                transactionRunner = transactionRunner,
                sleepDao = sleepDao,
                heartRateDao = heartRateDao,
                hrvDao = hrvDao,
                workoutDao = workoutDao,
                dailySummaryDao = dailySummaryDao,
                weightDao = weightDao,
                bodyFatDao = bodyFatDao,
                bloodPressureDao = bloodPressureDao,
                oxygenSaturationDao = oxygenSaturationDao,
            )
    }

    @After
    fun cleanup() {
        database.close()
    }

    @Test
    fun testRetentionCleanup() =
        runTest {
            val cutoffMs = 1000000L

            // 1. Sleep sessions & stages
            sleepDao.upsertAll(
                listOf(
                    SleepSessionEntity(
                        id = "old_sleep",
                        startTime = cutoffMs - 2000,
                        endTime = cutoffMs - 1000,
                        durationMinutes = 60,
                        efficiency = 0.9f,
                        deepSleepMinutes = 20,
                        remSleepMinutes = 20,
                        lightSleepMinutes = 20,
                        awakeMinutes = 0,
                    ),
                    SleepSessionEntity(
                        id = "equal_sleep",
                        startTime = cutoffMs - 1000,
                        endTime = cutoffMs,
                        durationMinutes = 60,
                        efficiency = 0.9f,
                        deepSleepMinutes = 20,
                        remSleepMinutes = 20,
                        lightSleepMinutes = 20,
                        awakeMinutes = 0,
                    ),
                    SleepSessionEntity(
                        id = "new_sleep",
                        startTime = cutoffMs,
                        endTime = cutoffMs + 1000,
                        durationMinutes = 60,
                        efficiency = 0.9f,
                        deepSleepMinutes = 20,
                        remSleepMinutes = 20,
                        lightSleepMinutes = 20,
                        awakeMinutes = 0,
                    ),
                ),
            )
            sleepStageDao.upsertAll(
                listOf(
                    SleepStageEntity(
                        id = 1,
                        sessionId = "old_sleep",
                        stageType = "REM",
                        startTime = cutoffMs - 2000,
                        endTime =
                            cutoffMs - 1500,
                        durationMinutes = 10,
                    ),
                    SleepStageEntity(
                        id = 2,
                        sessionId = "equal_sleep",
                        stageType = "DEEP",
                        startTime = cutoffMs - 1000,
                        endTime =
                            cutoffMs - 500,
                        durationMinutes = 10,
                    ),
                    SleepStageEntity(
                        id = 3,
                        sessionId = "new_sleep",
                        stageType = "LIGHT",
                        startTime = cutoffMs,
                        endTime =
                            cutoffMs + 500,
                        durationMinutes = 10,
                    ),
                ),
            )

            // 2. Heart rate
            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        id = "old_hr",
                        timestampMs = cutoffMs - 1,
                        beatsPerMinute = 70,
                        recordType = "RESTING",
                    ),
                    HeartRateRecordEntity(
                        id = "equal_hr",
                        timestampMs = cutoffMs,
                        beatsPerMinute = 70,
                        recordType = "RESTING",
                    ),
                    HeartRateRecordEntity(
                        id = "new_hr",
                        timestampMs = cutoffMs + 1,
                        beatsPerMinute = 70,
                        recordType = "RESTING",
                    ),
                ),
            )

            // 3. HRV
            hrvDao.upsertAll(
                listOf(
                    HrvRecordEntity(id = "old_hrv", timestampMs = cutoffMs - 1, rmssdMs = 50f, recordType = "RESTING"),
                    HrvRecordEntity(id = "equal_hrv", timestampMs = cutoffMs, rmssdMs = 50f, recordType = "RESTING"),
                    HrvRecordEntity(id = "new_hrv", timestampMs = cutoffMs + 1, rmssdMs = 50f, recordType = "RESTING"),
                ),
            )

            // 4. Workout
            workoutDao.upsertAll(
                listOf(
                    WorkoutRecordEntity(
                        id = "old_workout",
                        startTime = cutoffMs - 1,
                        endTime = cutoffMs,
                        exerciseType = "Run",
                        durationMinutes = 30,
                        zone1Minutes = 10f,
                        zone2Minutes = 10f,
                        zone3Minutes = 10f,
                        zone4Minutes = 0f,
                        zone5Minutes = 0f,
                        trimp = 50f,
                        avgHr = 130f,
                    ),
                    WorkoutRecordEntity(
                        id = "equal_workout",
                        startTime = cutoffMs,
                        endTime = cutoffMs + 1,
                        exerciseType = "Run",
                        durationMinutes = 30,
                        zone1Minutes = 10f,
                        zone2Minutes = 10f,
                        zone3Minutes = 10f,
                        zone4Minutes = 0f,
                        zone5Minutes = 0f,
                        trimp = 50f,
                        avgHr = 130f,
                    ),
                    WorkoutRecordEntity(
                        id = "new_workout",
                        startTime = cutoffMs + 1,
                        endTime = cutoffMs + 2,
                        exerciseType = "Run",
                        durationMinutes = 30,
                        zone1Minutes = 10f,
                        zone2Minutes = 10f,
                        zone3Minutes = 10f,
                        zone4Minutes = 0f,
                        zone5Minutes = 0f,
                        trimp = 50f,
                        avgHr = 130f,
                    ),
                ),
            )

            // 5. Daily Summary
            dailySummaryDao.upsert(DailySummaryEntity(dateMidnightMs = cutoffMs - 1))
            dailySummaryDao.upsert(DailySummaryEntity(dateMidnightMs = cutoffMs))
            dailySummaryDao.upsert(DailySummaryEntity(dateMidnightMs = cutoffMs + 1))

            // 6. Weight
            weightDao.upsertAll(
                listOf(
                    WeightRecordEntity(id = "old_weight", timestampMs = cutoffMs - 1, weightKg = 70f),
                    WeightRecordEntity(id = "equal_weight", timestampMs = cutoffMs, weightKg = 70f),
                    WeightRecordEntity(id = "new_weight", timestampMs = cutoffMs + 1, weightKg = 70f),
                ),
            )

            // 7. Body fat
            bodyFatDao.upsertAll(
                listOf(
                    BodyFatRecordEntity(id = "old_bf", timestampMs = cutoffMs - 1, bodyFatPercent = 15f),
                    BodyFatRecordEntity(id = "equal_bf", timestampMs = cutoffMs, bodyFatPercent = 15f),
                    BodyFatRecordEntity(id = "new_bf", timestampMs = cutoffMs + 1, bodyFatPercent = 15f),
                ),
            )

            // 8. Blood pressure
            bloodPressureDao.upsertAll(
                listOf(
                    BloodPressureRecordEntity(
                        id = "old_bp",
                        timestampMs = cutoffMs - 1,
                        systolicMmHg = 120,
                        diastolicMmHg = 80,
                    ),
                    BloodPressureRecordEntity(
                        id = "equal_bp",
                        timestampMs = cutoffMs,
                        systolicMmHg = 120,
                        diastolicMmHg = 80,
                    ),
                    BloodPressureRecordEntity(
                        id = "new_bp",
                        timestampMs = cutoffMs + 1,
                        systolicMmHg = 120,
                        diastolicMmHg = 80,
                    ),
                ),
            )

            // 9. Oxygen saturation
            oxygenSaturationDao.upsertAll(
                listOf(
                    OxygenSaturationRecordEntity(id = "old_spo2", timestampMs = cutoffMs - 1, percentage = 98f),
                    OxygenSaturationRecordEntity(id = "equal_spo2", timestampMs = cutoffMs, percentage = 98f),
                    OxygenSaturationRecordEntity(id = "new_spo2", timestampMs = cutoffMs + 1, percentage = 98f),
                ),
            )

            // Execute cleanup
            retentionCleanup.deleteBefore(cutoffMs)

            // Verify sleep sessions (old deleted, equal and new remain)
            val sleepRemaining = sleepDao.getSince(0)
            assertEquals(listOf("equal_sleep", "new_sleep"), sleepRemaining.map { it.id }.sorted())

            // Verify sleep stages (cascaded delete from sleep sessions)
            val stagesCount =
                database.openHelper.writableDatabase
                    .compileStatement(
                        "SELECT COUNT(*) FROM sleep_stages",
                    ).simpleQueryForLong()
            assertEquals(2L, stagesCount)

            // Verify Heart Rate
            val hrRemaining = heartRateDao.getSince(0)
            assertEquals(listOf("equal_hr", "new_hr"), hrRemaining.map { it.id }.sorted())

            // Verify HRV
            val hrvRemaining = hrvDao.getSince(0)
            assertEquals(listOf("equal_hrv", "new_hrv"), hrvRemaining.map { it.id }.sorted())

            // Verify Workout
            val workoutRemaining = workoutDao.getSince(0)
            assertEquals(listOf("equal_workout", "new_workout"), workoutRemaining.map { it.id }.sorted())

            // Verify Daily Summary
            val summaryRemaining = dailySummaryDao.getSince(0)
            assertEquals(listOf(cutoffMs, cutoffMs + 1), summaryRemaining.map { it.dateMidnightMs }.sorted())

            // Verify Weight
            val weightRemaining = weightDao.getSince(0)
            assertEquals(listOf("equal_weight", "new_weight"), weightRemaining.map { it.id }.sorted())

            // Verify Body Fat
            val bodyFatRemaining = bodyFatDao.getSince(0)
            assertEquals(listOf("equal_bf", "new_bf"), bodyFatRemaining.map { it.id }.sorted())

            // Verify Blood Pressure
            val bloodPressureRemaining = bloodPressureDao.getSince(0)
            assertEquals(listOf("equal_bp", "new_bp"), bloodPressureRemaining.map { it.id }.sorted())

            // Verify Oxygen Saturation
            val oxygenSaturationRemaining = oxygenSaturationDao.getSince(0)
            assertEquals(listOf("equal_spo2", "new_spo2"), oxygenSaturationRemaining.map { it.id }.sorted())
        }
}
