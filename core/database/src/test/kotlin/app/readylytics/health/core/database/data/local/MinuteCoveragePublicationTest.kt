package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.dao.getOrCreateSourceRef
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrSourceMinuteContributionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.MinuteCoverageEntity
import app.readylytics.health.core.databaseschema.data.local.entity.StagedHeartRateEntity
import app.readylytics.health.core.databaseschema.data.local.entity.StagedSourceMetadataEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * WP-17 Step 1/3/4 publication fixture. Uses a FILE-BACKED Room database (not in-memory) so the
 * fault-boundary case can genuinely close and reopen the database after an aborted publication and
 * assert that the previously visible generation survived on disk.
 */
@RunWith(RobolectricTestRunner::class)
class MinuteCoveragePublicationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var databaseFile: String
    private lateinit var database: HealthDatabase
    private lateinit var publisher: MinuteCoveragePublisher
    private lateinit var stagingStore: HeartRateRefreshStagingStore

    @Before
    fun setup() {
        databaseFile = tempFolder.newFile("publication-test.db").absolutePath
        openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun openDatabase() {
        database =
            Room
                .databaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                    databaseFile,
                ).addMigrations(*DatabaseMigrations.all)
                .allowMainThreadQueries()
                .build()
        publisher = MinuteCoveragePublisher(database.minuteBucketDao(), database.minuteCoverageDao())
        stagingStore =
            HeartRateRefreshStagingStore(
                database.heartRateRefreshStagingDao(),
                RoomTransactionRunner(database),
            )
    }

    // I6: `stage` must persist the caller's completeness marker, not forge `true`. The complete
    // marker is still only written after all children land, which is what the two-write sequence
    // inside one transaction buys.
    @Test
    fun `staging persists rows under runId and sourceId and honours payloadComplete`() =
        runBlocking {
            val samples =
                listOf(
                    StagedHeartRateEntity(RUN_ID, SOURCE_ID, 1_000L, 60, "RESTING", null, "device"),
                    StagedHeartRateEntity(RUN_ID, SOURCE_ID, 2_000L, 62, "RESTING", null, "device"),
                )

            stagingStore.stage(RUN_ID, SOURCE_ID, stagedMetadata(payloadComplete = false), samples)

            val incomplete = stagingStore.stagedMetadata(RUN_ID, SOURCE_ID)
            assertNotNull(incomplete)
            assertFalse("Caller's incomplete payload must stay incomplete", incomplete!!.payloadComplete)
            assertEquals(2, stagingStore.stagedSamples(RUN_ID, SOURCE_ID).size)

            stagingStore.stage(RUN_ID, SOURCE_ID, stagedMetadata(payloadComplete = true), samples)
            assertTrue(stagingStore.stagedMetadata(RUN_ID, SOURCE_ID)!!.payloadComplete)

            // Empty complete parent retains its metadata row and replaces the staged children.
            stagingStore.stage(RUN_ID, SOURCE_ID, stagedMetadata(payloadComplete = true), emptyList())
            assertTrue(stagingStore.stagedMetadata(RUN_ID, SOURCE_ID)!!.payloadComplete)
            assertEquals(0, stagingStore.stagedSamples(RUN_ID, SOURCE_ID).size)

            stagingStore.clearRun(RUN_ID)
            assertNull(stagingStore.stagedMetadata(RUN_ID, SOURCE_ID))
            assertEquals(0, stagingStore.stagedSamples(RUN_ID, SOURCE_ID).size)
        }

    @Test
    fun `successful switch yields exactly one tier and the expected count and weighted mean`() =
        runBlocking {
            val sourceRefs = seedSources()

            publishGeneration(sourceRefs, generation = 1L)

            val coverage = database.minuteCoverageDao().getCoverageInRange(0L, MINUTE_MS)
            assertEquals(1, coverage.size)
            assertEquals(setOf(TIER_WARM), coverage.map { it.tier }.toSet())
            assertEquals(QUALITY_SOURCE_BACKED, coverage.single().quality)
            assertEquals(1L, coverage.single().visibleGeneration)

            // Two device slices of the same minute: 2 samples @60 and 2 @90 -> mean 75, count 4.
            val rows = database.minuteBucketDao().getMinuteBuckets(0L, MINUTE_MS)
            assertEquals(1, rows.size)
            assertEquals(4, rows.single().sampleCount)
            assertEquals(75.0, rows.single().avgBpm, 0.001)

            val contributions = database.minuteCoverageDao().getContributionsForMinute(0L)
            assertEquals(2, contributions.size)
            assertEquals(setOf(1L), contributions.map { it.generation }.toSet())
        }

    // C5 + brief Step 1: an identical second switch changes nothing -- same single coverage row at
    // the same generation, and exactly one generation's contributions and bucket slices.
    @Test
    fun `identical second switch is a no-op`() =
        runBlocking {
            val sourceRefs = seedSources()
            publishGeneration(sourceRefs, generation = 1L)

            val coverageBefore = database.minuteCoverageDao().getCoverageInRange(0L, MINUTE_MS)
            val contributionsBefore = database.minuteCoverageDao().getContributionsForMinute(0L)
            val bucketsBefore = database.minuteBucketDao().getBucketsInTimeRange(0L, MINUTE_MS)

            publishGeneration(sourceRefs, generation = 1L)

            assertEquals(coverageBefore, database.minuteCoverageDao().getCoverageInRange(0L, MINUTE_MS))
            assertEquals(contributionsBefore, database.minuteCoverageDao().getContributionsForMinute(0L))
            assertEquals(bucketsBefore, database.minuteBucketDao().getBucketsInTimeRange(0L, MINUTE_MS))
            assertEquals(2, contributionsBefore.size)
            assertEquals(2, bucketsBefore.size)
        }

    // C5: re-publishing the same minute at a NEW generation replaces the superseded evidence
    // instead of appending a second generation's rows next to it.
    @Test
    fun `republishing the same minute supersedes the previous generation`() =
        runBlocking {
            val sourceRefs = seedSources()
            publishGeneration(sourceRefs, generation = 1L)
            publishGeneration(sourceRefs, generation = 2L)

            val contributions = database.minuteCoverageDao().getContributionsForMinute(0L)
            assertEquals(2, contributions.size)
            assertEquals(setOf(2L), contributions.map { it.generation }.toSet())

            val buckets = database.minuteBucketDao().getBucketsInTimeRange(0L, MINUTE_MS)
            assertEquals(2, buckets.size)
            assertEquals(setOf(2L), buckets.map { it.generation }.toSet())
            assertEquals(2L, database.minuteCoverageDao().getCoverageInRange(0L, MINUTE_MS).single().visibleGeneration)
        }

    // Brief Step 1's central assertion: stage corrected values, throw before the visibility switch
    // commits, reopen the database and assert the OLD visible data is still there.
    @Test
    fun `throwing before the visibility switch commits leaves the old visible data intact`() =
        runBlocking {
            val sourceRefs = seedSources()
            publishGeneration(sourceRefs, generation = 1L)

            val runner = RoomTransactionRunner(database)
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    runBlocking {
                        runner.runInTransaction {
                            publisher.publish(publicationRequest(sourceRefs, generation = 9L, avgBpm = 200.0))
                            error("simulated failure before the visibility switch commits")
                        }
                    }
                }
            assertEquals("simulated failure before the visibility switch commits", failure.message)

            database.close()
            openDatabase()

            val coverage = database.minuteCoverageDao().getCoverageInRange(0L, MINUTE_MS)
            assertEquals(1, coverage.size)
            assertEquals(1L, coverage.single().visibleGeneration)
            assertEquals(QUALITY_SOURCE_BACKED, coverage.single().quality)

            val contributions = database.minuteCoverageDao().getContributionsForMinute(0L)
            assertEquals(2, contributions.size)
            assertEquals(setOf(1L), contributions.map { it.generation }.toSet())

            val rows = database.minuteBucketDao().getMinuteBuckets(0L, MINUTE_MS)
            assertEquals(75.0, rows.single().avgBpm, 0.001)
        }

    // Step 3: the captured source generation is re-checked before commit.
    @Test
    fun `publication aborts when the source generation moved while the unit was assembled`() =
        runBlocking {
            val sourceRefs = seedSources()
            val stateDao = database.healthMutationStateDao()
            stateDao.getOrCreate()
            val guardedPublisher =
                MinuteCoveragePublisher(
                    minuteBucketDao = database.minuteBucketDao(),
                    minuteCoverageDao = database.minuteCoverageDao(),
                    healthMutationStateDao = stateDao,
                )

            stateDao.incrementGeneration()

            val conflict =
                assertThrows(SourceGenerationConflictException::class.java) {
                    runBlocking {
                        guardedPublisher.publish(publicationRequest(sourceRefs, generation = 0L))
                    }
                }
            assertEquals(0L, conflict.capturedGeneration)
            assertEquals(1L, conflict.currentGeneration)
            assertEquals(0, database.minuteCoverageDao().getCoverageInRange(0L, MINUTE_MS).size)
        }

    // C4/OD-1: a minute whose visible coverage is still legacy/approximate is quarantined, never
    // replaced or concatenated by an ordinary publication.
    @Test
    fun `legacy coverage minutes are quarantined instead of republished`() =
        runBlocking {
            val sourceRefs = seedSources()
            database.minuteCoverageDao().upsertCoverage(
                listOf(
                    MinuteCoverageEntity(
                        bucketStartMs = 0L,
                        visibleGeneration = 0L,
                        tier = "LEGACY_WARM",
                        quality = QUALITY_LEGACY_UNKNOWN,
                        sourceSelectionId = null,
                    ),
                ),
            )

            val outcome =
                RoomTransactionRunner(database).runInTransaction {
                    publisher.publish(publicationRequest(sourceRefs, generation = 5L))
                }

            assertEquals(emptySet<Long>(), outcome.publishedMinutes)
            assertEquals(setOf(0L), outcome.quarantinedMinutes)
            val coverage = database.minuteCoverageDao().getCoverageInRange(0L, MINUTE_MS).single()
            assertEquals(QUALITY_LEGACY_UNKNOWN, coverage.quality)
            assertEquals(0L, coverage.visibleGeneration)
            assertEquals(0, database.minuteCoverageDao().getContributionsForMinute(0L).size)
            assertEquals(0, database.minuteBucketDao().getBucketsInTimeRange(0L, MINUTE_MS).size)
        }

    /** Two distinct source records, one per device slice -- contributions RESTRICT on this FK. */
    private suspend fun seedSources(): Pair<Long, Long> {
        val dao = database.sourceRecordDao()
        return dao.getOrCreateSourceRef("src-a", "HEART_RATE", 0L) to
            dao.getOrCreateSourceRef("src-b", "HEART_RATE", 0L)
    }

    private suspend fun publishGeneration(
        sourceRefs: Pair<Long, Long>,
        generation: Long,
    ) = RoomTransactionRunner(database).runInTransaction {
        publisher.publish(publicationRequest(sourceRefs, generation))
    }

    private fun publicationRequest(
        sourceRefs: Pair<Long, Long>,
        generation: Long,
        avgBpm: Double = 60.0,
    ) = MinutePublicationRequest(
        rangeStartMs = 0L,
        rangeEndExclusiveMs = MINUTE_MS,
        capturedGeneration = generation,
        coverage =
            listOf(
                MinuteCoverageEntity(
                    bucketStartMs = 0L,
                    visibleGeneration = generation,
                    tier = TIER_WARM,
                    quality = QUALITY_SOURCE_BACKED,
                    sourceSelectionId = "sel-1",
                ),
            ),
        contributions =
            listOf(
                contribution(sourceRefs.first, generation, "dev-a", "v1:60:2"),
                contribution(sourceRefs.second, generation, "dev-b", "v1:90:2"),
            ),
        buckets =
            listOf(
                bucket(generation, "dev-a", avgBpm),
                bucket(generation, "dev-b", avgBpm + 30.0),
            ),
    )

    private fun contribution(
        sourceRef: Long,
        generation: Long,
        deviceName: String,
        histogram: String,
    ) = HrSourceMinuteContributionEntity(
        sourceRecordRef = sourceRef,
        bucketStartMs = 0L,
        generation = generation,
        firstSampleMs = 1_000L,
        lastSampleMs = 5_000L,
        deviceName = deviceName,
        bpmHistogram = histogram,
    )

    private fun bucket(
        generation: Long,
        deviceName: String,
        avgBpm: Double,
    ) = HrMinuteBucketEntity(
        bucketStartMs = 0L,
        bucketEndMs = MINUTE_MS,
        minBpm = avgBpm.toInt(),
        maxBpm = avgBpm.toInt(),
        avgBpm = avgBpm,
        sampleCount = 2,
        recordType = "RESTING",
        deviceName = deviceName,
        generation = generation,
    )

    private fun stagedMetadata(payloadComplete: Boolean) =
        StagedSourceMetadataEntity(
            runId = RUN_ID,
            sourceId = SOURCE_ID,
            recordType = "HEART_RATE",
            originPackage = "pkg",
            startMs = 0L,
            endExclusiveMs = MINUTE_MS,
            lastModifiedMs = 0L,
            payloadComplete = payloadComplete,
        )

    private companion object {
        const val MINUTE_MS = 60_000L
        const val RUN_ID = "run-1"
        const val SOURCE_ID = "staged-src-1"
    }
}
