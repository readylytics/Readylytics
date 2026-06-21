package app.readylytics.health.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.data.local.dao.BodyFatRecordDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.dao.WeightRecordDao
import app.readylytics.health.data.local.dao.WorkoutDao
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.domain.model.HealthDataType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
class SelectedSourcePrunerImplTest {
    private lateinit var database: HealthDatabase
    private lateinit var sleepDao: SleepSessionDao
    private lateinit var heartRateDao: HeartRateDao
    private lateinit var hrvDao: HrvDao
    private lateinit var workoutDao: WorkoutDao
    private lateinit var weightDao: WeightRecordDao
    private lateinit var bodyFatDao: BodyFatRecordDao
    private lateinit var bloodPressureDao: BloodPressureRecordDao
    private lateinit var oxygenSaturationDao: OxygenSaturationRecordDao
    private lateinit var pruner: SelectedSourcePrunerImpl

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
        weightDao = database.weightRecordDao()
        bodyFatDao = database.bodyFatRecordDao()
        bloodPressureDao = database.bloodPressureRecordDao()
        oxygenSaturationDao = database.oxygenSaturationRecordDao()

        val transactionRunner = RoomTransactionRunner(database)

        pruner =
            SelectedSourcePrunerImpl(
                transactionRunner = transactionRunner,
                sleepSessionDao = sleepDao,
                heartRateDao = heartRateDao,
                hrvDao = hrvDao,
                workoutDao = workoutDao,
                weightRecordDao = weightDao,
                bodyFatRecordDao = bodyFatDao,
                bloodPressureRecordDao = bloodPressureDao,
                oxygenSaturationRecordDao = oxygenSaturationDao,
            )
    }

    @After
    fun cleanup() {
        database.close()
    }

    @Test
    fun pruneDeletesNonMatchingDevicesWithinRange() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val date = LocalDate.of(2024, 6, 1)
            val timestamp = date.atStartOfDay(zoneId).toInstant().toEpochMilli()

            // Seed sleep sessions
            sleepDao.upsertAll(
                listOf(
                    SleepSessionEntity(
                        id = "sleep_a",
                        startTime = timestamp,
                        endTime = timestamp + 3600000,
                        durationMinutes = 60,
                        efficiency = 0.8f,
                        deepSleepMinutes = 20,
                        remSleepMinutes = 20,
                        lightSleepMinutes = 20,
                        awakeMinutes = 0,
                        deviceName = "Device A",
                    ),
                    SleepSessionEntity(
                        id = "sleep_b",
                        startTime = timestamp,
                        endTime = timestamp + 3600000,
                        durationMinutes = 60,
                        efficiency = 0.8f,
                        deepSleepMinutes = 20,
                        remSleepMinutes = 20,
                        lightSleepMinutes = 20,
                        awakeMinutes = 0,
                        deviceName = "Device B",
                    ),
                ),
            )

            // Seed heart rate records
            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        id = "hr_a",
                        timestampMs = timestamp,
                        beatsPerMinute = 70,
                        recordType = "RESTING",
                        deviceName = "Device A",
                    ),
                    HeartRateRecordEntity(
                        id = "hr_b",
                        timestampMs = timestamp,
                        beatsPerMinute = 70,
                        recordType = "RESTING",
                        deviceName = "Device B",
                    ),
                ),
            )

            val selections =
                mapOf(
                    HealthDataType.SLEEP to "Device B",
                    HealthDataType.HEART_RATE to "Device B",
                )

            pruner.prune(date, date, selections, zoneId)

            val remainingSleep = sleepDao.getSince(0)
            assertEquals(1, remainingSleep.size)
            assertEquals("sleep_b", remainingSleep[0].id)

            val remainingHr = heartRateDao.getByTimeRange(0, timestamp + 10000000)
            assertEquals(1, remainingHr.size)
            assertEquals("hr_b", remainingHr[0].id)
        }

    @Test
    fun pruneKeepsAllDevicesWhenSelectionIsNull() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val date = LocalDate.of(2024, 6, 1)
            val timestamp = date.atStartOfDay(zoneId).toInstant().toEpochMilli()

            sleepDao.upsertAll(
                listOf(
                    SleepSessionEntity(
                        id = "sleep_a",
                        startTime = timestamp,
                        endTime = timestamp + 3600000,
                        durationMinutes = 60,
                        efficiency = 0.8f,
                        deepSleepMinutes = 20,
                        remSleepMinutes = 20,
                        lightSleepMinutes = 20,
                        awakeMinutes = 0,
                        deviceName = "Device A",
                    ),
                    SleepSessionEntity(
                        id = "sleep_b",
                        startTime = timestamp,
                        endTime = timestamp + 3600000,
                        durationMinutes = 60,
                        efficiency = 0.8f,
                        deepSleepMinutes = 20,
                        remSleepMinutes = 20,
                        lightSleepMinutes = 20,
                        awakeMinutes = 0,
                        deviceName = "Device B",
                    ),
                ),
            )

            val selections =
                mapOf(
                    HealthDataType.SLEEP to null,
                )

            pruner.prune(date, date, selections, zoneId)

            val remainingSleep = sleepDao.getSince(0)
            assertEquals(2, remainingSleep.size)
        }

    @Test
    fun pruneUsesScoringZoneForRangeBoundaries() =
        runTest {
            val originalTimeZone = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            try {
                val scoringZone = ZoneId.of("Pacific/Kiritimati")
                val date = LocalDate.of(2024, 6, 1)
                val timestamp =
                    date
                        .atStartOfDay(scoringZone)
                        .plusHours(1)
                        .toInstant()
                        .toEpochMilli()
                heartRateDao.upsertAll(
                    listOf(
                        HeartRateRecordEntity(
                            id = "scoring-zone-record",
                            timestampMs = timestamp,
                            beatsPerMinute = 70,
                            recordType = "RESTING",
                            deviceName = "Device A",
                        ),
                    ),
                )

                pruner.prune(
                    start = date,
                    endInclusive = date,
                    selections = mapOf(HealthDataType.HEART_RATE to "Device B"),
                    zoneId = scoringZone,
                )

                assertEquals(emptyList<HeartRateRecordEntity>(), heartRateDao.getByTimeRange(0, Long.MAX_VALUE))
            } finally {
                TimeZone.setDefault(originalTimeZone)
            }
        }
}
