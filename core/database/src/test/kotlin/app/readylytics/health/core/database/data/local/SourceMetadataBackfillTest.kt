package app.readylytics.health.core.database.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.dao.SourceRecordDao
import app.readylytics.health.core.databaseschema.data.local.entity.HealthMutationStateEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SourceMetadataBackfillTest {
    private lateinit var context: Context
    private lateinit var db: HealthDatabase
    private lateinit var backfill: SourceMetadataBackfill

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        val runner = RoomTransactionRunner(db)
        backfill =
            SourceMetadataBackfill(
                sourceRecordDao = db.sourceRecordDao(),
                heartRateDao = db.heartRateDao(),
                hrvDao = db.hrvDao(),
                healthMutationStateDao = db.healthMutationStateDao(),
                transactionRunner = runner,
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun backfillUpdatesChildBoundsForHrAndHrvAndPreservesWarmOnlyAndAuthoritative() =
        runTest {
            seedFourSources(db)

            val result1 = backfill.backfill(batchSize = 2, maxBatches = 1)
            assertEquals(1, result1.batchesProcessed)
            assertEquals(2, result1.sourcesExamined)
            assertEquals(2, result1.sourcesUpdated)
            assertFalse(result1.isComplete)
            assertEquals(2L, db.healthMutationStateDao().get()!!.backfillAfterSourceRef)

            assertRefBounds(db, id = 1, state = "CHILD_BOUNDS", start = 1000L, end = 2001L)
            assertRefBounds(db, id = 2, state = "CHILD_BOUNDS", start = 3000L, end = 3001L)

            val result2 = backfill.backfill(batchSize = 2, maxBatches = 10)
            assertEquals(1, result2.batchesProcessed)
            assertEquals(2, result2.sourcesExamined)
            assertEquals(0, result2.sourcesUpdated)
            assertTrue(result2.isComplete)
            assertEquals(4L, db.healthMutationStateDao().get()!!.backfillAfterSourceRef)

            val ref3 = db.sourceRecordDao().getById(3)!!
            assertEquals("UNKNOWN", ref3.metadataState)
            assertNull(ref3.recordStartMs)

            val ref4 = db.sourceRecordDao().getById(4)!!
            assertEquals("AUTHORITATIVE", ref4.metadataState)
            assertEquals("com.google.android.apps.healthdata", ref4.originPackage)
        }

    @Test
    fun interruptedBackfillMatchesUninterruptedBackfill() =
        runTest {
            val db2 =
                Room
                    .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            val backfill2 =
                SourceMetadataBackfill(
                    sourceRecordDao = db2.sourceRecordDao(),
                    heartRateDao = db2.heartRateDao(),
                    hrvDao = db2.hrvDao(),
                    healthMutationStateDao = db2.healthMutationStateDao(),
                    transactionRunner = RoomTransactionRunner(db2),
                )

            val sources = createComparisonSources()
            val hrSamples = createComparisonHrSamples()
            val hrvSamples = createComparisonHrvSamples()

            seedComparisonDb(db, sources, hrSamples, hrvSamples)
            seedComparisonDb(db2, sources, hrSamples, hrvSamples)

            backfill.backfill(batchSize = 1, maxBatches = 1)
            backfill.backfill(batchSize = 1, maxBatches = 1)
            backfill.backfill(batchSize = 1, maxBatches = 10)

            backfill2.backfill(batchSize = 10)

            assertEquals(db2.sourceRecordDao().getAll(), db.sourceRecordDao().getAll())
            assertEquals(db2.healthMutationStateDao().get(), db.healthMutationStateDao().get())

            db2.close()
        }

    @Test
    fun checkedOverflowCapsAtLongMax() =
        runTest {
            db.healthMutationStateDao().upsert(HealthMutationStateEntity(id = 1))
            db.sourceRecordDao().insertAll(
                listOf(
                    HealthSourceRecordEntity(
                        id = 10,
                        sourceRecordId = "s-max",
                        recordType = "HEART_RATE",
                        createdAtMs = 1000L,
                    ),
                ),
            )
            db.heartRateDao().upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        sourceRecordRef = 10,
                        timestampMs = Long.MAX_VALUE,
                        beatsPerMinute = 60,
                        recordType = "RESTING",
                    ),
                ),
            )

            backfill.backfill()

            val ref10 = db.sourceRecordDao().getById(10)!!
            assertEquals("CHILD_BOUNDS", ref10.metadataState)
            assertEquals(Long.MAX_VALUE, ref10.recordStartMs)
            assertEquals(Long.MAX_VALUE, ref10.recordEndExclusiveMs)
        }

    private suspend fun seedFourSources(database: HealthDatabase) {
        database.healthMutationStateDao().upsert(
            HealthMutationStateEntity(id = 1, sourceGeneration = 0, backfillAfterSourceRef = 0),
        )
        database.sourceRecordDao().insertAll(
            listOf(
                HealthSourceRecordEntity(
                    id = 1,
                    sourceRecordId = "src-hr-1",
                    recordType = "HEART_RATE",
                    createdAtMs = 1000L,
                ),
                HealthSourceRecordEntity(
                    id = 2,
                    sourceRecordId = "src-hrv-2",
                    recordType = "HRV",
                    createdAtMs = 2000L,
                ),
                HealthSourceRecordEntity(
                    id = 3,
                    sourceRecordId = "src-warm-3",
                    recordType = "HEART_RATE",
                    createdAtMs = 3000L,
                ),
                HealthSourceRecordEntity(
                    id = 4,
                    sourceRecordId = "src-auth-4",
                    recordType = "HEART_RATE",
                    createdAtMs = 4000L,
                    originPackage = "com.google.android.apps.healthdata",
                    recordStartMs = 4000L,
                    recordEndExclusiveMs = 5000L,
                    metadataState = "AUTHORITATIVE",
                    sourceRevision = 1L,
                ),
            ),
        )
        seedSamples(database)
    }

    private suspend fun seedSamples(database: HealthDatabase) {
        database.heartRateDao().upsertAll(
            listOf(
                HeartRateRecordEntity(
                    sourceRecordRef = 1,
                    timestampMs = 1000L,
                    beatsPerMinute = 60,
                    recordType = "RESTING",
                ),
                HeartRateRecordEntity(
                    sourceRecordRef = 1,
                    timestampMs = 2000L,
                    beatsPerMinute = 65,
                    recordType = "RESTING",
                ),
            ),
        )
        database.hrvDao().upsertAll(
            listOf(
                HrvRecordEntity(
                    sourceRecordRef = 2,
                    timestampMs = 3000L,
                    rmssdMs = 42f,
                    recordType = "SLEEP",
                ),
            ),
        )
    }


    private suspend fun assertRefBounds(
        database: HealthDatabase,
        id: Long,
        state: String,
        start: Long,
        end: Long,
    ) {
        val ref = database.sourceRecordDao().getById(id)!!
        assertEquals(state, ref.metadataState)
        assertEquals(start, ref.recordStartMs)
        assertEquals(end, ref.recordEndExclusiveMs)
        assertNull(ref.originPackage)
    }

    private fun createComparisonSources() =
        listOf(
            HealthSourceRecordEntity(
                id = 1,
                sourceRecordId = "s1",
                recordType = "HEART_RATE",
                createdAtMs = 1000L,
            ),
            HealthSourceRecordEntity(
                id = 2,
                sourceRecordId = "s2",
                recordType = "HRV",
                createdAtMs = 2000L,
            ),
            HealthSourceRecordEntity(
                id = 3,
                sourceRecordId = "s3",
                recordType = "HEART_RATE",
                createdAtMs = 3000L,
            ),
            HealthSourceRecordEntity(
                id = 4,
                sourceRecordId = "s4",
                recordType = "HRV",
                createdAtMs = 4000L,
            ),
        )

    private fun createComparisonHrSamples() =
        listOf(
            HeartRateRecordEntity(
                sourceRecordRef = 1,
                timestampMs = 100L,
                beatsPerMinute = 60,
                recordType = "RESTING",
            ),
            HeartRateRecordEntity(
                sourceRecordRef = 1,
                timestampMs = 200L,
                beatsPerMinute = 65,
                recordType = "RESTING",
            ),
            HeartRateRecordEntity(
                sourceRecordRef = 3,
                timestampMs = 500L,
                beatsPerMinute = 70,
                recordType = "RESTING",
            ),
        )

    private fun createComparisonHrvSamples() =
        listOf(
            HrvRecordEntity(sourceRecordRef = 2, timestampMs = 300L, rmssdMs = 50f, recordType = "SLEEP"),
            HrvRecordEntity(sourceRecordRef = 4, timestampMs = 700L, rmssdMs = 55f, recordType = "SLEEP"),
        )

    private suspend fun seedComparisonDb(
        targetDb: HealthDatabase,
        sources: List<HealthSourceRecordEntity>,
        hr: List<HeartRateRecordEntity>,
        hrv: List<HrvRecordEntity>,
    ) {
        targetDb.healthMutationStateDao().upsert(HealthMutationStateEntity(id = 1))
        targetDb.sourceRecordDao().insertAll(sources)
        targetDb.heartRateDao().upsertAll(hr)
        targetDb.hrvDao().upsertAll(hrv)
    }

    private suspend fun SourceRecordDao.getById(id: Long): HealthSourceRecordEntity? =
        pageAfter(id - 1, 1).firstOrNull { it.id == id }
}
