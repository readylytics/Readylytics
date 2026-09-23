package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.dao.getOrCreateSourceRef
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.SourceMetadata
import app.readylytics.health.core.model.domain.sync.SourcePayload
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.sync.ScoringRunContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
class WarmMutationRegressionTest {
    private lateinit var database: HealthDatabase
    private lateinit var daos: HealthRecordDaos
    private lateinit var publisher: MinuteCoveragePublisher
    private lateinit var writer: SourcePayloadWriter
    private lateinit var relinker: WarmTierRelinker

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        daos =
            HealthRecordDaos(
                database.sleepSessionDao(),
                database.sleepStageDao(),
                database.heartRateDao(),
                database.hrvDao(),
                database.workoutDao(),
                database.workoutRoutePointDao(),
                database.weightRecordDao(),
                database.bodyFatRecordDao(),
                database.bloodPressureRecordDao(),
                database.oxygenSaturationRecordDao(),
                database.bodyTemperatureRecordDao(),
                database.stepRecordDao(),
                database.sourceRecordDao(),
                database.minuteBucketMaintenanceDao(),
            )
        runBlocking { database.healthMutationStateDao().getOrCreate() }
        publisher =
            MinuteCoveragePublisher(
                database.minuteBucketDao(),
                database.minuteCoverageDao(),
                database.dirtyRangeDao(),
                database.healthMutationStateDao(),
            )
        writer = createWriter(RoomTransactionRunner(database))
        relinker =
            WarmTierRelinker(
                database.minuteCoverageSelectionDao(),
                database.minuteCoverageDao(),
                database.minuteBucketDao(),
                publisher,
                RoomTransactionRunner(database),
                database.healthMutationStateDao(),
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `refresh replaces warm source evidence and preserves the other source`() =
        runBlocking {
            seedWarmMinute(0L)
            writer.replaceHeartRateSources(listOf(payload(listOf(140, 160))))
            assertEquals(listOf(70.0, 150.0), visibleAverages())
            relinker.relink(0L, 59_999L, emptyList(), emptyList())
            assertEquals(listOf(70.0, 150.0), visibleAverages())
            assertEquals(0, database.heartRateDao().count())
            val before = database.minuteBucketDao().getVisibleBucketsInMinuteRange(0L, 60_000L)
            val generation = database.healthMutationStateDao().current().sourceGeneration
            writer.replaceHeartRateSources(listOf(payload(listOf(140, 160))))
            assertEquals(before, database.minuteBucketDao().getVisibleBucketsInMinuteRange(0L, 60_000L))
            assertEquals(generation, database.healthMutationStateDao().current().sourceGeneration)
        }

    @Test
    fun `empty refresh removes a rolled up source while preserving other evidence`() =
        runBlocking {
            seedWarmMinute(0L)
            writer.replaceHeartRateSources(listOf(payload(emptyList())))
            assertEquals(listOf(70.0), visibleAverages())
            relinker.relink(0L, 59_999L, emptyList(), emptyList())
            assertEquals(listOf(70.0), visibleAverages())
        }

    @Test
    fun `empty refresh retires coverage when no source evidence remains`() =
        runBlocking {
            seedWarmMinute(0L)
            database.sourceRecordDao().deleteBySourceRecordId("source-b")
            writer.replaceHeartRateSources(listOf(payload(emptyList())))
            assertTrue(visibleAverages().isEmpty())
            assertTrue(database.minuteCoverageDao().getCoverageInRange(0L, 60_000L).isEmpty())
            assertTrue(database.minuteCoverageDao().getContributionsForMinute(0L).isEmpty())
        }

    @Test
    fun `retention removes expired coverage and all evidence generations at the bucket cutoff`() =
        runBlocking {
            seedWarmMinute(0L)
            seedWarmMinute(60_000L)
            seedWarmMinute(120_000L)
            val oldContribution = database.minuteCoverageDao().getContributionsForMinute(0L).first()
            database.minuteCoverageDao().upsertContributions(listOf(oldContribution.copy(generation = 99L)))
            RetentionCleanup(
                coordinator = TestHealthMutationCoordinator,
                transactionRunner = RoomTransactionRunner(database),
                daos = daos,
                dailySummaryDao = database.dailySummaryDao(),
                vo2MaxRecordDao = database.vo2MaxRecordDao(),
            ).deleteBefore(
                120_000L,
                ScoringRunContext.capture(UserPreferences(), Instant.parse("2026-08-31T12:00:00Z")),
            )
            assertTrue(database.minuteCoverageDao().getContributionsForMinute(0L).isEmpty())
            assertEquals(
                listOf(60_000L, 120_000L),
                database.minuteCoverageDao().getCoverageInRange(0L, 180_000L).map { it.bucketStartMs },
            )
            assertTrue(database.minuteCoverageDao().getContributionsForMinute(60_000L).isNotEmpty())
        }

    @Test
    fun `source selection cannot be undone by warm relinking`() =
        runBlocking {
            seedWarmMinute(0L)
            SelectedSourcePrunerImpl(RoomTransactionRunner(database), daos).prune(
                LocalDate.ofEpochDay(0),
                LocalDate.ofEpochDay(0),
                mapOf(HealthDataType.HEART_RATE to "Device B"),
                ZoneOffset.UTC,
            )
            relinker.relink(0L, 59_999L, emptyList(), emptyList())
            assertEquals(
                listOf("Device B"),
                database.minuteBucketDao().getVisibleBucketsInMinuteRange(0L, 60_000L).map { it.deviceName },
            )
            assertEquals(
                setOf("Device B"),
                database
                    .minuteCoverageDao()
                    .getContributionsForMinute(0L)
                    .map { it.deviceName }
                    .toSet(),
            )
        }

    @Test
    fun `refresh abort rolls back source metadata generation dirty work and visible evidence`() =
        runBlocking {
            seedWarmMinute(0L)
            val before = database.minuteBucketDao().getVisibleBucketsInMinuteRange(0L, 60_000L)
            val oldSource = database.sourceRecordDao().getBySourceRecordId("source-a")
            val oldState = database.healthMutationStateDao().current()
            val oldDirty = database.dirtyRangeDao().pending(100)
            val aborting =
                object : TransactionRunner {
                    override suspend fun <R> runInTransaction(block: suspend () -> R): R =
                        RoomTransactionRunner(database).runInTransaction {
                            block()
                            error("interrupted before commit")
                        }
                }
            assertFailsWith<IllegalStateException> {
                createWriter(aborting).replaceHeartRateSources(listOf(payload(listOf(140, 160))))
            }
            assertEquals(before, database.minuteBucketDao().getVisibleBucketsInMinuteRange(0L, 60_000L))
            assertEquals(oldSource, database.sourceRecordDao().getBySourceRecordId("source-a"))
            assertEquals(oldState, database.healthMutationStateDao().current())
            assertEquals(oldDirty, database.dirtyRangeDao().pending(100))
            assertEquals(0, database.heartRateDao().count())
        }

    @Test
    fun `moving source samples removes the previous warm contribution`() =
        runBlocking {
            seedWarmMinute(0L)
            val moved =
                payload(listOf(150)).let {
                    it.copy(
                        source = it.source.copy(startMs = 60_000L, endExclusiveMs = 120_000L),
                        rows = it.rows.map { row -> row.copy(timestampMs = 61_000L) },
                    )
                }
            writer.replaceHeartRateSources(listOf(moved))
            assertEquals(listOf(70.0), visibleAverages())
            val visible =
                AuthoritativeHeartRateReader(
                    database.heartRateDao(),
                    database.minuteBucketDao(),
                    database.minuteCoverageSelectionDao(),
                ).rangeIn(61_000L, 119_999L)
            assertEquals(listOf(150), visible.rawSamples.map { it.beatsPerMinute })
            assertTrue(visible.warmBuckets.isEmpty())
        }

    private fun createWriter(runner: TransactionRunner) =
        SourcePayloadWriter(
            daos,
            runner,
            RoomDirtyRangeStore(database.dirtyRangeDao(), database.healthMutationStateDao()),
            database.healthMutationStateDao(),
            warmRefresh =
                SourceHeartRateRefresh(
                    daos,
                    database.minuteCoverageDao(),
                    database.minuteBucketDao(),
                    publisher,
                    database.healthMutationStateDao(),
                ),
        )

    private suspend fun visibleAverages() =
        database
            .minuteBucketDao()
            .getVisibleBucketsInMinuteRange(0L, 60_000L)
            .map { it.avgBpm }
            .sorted()

    private fun payload(bpms: List<Int>) =
        SourcePayload(
            SourceMetadata("source-a", "HEART_RATE", "origin", 0L, 60_000L, 2L),
            bpms.mapIndexed { index, bpm ->
                HeartRateInput(
                    "source-a",
                    1_000L + index * 10_000L,
                    bpm,
                    "RESTING",
                    null,
                    "Device A",
                )
            },
        )

    private suspend fun seedWarmMinute(minute: Long) {
        val a = database.sourceRecordDao().getOrCreateSourceRef("source-a", "HEART_RATE", 0L)
        val b = database.sourceRecordDao().getOrCreateSourceRef("source-b", "HEART_RATE", 0L)
        database.heartRateDao().upsertAll(
            listOf(
                HeartRateRecordEntity(a, minute + 1_000L, 90, "RESTING", deviceName = "Device A"),
                HeartRateRecordEntity(a, minute + 11_000L, 110, "RESTING", deviceName = "Device A"),
                HeartRateRecordEntity(b, minute + 1_000L, 70, "RESTING", deviceName = "Device B"),
            ),
        )
        DataRollupManager(
            coordinator = TestHealthMutationCoordinator,
            minuteCoverageDao = database.minuteCoverageDao(),
            heartRateDao = database.heartRateDao(),
            publisher = publisher,
            transactionRunner = RoomTransactionRunner(database),
            dirtyRangeDao = database.dirtyRangeDao(),
            healthMutationStateDao = database.healthMutationStateDao(),
        ).rollupExpiredHotTier(app.readylytics.health.core.model.domain.sync.ScoringRunContext.capture(app.readylytics.health.core.model.domain.preferences.UserPreferences(), java.time.Instant.ofEpochMilli(minute + 60_000L)), minute + 60_000L)
    }
}
