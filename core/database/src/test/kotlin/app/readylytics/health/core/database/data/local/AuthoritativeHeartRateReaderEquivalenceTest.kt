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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
                coordinator = TestHealthMutationCoordinator,
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

    // Finding 2 (final review): the session-scoped mirror of the test above. Before the fix,
    // `AuthoritativeHeartRateReader.observeSleepSession` paired an UNFILTERED raw session read with
    // the tier-visibility-filtered warm session read, so this exact overlap state doubled the
    // minute's sample count on the sleep-HR chart instead of resolving to one tier.
    @Test
    fun `legacy overlap minute never concatenates its quarantined raw rows into a sleep session`() =
        runBlocking {
            val ref = seedSource("src-a")
            database.sleepSessionDao().upsertAll(listOf(sleepSession("sleep-1", 0L, MINUTE_MS - 1)))
            seedLegacyMinute(
                bucketStartMs = 0L,
                sampleCount = 10,
                avgBpm = 60.0,
                recordType = "SLEEP",
                sessionId = "sleep-1",
            )
            database.heartRateDao().upsertAll(listOf(sleepHr(ref, 1_000L, 200, "sleep-1")))

            rollupManager.rollupExpiredHotTier(MINUTE_MS)

            // Precondition: the quarantine really did leave a raw row behind under the cutoff.
            assertEquals(1, database.heartRateDao().countInRange(0L, MINUTE_MS - 1))

            val range = reader.observeSleepSession("sleep-1").first()
            assertTrue("Quarantined raw rows must stay invisible", range.rawSamples.isEmpty())
            assertEquals(1, range.warmBuckets.size)

            val merged = range.mergedSamples()
            assertEquals("the minute must resolve to exactly one tier's evidence", 10, merged.size)
            assertTrue(
                "the quarantined raw sample must stay hidden, not concatenated",
                merged.none { it.beatsPerMinute == 200 },
            )
            assertEquals(60.0, merged.map { it.beatsPerMinute }.average(), TOLERANCE)
        }

    @Test
    fun `sleep session observation refreshes when a visible warm bucket changes without raw rows`() =
        runBlocking {
            seedLegacyMinute(
                bucketStartMs = 0L,
                sampleCount = 2,
                avgBpm = 60.0,
                recordType = "SLEEP",
                sessionId = "sleep-1",
            )
            val emissions = Channel<AuthoritativeHrRange>(Channel.UNLIMITED)
            val collection = launch {
                reader.observeSleepSession("sleep-1").collect { emissions.send(it) }
            }
            try {
                val initial = withTimeout(5_000L) { emissions.receive() }
                assertTrue(initial.rawSamples.isEmpty())
                assertEquals(60.0, initial.warmBuckets.single().avgBpm, TOLERANCE)

                database.minuteBucketDao().upsertBuckets(
                    listOf(
                        initial.warmBuckets.single().copy(
                            minBpm = 80,
                            maxBpm = 80,
                            avgBpm = 80.0,
                        ),
                    ),
                )

                val updated = withTimeout(5_000L) { emissions.receive() }
                assertTrue(updated.rawSamples.isEmpty())
                assertEquals(80.0, updated.warmBuckets.single().avgBpm, TOLERANCE)
            } finally {
                collection.cancel()
                emissions.close()
            }
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

    // Review round 1 / C1. The reader correctly hides a quarantined minute's raw rows, but the two
    // repository methods that only read `rawSamples` then returned NOTHING for that minute -- strictly
    // lossier than not applying the predicate at all, and score-affecting via
    // ComputeSleepMetricsUseCase -> HrCoverageValidator.isValid (false on an empty list). Exercised
    // through the repository APIs, not the reader, because that is where the loss happened.
    @Test
    fun `repository APIs serve a quarantined minute from the warm tier instead of dropping it`() =
        runBlocking {
            val ref = seedSource("src-a")
            seedLegacyMinute(bucketStartMs = 0L, sampleCount = 10, avgBpm = 60.0)
            database.heartRateDao().upsertAll(listOf(hr(ref, 1_000L, 200)))
            rollupManager.rollupExpiredHotTier(MINUTE_MS)

            // Preconditions: the raw row survived the rollup and is invisible to the raw side.
            assertEquals(1, database.heartRateDao().countInRange(0L, MINUTE_MS - 1))
            assertTrue(reader.rangeIn(0L, MINUTE_MS - 1).rawSamples.isEmpty())

            val repository = HeartRateRepositoryImpl(database.heartRateDao(), database.hrvDao(), reader)
            val merged = repository.getByTimeRange(0L, MINUTE_MS - 1)
            assertEquals("the minute's warm evidence must be served, not dropped", 10, merged.size)
            assertEquals(60.0, merged.map { it.beatsPerMinute }.average(), TOLERANCE)
            assertTrue("the quarantined raw sample must stay hidden", merged.none { it.beatsPerMinute == 200 })

            val scoringHistory =
                ScoringHistoryRepositoryImpl(
                    heartRateDao = database.heartRateDao(),
                    hrvDao = database.hrvDao(),
                    sleepSessionDao = database.sleepSessionDao(),
                    dailySummaryDao = database.dailySummaryDao(),
                    authoritativeReader = reader,
                )
            val forScoring = scoringHistory.getHeartRateRecordsByTimeRange(0L, MINUTE_MS - 1)
            assertEquals(10, forScoring.size)
            assertEquals(60.0, forScoring.map { it.beatsPerMinute }.average(), TOLERANCE)
        }

    // The merged rows must keep each bucket's own key, or ComputeSleepMetricsUseCase's
    // `recordType == SLEEP && sessionId in currentSessionIds` filter would silently select nothing.
    @Test
    fun `merged warm rows keep their own record type and session id`() =
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
            // Minute 0 rolls up; minute 1 stays raw. Both must arrive keyed to the sleep session.
            rollupManager.rollupExpiredHotTier(MINUTE_MS)

            val scoringHistory =
                ScoringHistoryRepositoryImpl(
                    heartRateDao = database.heartRateDao(),
                    hrvDao = database.hrvDao(),
                    sleepSessionDao = database.sleepSessionDao(),
                    dailySummaryDao = database.dailySummaryDao(),
                    authoritativeReader = reader,
                )
            val records = scoringHistory.getHeartRateRecordsByTimeRange(0L, 2 * MINUTE_MS - 1)
            assertEquals(3, records.size)
            assertEquals(setOf("SLEEP"), records.map { it.recordType }.toSet())
            assertEquals(setOf("sleep-1"), records.map { it.sessionId }.toSet())
            // The filter ComputeSleepMetricsUseCase actually applies must keep all three.
            assertEquals(
                3,
                records.count { it.recordType == "SLEEP" && it.sessionId in setOf("sleep-1") },
            )
        }

    // Review round 1 / I2: coverage committed at a generation with no bucket slice (a partially
    // applied restore) used to fail BOTH predicates -- raw suppressed because coverage exists, warm
    // hidden because the generation does not match. The minute must degrade to its raw evidence.
    @Test
    fun `coverage with no bucket slice at its visible generation falls back to raw`() =
        runBlocking {
            val ref = seedSource("src-a")
            database.heartRateDao().upsertAll(listOf(hr(ref, 1_000L, 60), hr(ref, 2_000L, 62)))
            rollupManager.rollupExpiredHotTier(MINUTE_MS)
            // Precondition: warm is authoritative and raw is hidden.
            assertTrue(reader.rangeIn(0L, MINUTE_MS - 1).rawSamples.isEmpty())
            // Re-insert the raw rows the rollup consumed, then simulate a restore that landed the
            // coverage row but not the projection it points at.
            database.heartRateDao().upsertAll(listOf(hr(ref, 1_000L, 60), hr(ref, 2_000L, 62)))
            database.minuteBucketDao().deleteBucketsForMinutes(listOf(0L))

            val range = reader.rangeIn(0L, MINUTE_MS - 1)
            assertTrue("the warm side has nothing to serve", range.warmBuckets.isEmpty())
            assertEquals("the minute must not vanish from both tiers", 2, range.rawSamples.size)
            assertEquals(listOf(60, 62), range.rawSamples.map { it.beatsPerMinute })
            // And through every other predicate mirror, not just the range read.
            val projection = reader.minuteBuckets(0L, MINUTE_MS)
            assertEquals(2, projection.single().sampleCount)
            assertEquals(61.0, projection.single().avgBpm, TOLERANCE)
            assertEquals(60, reader.minBpmInRange(0L, MINUTE_MS - 1))
        }

    /**
     * Review round 1 / I3: the DAO comment claims the coverage join is a rowid seek and the warm
     * fallback subquery is index-covered. This asserts it against real SQLite instead of asserting
     * it in prose. The SQL below mirrors `HeartRateDao.getVisibleByTimeRange`; if that query's shape
     * changes, this plan changes with it and the test fails loudly.
     */
    @Test
    fun `the visibility predicates are index-driven`() {
        val plan =
            queryPlan(
                "SELECT h.* FROM heart_rate_records h " +
                    "LEFT JOIN minute_coverage c ON c.bucketStartMs = " +
                    "((h.timestampMs / 60000) - (CASE WHEN h.timestampMs % 60000 < 0 THEN 1 ELSE 0 END)) * 60000 " +
                    "WHERE h.timestampMs >= 0 AND h.timestampMs <= 60000 " +
                    "AND (c.bucketStartMs IS NULL OR c.tier = 'HOT' " +
                    "OR (c.tier IN ('WARM', 'LEGACY_WARM') AND NOT EXISTS (" +
                    "SELECT 1 FROM hr_minute_buckets b2 WHERE b2.bucketStartMs = c.bucketStartMs " +
                    "AND b2.generation = c.visibleGeneration))) " +
                    "ORDER BY h.timestampMs ASC, h.sourceRecordRef ASC",
            )
        println("WP-17 T3 EXPLAIN QUERY PLAN for the raw-side visibility predicate: $plan")
        assertTrue("plan must not scan heart_rate_records: $plan", plan.none { it.scansTable("heart_rate_records") })
        assertTrue("plan must not scan minute_coverage: $plan", plan.none { it.scansTable("minute_coverage") })
        assertTrue("plan must not scan hr_minute_buckets: $plan", plan.none { it.scansTable("hr_minute_buckets") })
        assertTrue(
            "the coverage join must be a rowid seek: $plan",
            plan.any { it.contains("minute_coverage") && it.contains("USING INTEGER PRIMARY KEY") },
        )
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

        // The chart/repository consumer must serve the WHOLE range, not just its raw half. Compared
        // against the independent reference above rather than against the reader's own expression:
        // `getByTimeRange` delegates to the reader, so asserting equality with
        // `reader.rangeIn(...).rawSamples` would be tautological AND would have rubber-stamped the
        // bug where a warm-covered minute contributed nothing at all.
        val repository = HeartRateRepositoryImpl(database.heartRateDao(), database.hrvDao(), reader)
        val fromRepository = repository.getByTimeRange(0L, endExclusiveMs - 1)
        assertEquals(
            "repository dropped samples the reference accounts for",
            reference.sumOf { it.sampleCount },
            fromRepository.count { it.beatsPerMinute in PLAUSIBLE },
        )
        if (reference.isNotEmpty()) {
            assertEquals(
                "repository mean disagrees with the reference beyond warm reconstruction error",
                reference.sumOf { it.avgBpm * it.sampleCount } / reference.sumOf { it.sampleCount },
                fromRepository.filter { it.beatsPerMinute in PLAUSIBLE }.map { it.beatsPerMinute }.average(),
                RECONSTRUCTION_TOLERANCE,
            )
        }
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

    /** Every `detail` row SQLite reports for [sql], via real `EXPLAIN QUERY PLAN`. */
    private fun queryPlan(sql: String): List<String> {
        val details = mutableListOf<String>()
        database.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            val detailIndex = cursor.getColumnIndexOrThrow("detail")
            while (cursor.moveToNext()) {
                details += cursor.getString(detailIndex)
            }
        }
        return details
    }

    private suspend fun seedSource(id: String): Long =
        database.sourceRecordDao().getOrCreateSourceRef(id, "HEART_RATE", 0L)

    /** A pre-v22 minute: an approximate warm bucket plus legacy coverage, at generation 0. */
    private suspend fun seedLegacyMinute(
        bucketStartMs: Long,
        sampleCount: Int,
        avgBpm: Double,
        recordType: String = "RESTING",
        sessionId: String = "",
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
                    recordType = recordType,
                    sessionId = sessionId,
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

        /**
         * Separate, larger bound for a mean taken over *reconstructed* warm points. Counts are
         * conserved exactly, but a warm point's value comes from the 7-anchor percentile sketch, not
         * from the histogram, so the reconstructed mean carries the documented warm-tier
         * reconstruction error rather than mere accumulation noise (measured on this fixture: 0.5
         * bpm at worst). Deliberately not conflated with [TOLERANCE].
         */
        const val RECONSTRUCTION_TOLERANCE = 2.0

        val PLAUSIBLE = 30..230
        val PLAUSIBLE_AVG = 30.0..230.0
    }
}

/**
 * True when this `EXPLAIN QUERY PLAN` detail row is a full table scan of [table]. Accepts both the
 * modern `SCAN <table>` wording and the older `SCAN TABLE <table>`; an index-driven `SEARCH`, or a
 * `SCAN ... USING ... INDEX`, is not a table scan.
 */
private fun String.scansTable(table: String): Boolean =
    Regex("""\bSCAN (TABLE )?\Q$table\E\b""").containsMatchIn(this) && !contains("USING")
