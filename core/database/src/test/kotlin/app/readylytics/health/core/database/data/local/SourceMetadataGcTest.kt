package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.dao.getOrCreateSourceRef
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrSourceMinuteContributionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.ScanSeenIdEntity
import app.readylytics.health.core.model.domain.sync.IntervalKind
import app.readylytics.health.core.model.domain.sync.IntervalSourceRecord
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

    /**
     * Regression: `health_source_records` holds two semantically different kinds of row. For
     * `HEART_RATE`/`HRV` it is a derived index over raw children. For `DISTANCE`/
     * `ELEVATION_GAINED` -- written by [RoomHealthChangeIngestionStore.persistIntervalEnrichment]
     * -- the row IS the record: it carries the interval's own bounds/origin/lastModified and has
     * no children in any table, so every reference check in `pageUnreferencedSourceIds` passes for
     * it. Without the record-type allowlist, the next `DataCleanupWorker` run would permanently
     * erase an interval source created minutes earlier, and `getIntervalSource` would stop being
     * able to resolve that record's previous range -- so a later Health Connect correction or
     * deletion of the distance would silently never mark its dates dirty.
     */
    @Test
    fun keepsIntervalSourceMetadataThatHasNoRawWarmOrStagedReference() =
        runBlocking {
            val daos = healthRecordDaos()
            val changeStore =
                RoomHealthChangeIngestionStore(daos = daos, vo2MaxRecordDao = database.vo2MaxRecordDao())
            changeStore.persistIntervalEnrichment(
                preparedWorkouts = emptyList(),
                sourceUpserts =
                    listOf(
                        IntervalSourceRecord(
                            sourceId = "hc-distance",
                            kind = IntervalKind.DISTANCE,
                            startMs = 15_000L,
                            endExclusiveMs = 25_000L,
                            originPackage = "com.strava",
                            lastModifiedMs = 20_000L,
                        ),
                        IntervalSourceRecord(
                            sourceId = "hc-elevation",
                            kind = IntervalKind.ELEVATION_GAINED,
                            startMs = 15_000L,
                            endExclusiveMs = 25_000L,
                            originPackage = "com.strava",
                            lastModifiedMs = 20_000L,
                        ),
                    ),
                sourceDeletes = emptyList(),
                dirtyDates = emptySet(),
            )
            val dao = database.sourceRecordDao()
            dao.getOrCreateSourceRef("hc-orphan", "HEART_RATE", 0L)

            val deleted = SourceMetadataGc.collect(dao)

            assertEquals(1, deleted)
            assertEquals(
                setOf("hc-distance", "hc-elevation"),
                dao.pageAfter(0L, 100).map { it.sourceRecordId }.toSet(),
            )
            // The consumer contract that the GC must not break: both records still resolve to
            // their previous range, so an update/delete from Health Connect can still be diffed.
            assertEquals(15_000L, changeStore.getIntervalSource("hc-distance")?.startMs)
            assertEquals(IntervalKind.ELEVATION_GAINED, changeStore.getIntervalSource("hc-elevation")?.kind)
        }

    private fun healthRecordDaos() =
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
