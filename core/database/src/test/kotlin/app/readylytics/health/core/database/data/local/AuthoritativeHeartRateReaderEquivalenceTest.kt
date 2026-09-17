package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.database.data.repository.HeartRateRepositoryImpl
import app.readylytics.health.core.database.data.repository.ScoringHeartRateDataLoader
import app.readylytics.health.core.database.data.repository.ScoringHistoryRepositoryImpl
import app.readylytics.health.core.databaseschema.data.local.dao.getOrCreateSourceRef
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.databaseschema.data.local.entity.MinuteCoverageEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.model.domain.model.HrMinuteBucketRow
import app.readylytics.health.core.model.domain.repository.HeartRateResolution
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * WP-17 Step 1/Step 3 cross-reader equivalence, against a real Room database.
 *
 * Every case asserts three things at once:
 * 1. every reader agrees on **sample count** and **weighted mean** for the same window;
 * 2. exactly **one generation** of exactly **one tier** backs each minute -- never raw *and* warm;
 * 3. the answer matches a **reference** derived from the immutable stored contribution histograms
 *    (or, for a legacy minute, from its own preserved approximate projection), computed in Kotlin
 *    independently of the production SQL selection.
 *
 * Integer identities and counts are compared exactly; `Float`/`Double` results carry the documented
 * 1e-4 numerical-accumulation tolerance. The separate hot-versus-warm *approximation* error (a
 * different thing from accumulation noise) is measured in `WarmTierRelinkTest`.
 */
@RunWith(RobolectricTestRunner::class)
class AuthoritativeHeartRateReaderEquivalenceTest {
    private lateinit var database: HealthDatabase
    private lateinit var rollupManager: DataRollupManager
    private lateinit var reader: AuthoritativeHeartRateReader

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        runBlocking { database.healthMutationStateDao().getOrCreate() }
        rollupManager =
            DataRollupManager(
                minuteCoverageDao = database.minuteCoverageDao(),
                heartRateDao = database.heartRateDao(),
                publisher =
                    MinuteCoveragePublisher(
                        minuteBucketDao = database.minuteBucketDao(),
                        minuteCoverageDao = database.minuteCoverageDao(),
                        dirtyRangeDao = database.dirtyRangeDao(),
                        healthMutationStateDao = database.healthMutationStateDao(),
                    ),
                transactionRunner = RoomTransactionRunner(database),
                dirtyRangeDao = database.dirtyRangeDao(),
                healthMutationStateDao = database.healthMutationStateDao(),
            )
        reader =
            AuthoritativeHeartRateReader(
                heartRateDao = database.heartRateDao(),
                minuteBucketDao = database.minuteBucketDao(),
                coverageSelectionDao = database.minuteCoverageSelectionDao(),
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `raw-only minute is served from the hot tier by every reader`() =
        runBlocking {
            val ref = seedSource("src-a")
            database.heartRateDao().upsertAll(listOf(hr(ref, 1_000L, 60), hr(ref, 2_000L, 62)))

            val range = reader.rangeIn(0L, MINUTE_MS - 1)
            assertEquals(2, range.rawSamples.size)
            assertTrue(range.warmBuckets.isEmpty())
            assertEquals(HeartRateResolution.RAW, range.resolution)

            assertMinuteProjection(expectedCount = 2, expectedMean = 61.0)
            assertCrossReaderAgreement()
        }

    @Test
    fun `warm-only minute is served from exactly one generation by every reader`() =
        runBlocking {
            val ref = seedSource("src-a")
            database.heartRateDao().upsertAll(listOf(hr(ref, 1_000L, 60), hr(ref, 2_000L, 62)))
            rollupManager.rollupExpiredHotTier(MINUTE_MS)

            val range = reader.rangeIn(0L, MINUTE_MS - 1)
            assertTrue("Rolled-up raw rows must not resurface", range.rawSamples.isEmpty())
            assertEquals(1, range.warmBuckets.size)
            assertEquals(HeartRateResolution.RECONSTRUCTED, range.resolution)

            val coverage = database.minuteCoverageDao().getCoverageInRange(0L, MINUTE_MS).single()
            assertEquals(setOf(coverage.visibleGeneration), range.warmBuckets.map { it.generation }.toSet())

            assertMinuteProjection(expectedCount = 2, expectedMean = 61.0)
            assertCrossReaderAgreement()
        }

    // This is the gap T2 explicitly deferred to T3. Under the OD-1 quarantine rule a legacy minute
    // keeps BOTH its approximate warm projection AND its raw rows below the hot/warm cutoff. Before
    // the visibility predicate every reader summed the two, inflating that minute's count (11
    // instead of 10) and dragging its mean. It must now resolve to the warm projection alone.
    @Test
    fun `legacy overlap minute never concatenates its quarantined raw rows`() =
        runBlocking {
            val ref = seedSource("src-a")
            seedLegacyMinute(bucketStartMs = 0L, sampleCount = 10, avgBpm = 60.0)
            database.heartRateDao().upsertAll(listOf(hr(ref, 1_000L, 200)))

            rollupManager.rollupExpiredHotTier(MINUTE_MS)

            // Precondition: the quarantine really did leave a raw row behind under the cutoff.
            assertEquals(1, database.heartRateDao().countInRange(0L, MINUTE_MS - 1))

            val range = reader.rangeIn(0L, MINUTE_MS - 1)
            assertTrue("Quarantined raw rows must stay invisible", range.rawSamples.isEmpty())
            assertEquals(1, range.warmBuckets.size)

            assertMinuteProjection(expectedCount = 10, expectedMean = 60.0)
            assertCrossReaderAgreement()
        }

    @Test
    fun `mixed boundary day splits minutes across tiers without double counting`() =
        runBlocking {
            val ref = seedSource("src-a")
            database.heartRateDao().upsertAll(
                listOf(
                    hr(ref, 1_000L, 60),
                    hr(ref, 30_000L, 62),
                    hr(ref, 65_000L, 80),
                    hr(ref, 95_000L, 84),
                ),
            )
            // Cutoff inside minute 1: minute 0 rolls up, minute 1 stays raw.
            rollupManager.rollupExpiredHotTier(90_000L)

            val range = reader.rangeIn(0L, 2 * MINUTE_MS - 1)
            assertEquals(2, range.rawSamples.size)
            assertEquals(1, range.warmBuckets.size)
            assertEquals(0L, range.warmBuckets.single().bucketStartMs)

            val projection = reader.minuteBuckets(0L, 2 * MINUTE_MS)
            assertEquals(listOf(0, 1), projection.map { it.bucketIndex })
            assertEquals(4, projection.sumOf { it.sampleCount })
            assertEquals(61.0, projection.first { it.bucketIndex == 0 }.avgBpm, TOLERANCE)
            assertEquals(82.0, projection.first { it.bucketIndex == 1 }.avgBpm, TOLERANCE)
            assertCrossReaderAgreement(endExclusiveMs = 2 * MINUTE_MS)
        }

    // A crash between "insert the new generation's buckets" and "flip visibleGeneration" can leave
    // a superseded slice behind. It must stay invisible rather than be summed into the minute.
    @Test
    fun `superseded generation bucket rows stay invisible`() =
        runBlocking {
            val ref = seedSource("src-a")
            database.heartRateDao().upsertAll(listOf(hr(ref, 1_000L, 60), hr(ref, 2_000L, 62)))
            rollupManager.rollupExpiredHotTier(MINUTE_MS)
            val visibleGeneration =
                database.minuteCoverageDao().getCoverageInRange(0L, MINUTE_MS).single().visibleGeneration

            // A stale slice at an older generation, on a key the current publication does not own.
            database.minuteBucketDao().upsertBuckets(
                listOf(
                    HrMinuteBucketEntity(
                        bucketStartMs = 0L,
                        bucketEndMs = MINUTE_MS,
                        minBpm = 150,
                        maxBpm = 150,
                        avgBpm = 150.0,
                        sampleCount = 40,
                        recordType = "RESTING",
                        sessionId = "",
                        deviceName = "stale-device",
                        generation = visibleGeneration - 1,
                    ),
                ),
            )

            val range = reader.rangeIn(0L, MINUTE_MS - 1)
            assertEquals(1, range.warmBuckets.size)
            assertEquals(setOf(visibleGeneration), range.warmBuckets.map { it.generation }.toSet())
            assertMinuteProjection(expectedCount = 2, expectedMean = 61.0)
            assertCrossReaderAgreement()
        }

    @Test
    fun `sleep projection and min bpm agree across tiers for a rolled-up session`() =
        runBlocking {
            val ref = seedSource("src-a")
            database.sleepSessionDao().upsertAll(listOf(sleepSession("sleep-1", 0L, 2 * MINUTE_MS - 1)))
            database.heartRateDao().upsertAll(
                listOf(
                    sleepHr(ref, 1_000L, 50, "sleep-1"),
                    sleepHr(ref, 2_000L, 54, "sleep-1"),
                    sleepHr(ref, 65_000L, 58, "sleep-1"),
                ),
            )
            // Only minute 0 rolls up; minute 1 stays raw. Both must still be counted exactly once.
            rollupManager.rollupExpiredHotTier(MINUTE_MS)

            val projection = reader.sleepProjectionForSessions(listOf("sleep-1"))
            assertEquals(3, projection.size)
            assertEquals(3, reader.sleepSamplesForSession("sleep-1").size)

            val repository =
                ScoringHistoryRepositoryImpl(
                    heartRateDao = database.heartRateDao(),
                    hrvDao = database.hrvDao(),
                    sleepSessionDao = database.sleepSessionDao(),
                    dailySummaryDao = database.dailySummaryDao(),
                    authoritativeReader = reader,
                )
            assertEquals(3, repository.getSleepHrProjectionForSessions(listOf("sleep-1")).size)
            assertEquals(
                projection.map { it.beatsPerMinute }.average(),
                repository.getAvgSleepHrForSessions(listOf("sleep-1")).getValue("sleep-1").toDouble(),
                1.0,
            )
            // 50 is the warm bucket's stored minBpm, so the minimum survives the rollup exactly.
            assertEquals(50, reader.minBpmInRange(0L, 2 * MINUTE_MS - 1))
        }

    @Test
    fun `legacy minutes are reported as unresolved rather than silently repaired`() =
        runBlocking {
            seedLegacyMinute(bucketStartMs = 0L, sampleCount = 10, avgBpm = 60.0)
            val ref = seedSource("src-a")
            database.heartRateDao().upsertAll(listOf(hr(ref, 65_000L, 70)))
            rollupManager.rollupExpiredHotTier(2 * MINUTE_MS)

            assertEquals(1, reader.legacyApproximateMinutes(0L, 2 * MINUTE_MS))
        }

    /**
     * Every minute in `[0, endExclusiveMs)` must resolve to exactly one tier, and the per-minute
     * projection the reader serves must equal the reference computed here from the *stored
     * evidence* -- decoded contribution histograms for a source-backed minute, the preserved
     * approximate bucket for a legacy one -- without consulting any visibility SQL.
     */
    private suspend fun assertCrossReaderAgreement(endExclusiveMs: Long = MINUTE_MS) {
        val reference = referenceProjection(endExclusiveMs)
        val fromReader = reader.minuteBuckets(0L, endExclusiveMs)
        assertEquals(reference.map { it.bucketIndex }, fromReader.map { it.bucketIndex })
        assertEquals(reference.map { it.sampleCount }, fromReader.map { it.sampleCount })
        reference.zip(fromReader).forEach { (expected, actual) ->
            assertEquals(
                "mean mismatch at bucket ${expected.bucketIndex}",
                expected.avgBpm,
                actual.avgBpm,
                TOLERANCE,
            )
        }

        // The scoring loader is a separate consumer; with the shared predicate it must not differ.
        val scoringLoader = ScoringHeartRateDataLoader(reader)
        assertEquals(fromReader, scoringLoader.loadMergedMinuteBuckets(0L, endExclusiveMs))

        // The chart/repository consumer must see the same raw multiset the reader selected.
        val repository = HeartRateRepositoryImpl(database.heartRateDao(), database.hrvDao(), reader)
        assertEquals(
            reader.rangeIn(0L, endExclusiveMs - 1).rawSamples.map { it.beatsPerMinute },
            repository.getByTimeRange(0L, endExclusiveMs - 1).map { it.beatsPerMinute },
        )
    }

    private suspend fun assertMinuteProjection(
        expectedCount: Int,
        expectedMean: Double,
    ) {
        val row = reader.minuteBuckets(0L, MINUTE_MS).single()
        assertEquals(expectedCount, row.sampleCount)
        assertEquals(expectedMean, row.avgBpm, TOLERANCE)
    }

    private suspend fun referenceProjection(endExclusiveMs: Long): List<HrMinuteBucketRow> {
        val rows = mutableListOf<HrMinuteBucketRow>()
        var bucketStartMs = 0L
        while (bucketStartMs < endExclusiveMs) {
            val coverage =
                database
                    .minuteCoverageDao()
                    .getCoverageInRange(bucketStartMs, bucketStartMs + MINUTE_MS)
                    .singleOrNull()
            val index = ((bucketStartMs) / MINUTE_MS).toInt()
            when {
                coverage == null -> rawReference(bucketStartMs, index)?.let(rows::add)
                coverage.quality == QUALITY_SOURCE_BACKED ->
                    contributionReference(bucketStartMs, index, coverage.visibleGeneration)?.let(rows::add)
                else -> legacyReference(bucketStartMs, index)?.let(rows::add)
            }
            bucketStartMs += MINUTE_MS
        }
        return rows
    }

    private suspend fun rawReference(
        bucketStartMs: Long,
        index: Int,
    ): HrMinuteBucketRow? {
        val samples =
            database
                .heartRateDao()
                .getByTimeRange(bucketStartMs, bucketStartMs + MINUTE_MS - 1)
                .map { it.beatsPerMinute }
                .filter { it in PLAUSIBLE }
        if (samples.isEmpty()) return null
        return HrMinuteBucketRow(index, samples.average(), samples.size)
    }

    private suspend fun contributionReference(
        bucketStartMs: Long,
        index: Int,
        visibleGeneration: Long,
    ): HrMinuteBucketRow? {
        val histograms =
            database
                .minuteCoverageDao()
                .getContributionsForMinute(bucketStartMs)
                .filter { it.generation == visibleGeneration }
                .map { BpmHistogram.decode(it.bpmHistogram) }
        val count = histograms.sumOf { it.count }
        if (count == 0) return null
        val sum = histograms.sumOf { it.sum }
        return HrMinuteBucketRow(index, sum.toDouble() / count, count)
    }

    private suspend fun legacyReference(
        bucketStartMs: Long,
        index: Int,
    ): HrMinuteBucketRow? {
        val buckets =
            database
                .minuteBucketDao()
                .getBucketsInTimeRange(bucketStartMs, bucketStartMs)
                .filter { it.bucketStartMs == bucketStartMs && it.avgBpm in PLAUSIBLE_AVG }
        if (buckets.isEmpty()) return null
        val count = buckets.sumOf { it.sampleCount }
        return HrMinuteBucketRow(index, buckets.sumOf { it.avgBpm * it.sampleCount } / count, count)
    }

    private suspend fun seedSource(id: String): Long =
        database.sourceRecordDao().getOrCreateSourceRef(id, "HEART_RATE", 0L)

    /** A pre-v22 minute: an approximate warm bucket plus legacy coverage, at generation 0. */
    private suspend fun seedLegacyMinute(
        bucketStartMs: Long,
        sampleCount: Int,
        avgBpm: Double,
    ) {
        database.minuteBucketDao().upsertBuckets(
            listOf(
                HrMinuteBucketEntity(
                    bucketStartMs = bucketStartMs,
                    bucketEndMs = bucketStartMs + MINUTE_MS,
                    minBpm = 50,
                    maxBpm = 70,
                    avgBpm = avgBpm,
                    sampleCount = sampleCount,
                    recordType = "RESTING",
                    deviceName = "legacy-device",
                ),
            ),
        )
        database.minuteCoverageDao().upsertCoverage(
            listOf(
                MinuteCoverageEntity(
                    bucketStartMs = bucketStartMs,
                    visibleGeneration = 0L,
                    tier = "LEGACY_WARM",
                    quality = QUALITY_LEGACY_UNKNOWN,
                    sourceSelectionId = null,
                ),
            ),
        )
    }

    private fun hr(
        ref: Long,
        timestampMs: Long,
        bpm: Int,
    ) = HeartRateRecordEntity(
        sourceRecordRef = ref,
        timestampMs = timestampMs,
        beatsPerMinute = bpm,
        recordType = "RESTING",
        sessionId = null,
    )

    private fun sleepHr(
        ref: Long,
        timestampMs: Long,
        bpm: Int,
        sessionId: String,
    ) = HeartRateRecordEntity(
        sourceRecordRef = ref,
        timestampMs = timestampMs,
        beatsPerMinute = bpm,
        recordType = "SLEEP",
        sessionId = sessionId,
    )

    private fun sleepSession(
        id: String,
        startTime: Long,
        endTime: Long,
    ) = SleepSessionEntity(
        id = id,
        startTime = startTime,
        endTime = endTime,
        durationMinutes = 0,
        efficiency = 0f,
        deepSleepMinutes = 0,
        remSleepMinutes = 0,
        lightSleepMinutes = 0,
        awakeMinutes = 0,
    )

    private companion object {
        const val MINUTE_MS = 60_000L

        /** Documented numerical-accumulation tolerance for Float/Double results (Step 1). */
        const val TOLERANCE = 1e-4

        val PLAUSIBLE = 30..230
        val PLAUSIBLE_AVG = 30.0..230.0
    }
}
