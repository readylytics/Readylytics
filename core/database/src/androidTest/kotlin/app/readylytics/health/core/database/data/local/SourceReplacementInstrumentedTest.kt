package app.readylytics.health.core.database.data.local

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.SourceMetadata
import app.readylytics.health.core.model.domain.sync.SourcePayload
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceReplacementInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: HealthDatabase
    private lateinit var transactionRunner: RoomTransactionRunner

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getDatabasePath(DB_NAME).delete()
        database =
            Room
                .databaseBuilder(context, HealthDatabase::class.java, DB_NAME)
                .allowMainThreadQueries()
                .build()
        transactionRunner = RoomTransactionRunner(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.getDatabasePath(DB_NAME).delete()
    }

    @Test
    fun multiChunkPayloadRollsBackCompletelyOnFailureAfterFirstChunk() =
        runBlocking {
            val sourceId = "fault_inject_parent"
            val baseTime = 1_700_000_000_000L
            val totalRows = 600

            val samples =
                (1..totalRows).map { i ->
                    HeartRateInput(
                        id = "${sourceId}_${baseTime + i * 1000L}",
                        sourceId = sourceId,
                        timestampMs = baseTime + i * 1000L,
                        beatsPerMinute = 70 + (i % 20),
                        recordType = "RESTING",
                        sessionId = null,
                        deviceName = "Watch",
                    )
                }

            val faultDao = FaultInjectingHeartRateDao(database.heartRateDao(), failAfterRows = 500)
            val store =
                RoomHealthIngestionStore(
                    daos =
                        HealthRecordDaos(
                            sleepSessionDao = database.sleepSessionDao(),
                            sleepStageDao = database.sleepStageDao(),
                            heartRateDao = faultDao,
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
                            minuteBucketMaintenanceDao = database.minuteBucketMaintenanceDao(),
                        ),
                    dailySummaryDao = database.dailySummaryDao(),
                    transactionRunner = transactionRunner,
                    vo2MaxRecordDao = database.vo2MaxRecordDao(),
                    scanTypeStateDao = database.scanTypeStateDao(),
                )

            val payload =
                SourcePayload(
                    source = SourceMetadata(sourceId, baseTime, baseTime + totalRows * 1000L),
                    rows = samples,
                )

            assertThrows(SQLiteException::class.java) {
                runBlocking {
                    store.replaceHeartRateSources(listOf(payload))
                }
            }

            // Transaction rolled back: 0 rows inserted in heart_rate_records table
            assertEquals(0, database.heartRateDao().count())
            // Source record was also rolled back
            assertNull(database.sourceRecordDao().getBySourceRecordId(sourceId))
        }

    private class FaultInjectingHeartRateDao(
        private val delegate: HeartRateDao,
        private val failAfterRows: Int,
    ) : HeartRateDao by delegate {
        private var rowsUpserted = 0

        override suspend fun upsertAll(records: List<HeartRateRecordEntity>) {
            rowsUpserted += records.size
            if (rowsUpserted > failAfterRows) {
                throw SQLiteException("Fault injection: exceeded $failAfterRows rows ($rowsUpserted)")
            }
            delegate.upsertAll(records)
        }
    }

    private companion object {
        const val DB_NAME = "source_replacement_test.db"
    }
}
