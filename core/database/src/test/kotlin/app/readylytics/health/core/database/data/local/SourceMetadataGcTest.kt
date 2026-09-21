package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.dao.getOrCreateSourceRef
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrSourceMinuteContributionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.ScanSeenIdEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SourceMetadataGcTest {
    private lateinit var database: HealthDatabase

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun deletesOnlySourcesWithNoRawNoWarmAndNoPendingReference() =
        runBlocking {
            val dao = database.sourceRecordDao()
            val withRaw = dao.getOrCreateSourceRef("hc-raw", "HEART_RATE", 0L)
            val withWarm = dao.getOrCreateSourceRef("hc-warm", "HEART_RATE", 0L)
            val staged = dao.getOrCreateSourceRef("hc-staged", "HEART_RATE", 0L)
            dao.getOrCreateSourceRef("hc-orphan", "HEART_RATE", 0L)

            database.heartRateDao().upsertAll(
                listOf(
                    HeartRateRecordEntity(withRaw, 1_000L, 70, "RESTING", null, "watch"),
                ),
            )
            database.minuteCoverageDao().upsertContributions(
                listOf(
                    HrSourceMinuteContributionEntity(
                        sourceRecordRef = withWarm,
                        bucketStartMs = 0L,
                        generation = 1L,
                        firstSampleMs = 0L,
                        lastSampleMs = 1_000L,
                        deviceName = "watch",
                        bpmHistogram = "v1:70=1",
                    ),
                ),
            )
            database.scanStagingDao().insertSeenIds(
                listOf(ScanSeenIdEntity("run-a", "0", "HEART_RATE", "hc-staged")),
            )

            val deleted = SourceMetadataGc.collect(dao)

            assertEquals(1, deleted)
            val remaining = dao.pageAfter(0L, 100).map { it.sourceRecordId }.toSet()
            assertEquals(setOf("hc-raw", "hc-warm", "hc-staged"), remaining)
            assertEquals(staged, dao.getSourceRef("hc-staged"))
        }

    @Test
    fun collectionIsBoundedPerRun() =
        runBlocking {
            val dao = database.sourceRecordDao()
            repeat(120) { dao.getOrCreateSourceRef("hc-$it", "HEART_RATE", 0L) }

            val deleted = SourceMetadataGc.collect(dao, pageSize = 25, limitPerRun = 50)

            assertEquals(50, deleted)
            assertEquals(70, dao.count())
        }
}
