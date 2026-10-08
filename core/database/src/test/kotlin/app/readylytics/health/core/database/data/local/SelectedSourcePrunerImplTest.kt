package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyTemperatureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketMaintenanceDao
import app.readylytics.health.core.databaseschema.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.entity.BodyTemperatureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.Vo2MaxRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRoutePointEntity
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    private lateinit var minuteBucketDao: MinuteBucketDao
    private lateinit var minuteBucketMaintenanceDao: MinuteBucketMaintenanceDao
    private lateinit var hrvDao: HrvDao
    private lateinit var workoutDao: WorkoutDao
    private lateinit var weightDao: WeightRecordDao
    private lateinit var bodyFatDao: BodyFatRecordDao
    private lateinit var bloodPressureDao: BloodPressureRecordDao
    private lateinit var oxygenSaturationDao: OxygenSaturationRecordDao
    private lateinit var bodyTemperatureDao: BodyTemperatureRecordDao
    private lateinit var vo2MaxDao: Vo2MaxRecordDao
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
        minuteBucketDao = database.minuteBucketDao()
        minuteBucketMaintenanceDao = database.minuteBucketMaintenanceDao()
        hrvDao = database.hrvDao()
        workoutDao = database.workoutDao()
        weightDao = database.weightRecordDao()
        bodyFatDao = database.bodyFatRecordDao()
        bloodPressureDao = database.bloodPressureRecordDao()
        oxygenSaturationDao = database.oxygenSaturationRecordDao()
        bodyTemperatureDao = database.bodyTemperatureRecordDao()
        vo2MaxDao = database.vo2MaxRecordDao()

        val transactionRunner = RoomTransactionRunner(database)

        pruner =
            SelectedSourcePrunerImpl(
                transactionRunner = transactionRunner,
                vo2MaxRecordDao = vo2MaxDao,
                daos =
                    HealthRecordDaos(
                        sleepSessionDao = sleepDao,
                        sleepStageDao = database.sleepStageDao(),
                        heartRateDao = heartRateDao,
                        hrvDao = hrvDao,
                        workoutDao = workoutDao,
                        workoutRoutePointDao = database.workoutRoutePointDao(),
                        weightRecordDao = weightDao,
                        bodyFatRecordDao = bodyFatDao,
                        bloodPressureRecordDao = bloodPressureDao,
                        oxygenSaturationRecordDao = oxygenSaturationDao,
                        bodyTemperatureRecordDao = bodyTemperatureDao,
                        stepRecordDao = database.stepRecordDao(),
                        sourceRecordDao = database.sourceRecordDao(),
                        minuteBucketMaintenanceDao = minuteBucketMaintenanceDao,
                    ),
            )
    }

    @After
    fun cleanup() {
        database.close()
    }

    private suspend fun seedSourceRecordParents(vararg refs: Long) {
        database.sourceRecordDao().insertAll(
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
    fun pruneDeletesNonMatchingDevicesWithinRange() =
        runTest {
            seedSourceRecordParents(1L, 2L)
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
                        sourceRecordRef = 1L,
                        timestampMs = timestamp,
                        beatsPerMinute = 70,
                        recordType = "RESTING",
                        deviceName = "Device A",
                    ),
                    HeartRateRecordEntity(
                        sourceRecordRef = 2L,
                        timestampMs = timestamp,
                        beatsPerMinute = 70,
                        recordType = "RESTING",
                        deviceName = "Device B",
                    ),
                ),
            )

            // Seed warm-tier minute buckets
            minuteBucketDao.upsertBuckets(
                listOf(
                    HrMinuteBucketEntity(
                        bucketStartMs = timestamp,
                        bucketEndMs = timestamp + 60000,
                        minBpm = 60,
                        maxBpm = 80,
                        avgBpm = 70.0,
                        sampleCount = 10,
                        recordType = "RESTING",
                        sessionId = "",
                        deviceName = "Device A",
                    ),
                    HrMinuteBucketEntity(
                        bucketStartMs = timestamp,
                        bucketEndMs = timestamp + 60000,
                        minBpm = 60,
                        maxBpm = 80,
                        avgBpm = 70.0,
                        sampleCount = 10,
                        recordType = "RESTING",
                        sessionId = "",
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
            assertEquals(2L, remainingHr[0].sourceRecordRef)

            val remainingBuckets = minuteBucketDao.getBucketsForSession("RESTING", "")
            assertEquals(1, remainingBuckets.size)
            assertEquals("Device B", remainingBuckets[0].deviceName)
        }

    @Test
    fun pruneDeletesNonMatchingBodyTemperatureDevicesWithinRange() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val date = LocalDate.of(2024, 6, 1)
            val timestamp = date.atStartOfDay(zoneId).toInstant().toEpochMilli()

            bodyTemperatureDao.upsertAll(
                listOf(
                    BodyTemperatureRecordEntity(
                        id = "bt_a",
                        timestampMs = timestamp,
                        celsius = 36.6f,
                        deviceName = "Device A",
                    ),
                    BodyTemperatureRecordEntity(
                        id = "bt_b",
                        timestampMs = timestamp,
                        celsius = 36.7f,
                        deviceName = "Device B",
                    ),
                ),
            )

            val selections =
                mapOf(
                    HealthDataType.BODY_TEMPERATURE to "Device B",
                )

            pruner.prune(date, date, selections, zoneId)

            val remainingBodyTemperature = bodyTemperatureDao.getByTimeRange(0, timestamp + 10000000)
            assertEquals(1, remainingBodyTemperature.size)
            assertEquals("bt_b", remainingBodyTemperature[0].id)
        }

    @Test
    fun pruneDeletesNonMatchingVo2MaxDevicesWithinRange() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val date = LocalDate.of(2024, 6, 1)
            val timestamp = date.atStartOfDay(zoneId).toInstant().toEpochMilli()

            vo2MaxDao.upsertAll(
                listOf(
                    Vo2MaxRecordEntity(
                        id = "vo2_a",
                        timestampMs = timestamp,
                        vo2Max = 45f,
                        measurementMethod = null,
                        deviceName = "Device A",
                    ),
                    Vo2MaxRecordEntity(
                        id = "vo2_b",
                        timestampMs = timestamp,
                        vo2Max = 50f,
                        measurementMethod = null,
                        deviceName = "Device B",
                    ),
                ),
            )

            val selections = mapOf(HealthDataType.VO2_MAX to "Device B")

            pruner.prune(date, date, selections, zoneId)

            val remaining = vo2MaxDao.getByTimeRange(0, timestamp + 10000000)
            assertEquals(1, remaining.size)
            assertEquals("vo2_b", remaining[0].id)
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
            seedSourceRecordParents(1L)
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
                            sourceRecordRef = 1L,
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

    private fun workoutRecord(
        id: String,
        startTime: Long,
        deviceName: String?,
    ) = WorkoutRecordEntity(
        id = id,
        startTime = startTime,
        endTime = startTime + 3_600_000L,
        exerciseType = "RUNNING",
        durationMinutes = 60,
        zone1Minutes = 0f,
        zone2Minutes = 0f,
        zone3Minutes = 0f,
        zone4Minutes = 0f,
        zone5Minutes = 0f,
        trimp = 10f,
        avgHr = 120f,
        deviceName = deviceName,
    )

    @Test
    fun repairExcludesOnlyOtherDeviceWorkouts() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val date = LocalDate.of(2024, 6, 1)
            val timestamp = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val routeDao = database.workoutRoutePointDao()

            workoutDao.upsertAll(
                listOf(
                    workoutRecord("kept", timestamp, "Watch"),
                    workoutRecord("excluded", timestamp, "Phone"),
                ),
            )
            routeDao.insertAll(
                listOf(
                    WorkoutRoutePointEntity(
                        workoutId = "excluded",
                        latitude = 1.0,
                        longitude = 1.0,
                        altitude = null,
                        timestampMs = timestamp,
                    ),
                ),
            )

            val affected = pruner.pruneExcludedWorkouts(date, date, "Watch", zoneId)

            assertEquals(listOf("kept"), workoutDao.getSince(0).map { it.id })
            assertTrue(routeDao.getRoutePoints("excluded").isEmpty())
            assertEquals(ScoreInvalidation.AffectedRange(date, date), affected)
        }

    /**
     * Fix-round-3: proves [SelectedSourcePrunerImpl.pruneExcludedWorkouts] durably journals a
     * `dirty_ranges` ticket for the deleted page, so a caller that only inspects the live return
     * value is no longer the only record of "these dates need recompute" -- the review finding
     * this change addresses was that the live return value alone is lost on a retry where the
     * prune finds nothing left to delete.
     */
    @Test
    fun pruneExcludedWorkoutsJournalsADurableDirtyTicketWithThePageDelete() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val date = LocalDate.of(2024, 6, 1)
            val timestamp = date.atStartOfDay(zoneId).toInstant().toEpochMilli()

            workoutDao.upsertAll(
                listOf(
                    workoutRecord("kept", timestamp, "Watch"),
                    workoutRecord("excluded", timestamp, "Phone"),
                ),
            )

            val mutationStateDao = database.healthMutationStateDao()
            mutationStateDao.upsert(
                app.readylytics.health.core.databaseschema.data.local.entity.HealthMutationStateEntity(
                    id = 1,
                    sourceGeneration = 1,
                ),
            )
            val dirtyRangeStore = RoomDirtyRangeStore(database.dirtyRangeDao(), mutationStateDao)
            val journalingPruner =
                SelectedSourcePrunerImpl(
                    transactionRunner = RoomTransactionRunner(database),
                    vo2MaxRecordDao = vo2MaxDao,
                    daos =
                        HealthRecordDaos(
                            sleepSessionDao = sleepDao,
                            sleepStageDao = database.sleepStageDao(),
                            heartRateDao = heartRateDao,
                            hrvDao = hrvDao,
                            workoutDao = workoutDao,
                            workoutRoutePointDao = database.workoutRoutePointDao(),
                            weightRecordDao = weightDao,
                            bodyFatRecordDao = bodyFatDao,
                            bloodPressureRecordDao = bloodPressureDao,
                            oxygenSaturationRecordDao = oxygenSaturationDao,
                            bodyTemperatureRecordDao = bodyTemperatureDao,
                            stepRecordDao = database.stepRecordDao(),
                            sourceRecordDao = database.sourceRecordDao(),
                            minuteBucketMaintenanceDao = minuteBucketMaintenanceDao,
                        ),
                    dirtyRangeStore = dirtyRangeStore,
                    healthMutationStateDao = mutationStateDao,
                )

            assertTrue(dirtyRangeStore.pending(10).isEmpty())

            journalingPruner.pruneExcludedWorkouts(date, date, "Watch", zoneId)

            assertEquals(listOf("kept"), workoutDao.getSince(0).map { it.id })
            val pending = dirtyRangeStore.pending(10)
            assertEquals(1, pending.size)
            assertEquals(date, pending.single().nextDay)
            assertTrue(!pending.single().endInclusive.isBefore(date))
        }

    @Test
    fun `pruneExcludedWorkouts is a no-op when nothing is excluded`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val date = LocalDate.of(2024, 6, 1)
            val timestamp = date.atStartOfDay(zoneId).toInstant().toEpochMilli()

            workoutDao.upsertAll(listOf(workoutRecord("kept", timestamp, "Watch")))

            val affected = pruner.pruneExcludedWorkouts(date, date, "Watch", zoneId)

            assertEquals(listOf("kept"), workoutDao.getSince(0).map { it.id })
            assertNull(affected)
        }
}
