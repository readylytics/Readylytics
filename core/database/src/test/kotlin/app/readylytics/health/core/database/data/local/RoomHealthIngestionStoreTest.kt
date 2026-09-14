package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.model.domain.sync.BloodPressureInput
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.HrvInput
import app.readylytics.health.core.model.domain.sync.SourceMetadata
import app.readylytics.health.core.model.domain.sync.SourcePayload
import app.readylytics.health.core.model.domain.sync.Vo2MaxInput
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
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
        store = createTestStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `replaceHeartRateSources persists 1500 samples across batches with conflict update and idempotency`() =
        runTest {
            val sourceId = "hc-hr-record"
            val initialSamples =
                (1..1500).map { index ->
                    HeartRateInput(
                        id = "${sourceId}_${index}_sub",
                        sourceId = sourceId,
                        timestampMs = START_MS + index * 1000L,
                        beatsPerMinute = 60 + (index % 50),
                        recordType = "RESTING",
                        sessionId = null,
                        deviceName = "Watch V1",
                    )
                }

            // 1. Initial persistence of 1,500 samples across 500-row batch boundaries
            store.replaceHeartRateSources(
                listOf(hrPayload(sourceId, initialSamples, START_MS + 1000L, START_MS + 1500 * 1000L)),
            )

            assertEquals(1500, database.heartRateDao().count())
            val firstPersisted = database.heartRateDao().getByTimeRange(START_MS, START_MS + 2000L).first()
            assertEquals("RESTING", firstPersisted.recordType)
            assertEquals("Watch V1", firstPersisted.deviceName)

            // 2. Re-persist 1,500 samples with duplicate timestamps / source ref but updated metadata
            val updatedSamples =
                (1..1500).map { index ->
                    HeartRateInput(
                        id = "${sourceId}_${index}_sub",
                        sourceId = sourceId,
                        timestampMs = START_MS + index * 1000L,
                        beatsPerMinute = 60 + (index % 50),
                        recordType = "SLEEP",
                        sessionId = "session-1",
                        deviceName = "Watch V2",
                    )
                }
            store.replaceHeartRateSources(
                listOf(hrPayload(sourceId, updatedSamples, START_MS + 1000L, START_MS + 1500 * 1000L)),
            )

            // Row count must remain 1500 (conflict-targeted update, no duplicate key errors)
            assertEquals(1500, database.heartRateDao().count())
            val updatedRecords = database.heartRateDao().getByTimeRange(START_MS, START_MS + 2000_000L)
            assertEquals(1500, updatedRecords.size)
            assertEquals(1500, updatedRecords.count { it.recordType == "SLEEP" })
            assertEquals(1500, updatedRecords.count { it.sessionId == "session-1" })
            assertEquals(1500, updatedRecords.count { it.deviceName == "Watch V2" })

            // 3. Idempotent re-persist with identical samples
            store.replaceHeartRateSources(
                listOf(hrPayload(sourceId, updatedSamples, START_MS + 1000L, START_MS + 1500 * 1000L)),
            )
            assertEquals(1500, database.heartRateDao().count())
        }

    @Test
    fun `replaceHrvSources persists 1500 samples across batches with conflict update and idempotency`() =
        runTest {
            val sourceId = "hc-hrv-record"
            val initialSamples =
                (1..1500).map { index ->
                    HrvInput(
                        id = "${sourceId}_${index}_sub",
                        sourceId = sourceId,
                        timestampMs = START_MS + index * 1000L,
                        rmssdMs = 45f + (index % 20),
                        recordType = "RESTING",
                        sessionId = null,
                        deviceName = "Watch V1",
                    )
                }

            // 1. Initial persistence of 1,500 samples across 500-row batch boundaries
            store.replaceHrvSources(
                listOf(hrvPayload(sourceId, initialSamples, START_MS + 1000L, START_MS + 1500 * 1000L)),
            )

            assertEquals(1500, database.hrvDao().count())
            val firstPersisted = database.hrvDao().getByTimeRange(START_MS, START_MS + 2000L).first()
            assertEquals("RESTING", firstPersisted.recordType)
            assertEquals("Watch V1", firstPersisted.deviceName)

            // 2. Re-persist 1,500 samples with duplicate timestamps / source ref but updated metadata
            val updatedSamples =
                (1..1500).map { index ->
                    HrvInput(
                        id = "${sourceId}_${index}_sub",
                        sourceId = sourceId,
                        timestampMs = START_MS + index * 1000L,
                        rmssdMs = 45f + (index % 20),
                        recordType = "SLEEP",
                        sessionId = "session-1",
                        deviceName = "Watch V2",
                    )
                }
            store.replaceHrvSources(
                listOf(hrvPayload(sourceId, updatedSamples, START_MS + 1000L, START_MS + 1500 * 1000L)),
            )

            // Row count must remain 1500 (conflict-targeted update, no duplicate key errors)
            assertEquals(1500, database.hrvDao().count())
            val updatedRecords = database.hrvDao().getByTimeRange(START_MS, START_MS + 2000_000L)
            assertEquals(1500, updatedRecords.size)
            assertEquals(1500, updatedRecords.count { it.recordType == "SLEEP" })
            assertEquals(1500, updatedRecords.count { it.sessionId == "session-1" })
            assertEquals(1500, updatedRecords.count { it.deviceName == "Watch V2" })

            // 3. Idempotent re-persist with identical samples
            store.replaceHrvSources(
                listOf(hrvPayload(sourceId, updatedSamples, START_MS + 1000L, START_MS + 1500 * 1000L)),
            )
            assertEquals(1500, database.hrvDao().count())
        }

    @Test
    fun `persist batch with 1500 HR and HRV sources persists all rows idempotently`() =
        runTest {
            val hrSamples =
                (1..1500).map { index ->
                    HeartRateInput(
                        id = "hc-hr-record_${index}_sub",
                        sourceId = "hc-hr-record",
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
                        sourceId = "hc-hrv-record",
                        timestampMs = START_MS + index * 1000L,
                        rmssdMs = 50f,
                        recordType = "RESTING",
                        sessionId = null,
                        deviceName = "Watch",
                    )
                }

            val hrPayload = hrPayload("hc-hr-record", hrSamples, START_MS + 1000L, START_MS + 1500 * 1000L)
            val hrvPayload = hrvPayload("hc-hrv-record", hrvSamples, START_MS + 1000L, START_MS + 1500 * 1000L)
            val batch =
                HealthIngestionBatch(
                    sleepSessions = emptyList(),
                    sleepStages = emptyList(),
                    heartRateSources = listOf(hrPayload),
                    hrvSources = listOf(hrvPayload),
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
    fun `authoritative replacement deletes missing rows updates existing and matches clean DB`() =
        runTest {
            val sourceId = "opaque_id_with_underscores"
            val s1 =
                HeartRateInput(
                    id = "${sourceId}_1000",
                    sourceId = sourceId,
                    timestampMs = 1000L,
                    beatsPerMinute = 80,
                    recordType = "RESTING",
                    sessionId = null,
                    deviceName = "W1",
                )
            val s2 =
                HeartRateInput(
                    id = "${sourceId}_2000",
                    sourceId = sourceId,
                    timestampMs = 2000L,
                    beatsPerMinute = 80,
                    recordType = "RESTING",
                    sessionId = null,
                    deviceName = "W1",
                )

            // 1. Initial ingestion: [1000, 2000]
            store.replaceHeartRateSources(listOf(hrPayload(sourceId, listOf(s1, s2), 1000L, 2001L)))
            assertEquals(2, database.heartRateDao().count())
            val sourceRef1 = database.sourceRecordDao().getBySourceRecordId(sourceId)?.id
            assertNotNull(sourceRef1)

            // 2. Update with [2000] (bpm 80 -> 81)
            val s2Updated = s2.copy(beatsPerMinute = 81)
            store.replaceHeartRateSources(listOf(hrPayload(sourceId, listOf(s2Updated), 2000L, 2001L)))
            assertEquals(1, database.heartRateDao().count())
            val remaining = database.heartRateDao().getByTimeRange(1000L, 2001L)
            assertEquals(1, remaining.size)
            assertEquals(2000L, remaining[0].timestampMs)
            assertEquals(81, remaining[0].beatsPerMinute)

            // 3. Update with empty samples: row deleted, source retained
            store.replaceHeartRateSources(listOf(hrPayload(sourceId, emptyList(), 2000L, 2001L)))
            assertEquals(0, database.heartRateDao().count())
            val sourceAfterEmpty = database.sourceRecordDao().getBySourceRecordId(sourceId)
            assertNotNull(sourceAfterEmpty)

            // 4. Update with [3000] (bpm 90)
            val s3 =
                HeartRateInput(
                    id = "${sourceId}_3000",
                    sourceId = sourceId,
                    timestampMs = 3000L,
                    beatsPerMinute = 90,
                    recordType = "RESTING",
                    sessionId = null,
                    deviceName = "W1",
                )
            store.replaceHeartRateSources(listOf(hrPayload(sourceId, listOf(s3), 3000L, 3001L)))
            assertEquals(1, database.heartRateDao().count())
            val finalRow = database.heartRateDao().getByTimeRange(2500L, 3500L).single()
            assertEquals(3000L, finalRow.timestampMs)
            assertEquals(90, finalRow.beatsPerMinute)

            // 5. Compare final state against clean DB that only ingested final state
            assertMatchesCleanDb(sourceId, s3)
        }

    @Test
    fun `identical payload replay twice preserves generation counter without unnecessary writes`() =
        runTest {
            val sourceId = "replay_gen_test"
            val sample =
                HeartRateInput(
                    id = "${sourceId}_1000",
                    sourceId = sourceId,
                    timestampMs = 1000L,
                    beatsPerMinute = 70,
                    recordType = "RESTING",
                    sessionId = null,
                    deviceName = "W",
                )
            val payload = listOf(hrPayload(sourceId, listOf(sample), 1000L, 2000L))

            store.replaceHeartRateSources(payload)
            val gen1 = database.sourceRecordDao().getBySourceRecordId(sourceId)?.sourceRevision
            assertNotNull(gen1)

            // Replay 1
            store.replaceHeartRateSources(payload)
            val gen2 = database.sourceRecordDao().getBySourceRecordId(sourceId)?.sourceRevision
            assertEquals(gen1, gen2)

            // Replay 2
            store.replaceHeartRateSources(payload)
            val gen3 = database.sourceRecordDao().getBySourceRecordId(sourceId)?.sourceRevision
            assertEquals(gen1, gen3)
        }

    @Test
    fun `authoritative HRV replacement updates rmssdMs on conflict`() =
        runTest {
            val sourceId = "hrv_conflict_test"
            val initial =
                HrvInput(
                    id = "${sourceId}_1000",
                    sourceId = sourceId,
                    timestampMs = 1000L,
                    rmssdMs = 40f,
                    recordType = "RESTING",
                    sessionId = null,
                    deviceName = "W1",
                )
            store.replaceHrvSources(listOf(hrvPayload(sourceId, listOf(initial), 1000L, 1001L)))
            assertEquals(40f, database.hrvDao().getByTimeRange(999L, 1001L).single().rmssdMs)

            val updated = initial.copy(rmssdMs = 64f, deviceName = "W2")
            store.replaceHrvSources(listOf(hrvPayload(sourceId, listOf(updated), 1000L, 1001L)))
            val row = database.hrvDao().getByTimeRange(999L, 1001L).single()
            assertEquals(64f, row.rmssdMs)
            assertEquals("W2", row.deviceName)
        }

    @Test
    fun `vital timestamp movement deletes old family row before inserting new timestamp`() =
        runTest {
            val bpSourceId = "bp_source_move"
            val batch1 = HealthIngestionBatch(
                sleepSessions = emptyList(), sleepStages = emptyList(), heartRateSamples = emptyList(),
                hrvSamples = emptyList(), workouts = emptyList(), weights = emptyList(),
                bodyFatSamples = emptyList(),
                bloodPressureSamples = listOf(
                    BloodPressureInput(
                        id = "${bpSourceId}_1000",
                        sourceId = bpSourceId,
                        timestampMs = 1000L,
                        systolicMmHg = 120,
                        diastolicMmHg = 80,
                        deviceName = "BP",
                    ),
                ),
                oxygenSaturationSamples = emptyList(), bodyTemperatureSamples = emptyList(), stepRecords = emptyList(),
            )
            store.persist(batch1)
            assertEquals(1, database.bloodPressureRecordDao().count())
            assertEquals(1000L, database.bloodPressureRecordDao().getByTimeRange(0, 5000L).single().timestampMs)

            // Move timestamp to 2000L
            val batch2 = HealthIngestionBatch(
                sleepSessions = emptyList(), sleepStages = emptyList(), heartRateSamples = emptyList(),
                hrvSamples = emptyList(), workouts = emptyList(), weights = emptyList(),
                bodyFatSamples = emptyList(),
                bloodPressureSamples = listOf(
                    BloodPressureInput(
                        id = "${bpSourceId}_2000",
                        sourceId = bpSourceId,
                        timestampMs = 2000L,
                        systolicMmHg = 122,
                        diastolicMmHg = 82,
                        deviceName = "BP",
                    ),
                ),
                oxygenSaturationSamples = emptyList(), bodyTemperatureSamples = emptyList(), stepRecords = emptyList(),
            )
            store.persist(batch2)
            val rows = database.bloodPressureRecordDao().getByTimeRange(0, 5000L)
            assertEquals(1, rows.size)
            assertEquals(2000L, rows.single().timestampMs)
            assertEquals("${bpSourceId}_2000", rows.single().id)
        }

    @Test
    fun `multiple samples with identical millisecond in single parent are handled deterministically without crash`() =
        runTest {
            val sourceId = "dup_ms_source"
            val s1 =
                HeartRateInput(
                    id = "${sourceId}_1000_a",
                    sourceId = sourceId,
                    timestampMs = 1000L,
                    beatsPerMinute = 60,
                    recordType = "RESTING",
                    sessionId = null,
                    deviceName = "W1",
                )
            val s2 =
                HeartRateInput(
                    id = "${sourceId}_1000_b",
                    sourceId = sourceId,
                    timestampMs = 1000L,
                    beatsPerMinute = 75,
                    recordType = "RESTING",
                    sessionId = null,
                    deviceName = "W1",
                )

            store.replaceHeartRateSources(listOf(hrPayload(sourceId, listOf(s1, s2), 1000L, 1001L)))
            assertEquals(1, database.heartRateDao().count())
            val stored = database.heartRateDao().getByTimeRange(999L, 1001L).single()
            assertEquals(1000L, stored.timestampMs)
            assertEquals(75, stored.beatsPerMinute)
        }

    private fun hrPayload(
        sourceId: String,
        rows: List<HeartRateInput>,
        startMs: Long = rows.minOfOrNull { it.timestampMs } ?: 0L,
        endExclusiveMs: Long = rows.maxOfOrNull { it.timestampMs + 1 } ?: (startMs + 1),
    ): SourcePayload<HeartRateInput> =
        SourcePayload(
            source = SourceMetadata(
                sourceId = sourceId,
                recordType = "HEART_RATE",
                originPackage = "pkg",
                startMs = startMs,
                endExclusiveMs = endExclusiveMs,
            ),
            rows = rows,
        )

    private fun hrvPayload(
        sourceId: String,
        rows: List<HrvInput>,
        startMs: Long = rows.minOfOrNull { it.timestampMs } ?: 0L,
        endExclusiveMs: Long = rows.maxOfOrNull { it.timestampMs + 1 } ?: (startMs + 1),
    ): SourcePayload<HrvInput> =
        SourcePayload(
            source = SourceMetadata(
                sourceId = sourceId,
                recordType = "HRV",
                originPackage = "pkg",
                startMs = startMs,
                endExclusiveMs = endExclusiveMs,
            ),
            rows = rows,
        )

    @Test
    fun `persist persists vo2MaxSamples with idempotency`() =
        runTest {
            val samples =
                listOf(
                    Vo2MaxInput(
                        id = "vo2_1",
                        timestampMs = START_MS,
                        vo2Max = 52.5f,
                        measurementMethod = 1,
                        deviceName = "Pixel Watch",
                    ),
                    Vo2MaxInput(
                        id = "vo2_2",
                        timestampMs = START_MS + 60_000,
                        vo2Max = 53.0f,
                        measurementMethod = 1,
                        deviceName = "Pixel Watch",
                    ),
                )

            val batch =
                HealthIngestionBatch(
                    sleepSessions = emptyList(),
                    sleepStages = emptyList(),
                    heartRateSamples = emptyList(),
                    hrvSamples = emptyList(),
                    workouts = emptyList(),
                    weights = emptyList(),
                    bodyFatSamples = emptyList(),
                    bloodPressureSamples = emptyList(),
                    oxygenSaturationSamples = emptyList(),
                    bodyTemperatureSamples = emptyList(),
                    stepRecords = emptyList(),
                    vo2MaxSamples = samples,
                )

            store.persist(batch)
            assertEquals(2, database.vo2MaxRecordDao().count())

            // Idempotent re-persist
            store.persist(batch)
            assertEquals(2, database.vo2MaxRecordDao().count())
            val records = database.vo2MaxRecordDao().getByTimeRange(START_MS, START_MS + 120_000)
            assertEquals(2, records.size)
            assertEquals("vo2_2", records[0].id)
            assertEquals(53.0f, records[0].vo2Max)
        }

    private fun createTestStore(db: HealthDatabase): RoomHealthIngestionStore =
        RoomHealthIngestionStore(
            daos =
                HealthRecordDaos(
                    sleepSessionDao = db.sleepSessionDao(),
                    sleepStageDao = db.sleepStageDao(),
                    heartRateDao = db.heartRateDao(),
                    hrvDao = db.hrvDao(),
                    workoutDao = db.workoutDao(),
                    workoutRoutePointDao = db.workoutRoutePointDao(),
                    weightRecordDao = db.weightRecordDao(),
                    bodyFatRecordDao = db.bodyFatRecordDao(),
                    bloodPressureRecordDao = db.bloodPressureRecordDao(),
                    oxygenSaturationRecordDao = db.oxygenSaturationRecordDao(),
                    bodyTemperatureRecordDao = db.bodyTemperatureRecordDao(),
                    stepRecordDao = db.stepRecordDao(),
                    sourceRecordDao = db.sourceRecordDao(),
                    minuteBucketMaintenanceDao = db.minuteBucketMaintenanceDao(),
                ),
            dailySummaryDao = db.dailySummaryDao(),
            transactionRunner = RoomTransactionRunner(db),
            vo2MaxRecordDao = db.vo2MaxRecordDao(),
        )

    private suspend fun assertMatchesCleanDb(
        sourceId: String,
        sample: HeartRateInput,
    ) {
        val cleanDb =
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val cleanStore = createTestStore(cleanDb)
        cleanStore.replaceHeartRateSources(listOf(hrPayload(sourceId, listOf(sample), 3000L, 3001L)))

        val liveRows = database.heartRateDao().getByTimeRange(0, 10000)
        val cleanRows = cleanDb.heartRateDao().getByTimeRange(0, 10000)
        assertEquals(cleanRows.size, liveRows.size)
        assertEquals(cleanRows[0].timestampMs, liveRows[0].timestampMs)
        assertEquals(cleanRows[0].beatsPerMinute, liveRows[0].beatsPerMinute)
        cleanDb.close()
    }

    private companion object {
        const val START_MS = 1_700_000_000_000L
    }
}
