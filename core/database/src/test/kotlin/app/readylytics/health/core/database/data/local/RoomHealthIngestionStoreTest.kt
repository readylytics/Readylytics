package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.HrvInput
import app.readylytics.health.core.model.domain.sync.SleepSessionInput
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class RoomHealthIngestionStoreTest {
    private lateinit var database: HealthDatabase
    private lateinit var store: RoomHealthIngestionStore

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        store =
            RoomHealthIngestionStore(
                daos =
                    HealthRecordDaos(
                        sleepSessionDao = database.sleepSessionDao(),
                        sleepStageDao = database.sleepStageDao(),
                        heartRateDao = database.heartRateDao(),
                        hrvDao = database.hrvDao(),
                        workoutDao = database.workoutDao(),
                        workoutRoutePointDao = database.workoutRoutePointDao(),
                        weightRecordDao = database.weightRecordDao(),
                        bodyFatRecordDao = database.bodyFatRecordDao(),
                        bloodPressureRecordDao = database.bloodPressureRecordDao(),
                        oxygenSaturationRecordDao = database.oxygenSaturationRecordDao(),
                        bodyTemperatureRecordDao = database.bodyTemperatureRecordDao(),
                        stepRecordDao = database.stepRecordDao(),
                        sourceRecordDao = database.sourceRecordDao(),
                        minuteBucketDao = database.minuteBucketDao(),
                    ),
                dailySummaryDao = database.dailySummaryDao(),
                transactionRunner = RoomTransactionRunner(database),
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `persistHeartRateSamples persists 1500 samples across batches with conflict update and idempotency`() =
        runTest {
            val initialSamples =
                (1..1500).map { index ->
                    HeartRateInput(
                        id = "hc-hr-record_${index}_sub",
                        timestampMs = START_MS + index * 1000L,
                        beatsPerMinute = 60 + (index % 50),
                        recordType = "RESTING",
                        sessionId = null,
                        deviceName = "Watch V1",
                    )
                }

            // 1. Initial persistence of 1,500 samples across 500-row batch boundaries
            store.persistHeartRateSamples(initialSamples)

            assertEquals(1500, database.heartRateDao().count())
            val firstPersisted = database.heartRateDao().getByTimeRange(START_MS, START_MS + 2000L).first()
            assertEquals("RESTING", firstPersisted.recordType)
            assertEquals("Watch V1", firstPersisted.deviceName)

            // 2. Re-persist 1,500 samples with duplicate timestamps / source ref but updated metadata
            val updatedSamples =
                (1..1500).map { index ->
                    HeartRateInput(
                        id = "hc-hr-record_${index}_sub",
                        timestampMs = START_MS + index * 1000L,
                        beatsPerMinute = 60 + (index % 50),
                        recordType = "SLEEP",
                        sessionId = "session-1",
                        deviceName = "Watch V2",
                    )
                }
            store.persistHeartRateSamples(updatedSamples)

            // Row count must remain 1500 (conflict-targeted update, no duplicate key errors)
            assertEquals(1500, database.heartRateDao().count())
            val updatedRecords = database.heartRateDao().getByTimeRange(START_MS, START_MS + 2000_000L)
            assertEquals(1500, updatedRecords.size)
            assertEquals(1500, updatedRecords.count { it.recordType == "SLEEP" })
            assertEquals(1500, updatedRecords.count { it.sessionId == "session-1" })
            assertEquals(1500, updatedRecords.count { it.deviceName == "Watch V2" })

            // 3. Idempotent re-persist with identical samples
            store.persistHeartRateSamples(updatedSamples)
            assertEquals(1500, database.heartRateDao().count())
        }

    @Test
    fun `persistHrvSamples persists 1500 samples across batches with conflict update and idempotency`() =
        runTest {
            val initialSamples =
                (1..1500).map { index ->
                    HrvInput(
                        id = "hc-hrv-record_${index}_sub",
                        timestampMs = START_MS + index * 1000L,
                        rmssdMs = 45f + (index % 20),
                        recordType = "RESTING",
                        sessionId = null,
                        deviceName = "Watch V1",
                    )
                }

            // 1. Initial persistence of 1,500 samples across 500-row batch boundaries
            store.persistHrvSamples(initialSamples)

            assertEquals(1500, database.hrvDao().count())
            val firstPersisted = database.hrvDao().getByTimeRange(START_MS, START_MS + 2000L).first()
            assertEquals("RESTING", firstPersisted.recordType)
            assertEquals("Watch V1", firstPersisted.deviceName)

            // 2. Re-persist 1,500 samples with duplicate timestamps / source ref but updated metadata
            val updatedSamples =
                (1..1500).map { index ->
                    HrvInput(
                        id = "hc-hrv-record_${index}_sub",
                        timestampMs = START_MS + index * 1000L,
                        rmssdMs = 45f + (index % 20),
                        recordType = "SLEEP",
                        sessionId = "session-1",
                        deviceName = "Watch V2",
                    )
                }
            store.persistHrvSamples(updatedSamples)

            // Row count must remain 1500 (conflict-targeted update, no duplicate key errors)
            assertEquals(1500, database.hrvDao().count())
            val updatedRecords = database.hrvDao().getByTimeRange(START_MS, START_MS + 2000_000L)
            assertEquals(1500, updatedRecords.size)
            assertEquals(1500, updatedRecords.count { it.recordType == "SLEEP" })
            assertEquals(1500, updatedRecords.count { it.sessionId == "session-1" })
            assertEquals(1500, updatedRecords.count { it.deviceName == "Watch V2" })

            // 3. Idempotent re-persist with identical samples
            store.persistHrvSamples(updatedSamples)
            assertEquals(1500, database.hrvDao().count())
        }

    @Test
    fun `persist batch with 1500 HR and HRV samples persists all rows idempotently`() =
        runTest {
            val hrSamples =
                (1..1500).map { index ->
                    HeartRateInput(
                        id = "hc-hr-record_${index}_sub",
                        timestampMs = START_MS + index * 1000L,
                        beatsPerMinute = 70,
                        recordType = "RESTING",
                        sessionId = null,
                        deviceName = "Watch",
                    )
                }
            val hrvSamples =
                (1..1500).map { index ->
                    HrvInput(
                        id = "hc-hrv-record_${index}_sub",
                        timestampMs = START_MS + index * 1000L,
                        rmssdMs = 50f,
                        recordType = "RESTING",
                        sessionId = null,
                        deviceName = "Watch",
                    )
                }

            val batch =
                HealthIngestionBatch(
                    sleepSessions = emptyList(),
                    sleepStages = emptyList(),
                    heartRateSamples = hrSamples,
                    hrvSamples = hrvSamples,
                    workouts = emptyList(),
                    weights = emptyList(),
                    bodyFatSamples = emptyList(),
                    bloodPressureSamples = emptyList(),
                    oxygenSaturationSamples = emptyList(),
                    bodyTemperatureSamples = emptyList(),
                    stepRecords = emptyList(),
                )

            store.persist(batch)
            assertEquals(1500, database.heartRateDao().count())
            assertEquals(1500, database.hrvDao().count())

            store.persist(batch)
            assertEquals(1500, database.heartRateDao().count())
            assertEquals(1500, database.hrvDao().count())
        }

    @Test
    fun `affectedDatesForRecord returns the sleep session's date range`() = runTest {
        val dayStartMs = START_MS
        val session = SleepSessionInput(
            id = "hc-sleep-1", startTime = dayStartMs, endTime = dayStartMs + 1 * 3_600_000L,
            durationMinutes = 60, efficiency = 0.9f, deepSleepMinutes = 40, remSleepMinutes = 15,
            lightSleepMinutes = 5, awakeMinutes = 0, sleepScore = null,
            startZoneOffsetSeconds = null, endZoneOffsetSeconds = null, deviceName = null,
        )

        store.persist(HealthIngestionBatch(
            sleepSessions = listOf(session), sleepStages = emptyList(), heartRateSamples = emptyList(),
            hrvSamples = emptyList(), workouts = emptyList(), weights = emptyList(), bodyFatSamples = emptyList(),
            bloodPressureSamples = emptyList(), oxygenSaturationSamples = emptyList(),
            bodyTemperatureSamples = emptyList(), stepRecords = emptyList(),
        ))

        val dates = store.affectedDatesForRecord(HealthDataType.SLEEP, "hc-sleep-1", ZoneId.of("UTC"))

        assertEquals(1, dates.size)
    }

    @Test
    fun `deleteRecord removes the heart rate record and its source ref`() = runTest {
        store.persistHeartRateSamples(listOf(
            HeartRateInput(id = "hc-hr-1_1000", timestampMs = 1000L, beatsPerMinute = 60,
                recordType = "RESTING", sessionId = null, deviceName = null),
        ))
        assertEquals(1, store.countHeartRateInRange(0, 2000))

        store.deleteRecord(HealthDataType.HEART_RATE, "hc-hr-1")

        assertEquals(0, store.countHeartRateInRange(0, 2000))
    }

    @Test
    fun `sessionSpansOverlapping returns sleep and workout spans overlapping the window`() = runTest {
        val sleep = SleepSessionInput(
            id = "s1", startTime = 1_000L, endTime = 5_000L, durationMinutes = 1,
            efficiency = 1f, deepSleepMinutes = 0, remSleepMinutes = 0, lightSleepMinutes = 1,
            awakeMinutes = 0, sleepScore = null, startZoneOffsetSeconds = null,
            endZoneOffsetSeconds = null, deviceName = null,
        )
        val workout = WorkoutInput(
            id = "w1", startTime = 10_000L, endTime = 20_000L, exerciseType = "running",
            durationMinutes = 1, zone1Minutes = 0f, zone2Minutes = 0f, zone3Minutes = 0f,
            zone4Minutes = 0f, zone5Minutes = 0f, trimp = 0f, avgHr = 0f, deviceName = null,
        )
        store.persist(HealthIngestionBatch(
            sleepSessions = listOf(sleep), sleepStages = emptyList(), heartRateSamples = emptyList(),
            hrvSamples = emptyList(), workouts = listOf(workout), weights = emptyList(),
            bodyFatSamples = emptyList(), bloodPressureSamples = emptyList(),
            oxygenSaturationSamples = emptyList(), bodyTemperatureSamples = emptyList(), stepRecords = emptyList(),
        ))

        val spans = store.sessionSpansOverlapping(0L, 25_000L)

        assertEquals(listOf("s1"), spans.sleepSessions.map { it.id })
        assertEquals(listOf("w1"), spans.workouts.map { it.id })
    }

    @Test
    fun `heartRateSamplesForMetrics filters by record type and range`() = runTest {
        store.persistHeartRateSamples(listOf(
            HeartRateInput(id = "hc-hr-2_1000", timestampMs = 1000L, beatsPerMinute = 140,
                recordType = "EXERCISE", sessionId = "w1", deviceName = null),
            HeartRateInput(id = "hc-hr-3_2000", timestampMs = 2000L, beatsPerMinute = 60,
                recordType = "RESTING", sessionId = null, deviceName = null),
        ))

        val samples = store.heartRateSamplesForMetrics("EXERCISE", 0L, 5000L)

        assertEquals(1, samples.size)
        assertEquals(140, samples.single().beatsPerMinute)
    }

    private companion object {
        const val START_MS = 1_700_000_000_000L
    }
}
