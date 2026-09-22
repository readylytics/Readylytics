package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.SourceMetadata
import app.readylytics.health.core.model.domain.sync.SourcePayload
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SourcePayloadWriterBatchingTest {
    private lateinit var database: HealthDatabase
    private lateinit var runner: RecordingTransactionRunner
    private lateinit var writer: SourcePayloadWriter

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        runner = RecordingTransactionRunner(RoomTransactionRunner(database))
        val daos =
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
                minuteBucketMaintenanceDao = database.minuteBucketMaintenanceDao(),
            )
        writer = SourcePayloadWriter(daos = daos, transactionRunner = runner)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun onePageOfManyParentsUsesOneTransaction() =
        runBlocking {
            val payloads = (1..600).map { payload("hc-$it", it.toLong()) }

            writer.replaceHeartRateSources(payloads)

            assertEquals(1, runner.transactionCount)
            assertEquals(600, database.sourceRecordDao().count())
            assertEquals(1800, database.heartRateDao().count())
        }

    @Test
    fun aPageLargerThanTheRowBudgetSplitsIntoBoundedTransactions() =
        runBlocking {
            // 20 parents x 400 samples = 8_000 rows, over PAGE_TRANSACTION_MAX_ROWS (5_000).
            val payloads = (1..20).map { parent -> payload("hc-$parent", parent.toLong(), sampleCount = 400) }

            writer.replaceHeartRateSources(payloads)

            assertEquals(2, runner.transactionCount)
            assertEquals(8_000, database.heartRateDao().count())
        }

    @Test
    fun identicalReimportChangesNoRowsAndOpensNoWriteForUnchangedSources() =
        runBlocking {
            val payloads = (1..50).map { payload("hc-$it", it.toLong()) }
            writer.replaceHeartRateSources(payloads)
            val revisionsBefore =
                database.sourceRecordDao().pageAfter(0L, 100).associate { it.sourceRecordId to it.sourceRevision }
            runner.reset()

            writer.replaceHeartRateSources(payloads)

            val revisionsAfter =
                database.sourceRecordDao().pageAfter(0L, 100).associate { it.sourceRecordId to it.sourceRevision }
            assertEquals(revisionsBefore, revisionsAfter)
            assertTrue("identical replay must not open a per-parent transaction", runner.transactionCount <= 1)
        }

    private fun payload(
        sourceId: String,
        index: Long,
        sampleCount: Int = 3,
    ): SourcePayload<HeartRateInput> {
        val startMs = index * 600_000L
        val rows =
            (0 until sampleCount).map { i ->
                HeartRateInput(
                    id = "${sourceId}_${startMs + i * 1_000L}",
                    timestampMs = startMs + i * 1_000L,
                    beatsPerMinute = 60 + (i % 20),
                    recordType = "RESTING",
                    sessionId = null,
                    deviceName = "watch",
                )
            }
        return SourcePayload(
            source =
                SourceMetadata(
                    sourceId = sourceId,
                    recordType = "HEART_RATE",
                    originPackage = "com.example.provider",
                    startMs = startMs,
                    endExclusiveMs = startMs + sampleCount * 1_000L,
                    lastModifiedMs = null,
                ),
            rows = rows,
        )
    }

    private class RecordingTransactionRunner(
        private val delegate: TransactionRunner,
    ) : TransactionRunner {
        var transactionCount = 0
            private set

        override suspend fun <R> runInTransaction(block: suspend () -> R): R {
            transactionCount++
            return delegate.runInTransaction(block)
        }

        fun reset() {
            transactionCount = 0
        }
    }
}
