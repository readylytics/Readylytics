package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.HrvInput
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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

    private companion object {
        const val START_MS = 1_700_000_000_000L
    }
}
