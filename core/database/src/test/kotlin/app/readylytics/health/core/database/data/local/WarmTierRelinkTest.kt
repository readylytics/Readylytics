package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.dao.getOrCreateSourceRef
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrSourceMinuteContributionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.MinuteCoverageEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.domain.heartrate.ZoneThresholds
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import app.readylytics.health.core.model.domain.sync.link.SessionLinker
import app.readylytics.health.core.model.domain.sync.link.SessionSpan
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * WP-17 Step 1/Step 4: the full-range warm relink, against a real Room database.
 *
 * The fixture rolls a five-minute stretch into the warm tier while a sleep session covers part of
 * it, then moves the session so that its boundary falls *inside* a minute -- the only case where a
 * warm minute's session assignment genuinely has to change, and the only case where the
 * hot-versus-warm temporal approximation is observable. Every test then checks one of the Step 1/4
 * guarantees: convergence, no drift on repeat, independence from how the range is chunked,
 * convergence after a source deletion, and a *measured* bound on the approximation error.
 */
@RunWith(RobolectricTestRunner::class)
class WarmTierRelinkTest {
    private lateinit var database: HealthDatabase
    private lateinit var rollupManager: DataRollupManager
    private lateinit var relinker: WarmTierRelinker
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
        val publisher =
            MinuteCoveragePublisher(
                minuteBucketDao = database.minuteBucketDao(),
                minuteCoverageDao = database.minuteCoverageDao(),
                dirtyRangeDao = database.dirtyRangeDao(),
                healthMutationStateDao = database.healthMutationStateDao(),
            )
        rollupManager =
            DataRollupManager(
                coordinator = TestHealthMutationCoordinator,
                minuteCoverageDao = database.minuteCoverageDao(),
                heartRateDao = database.heartRateDao(),
                publisher = publisher,
                transactionRunner = RoomTransactionRunner(database),
                dirtyRangeDao = database.dirtyRangeDao(),
                healthMutationStateDao = database.healthMutationStateDao(),
            )
        relinker =
            WarmTierRelinker(
                selectionDao = database.minuteCoverageSelectionDao(),
                minuteCoverageDao = database.minuteCoverageDao(),
                minuteBucketDao = database.minuteBucketDao(),
                publisher = publisher,
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
    fun `session wholly inside a minute bypasses the uniform fast path`() =
        runBlocking {
            seedRolledUpHistory(sleepEndMs = ORIGINAL_SLEEP_END)
            val minute = 4 * MINUTE_MS
            val before = bucketsAt(minute)
            assertEquals(setOf("RESTING"), before.map { it.recordType }.toSet())

            relinker.relink(
                minute,
                minute + MINUTE_MS - 1,
                emptyList(),
                listOf(SessionSpan("short-workout", minute + 10_000L, minute + 50_000L)),
            )

            val after = bucketsAt(minute)
            assertEquals(setOf("RESTING", "EXERCISE"), after.map { it.recordType }.toSet())
            assertEquals(before.sumOf { it.sampleCount }, after.sumOf { it.sampleCount })
            assertEquals(2, after.single { it.sessionId == "short-workout" }.sampleCount)
        }

    @Test
    fun `relink re-keys warm minutes when a session boundary moves`() =
        runBlocking {
            seedRolledUpHistory(sleepEndMs = ORIGINAL_SLEEP_END)
            val before = visibleBuckets()
            assertEquals(
                "fixture precondition: minutes 0-2 start out linked to the sleep session",
                setOf("sleep-1", ""),
                before.map { it.sessionId }.toSet(),
            )

            relinker.relink(0L, RANGE_END_INCLUSIVE, sleepSpans(MOVED_SLEEP_END), emptyList())

            val after = visibleBuckets()
            // Sleep now ends inside minute 1, so minute 2 must have left the session entirely.
            assertEquals(
                setOf(0L, MINUTE_MS),
                after.filter { it.sessionId == "sleep-1" }.map { it.bucketStartMs }.toSet(),
            )
            // No sample is lost or duplicated by the re-keying.
            assertEquals(before.sumOf { it.sampleCount }, after.sumOf { it.sampleCount })
            assertOneGenerationPerMinute()
        }

    @Test
    fun `repeating the relink pass cannot drift`() =
        runBlocking {
            seedRolledUpHistory(sleepEndMs = ORIGINAL_SLEEP_END)
            val spans = sleepSpans(MOVED_SLEEP_END)

            val first = relinker.relink(0L, RANGE_END_INCLUSIVE, spans, emptyList())
            val afterFirst = visibleBuckets()
            assertTrue("first pass must actually change something", first.republished > 0)

            val second = relinker.relink(0L, RANGE_END_INCLUSIVE, spans, emptyList())
            assertEquals("a converged pass must publish nothing", 0, second.republished)
            assertEquals(afterFirst, visibleBuckets())

            val third = relinker.relink(0L, RANGE_END_INCLUSIVE, spans, emptyList())
            assertEquals(0, third.republished)
            assertEquals(afterFirst, visibleBuckets())
        }

    @Test
    fun `relinking the range in two chunks equals relinking it in one`() =
        runBlocking {
            seedRolledUpHistory(sleepEndMs = ORIGINAL_SLEEP_END)
            val spans = sleepSpans(MOVED_SLEEP_END)
            relinker.relink(0L, RANGE_END_INCLUSIVE, spans, emptyList())
            val single = visibleBuckets()

            tearDown()
            setup()
            seedRolledUpHistory(sleepEndMs = ORIGINAL_SLEEP_END)
            relinker.relink(0L, 2 * MINUTE_MS - 1, spans, emptyList())
            relinker.relink(2 * MINUTE_MS, RANGE_END_INCLUSIVE, spans, emptyList())
            assertEquals(single, visibleBuckets())
        }

    // An unchanged session list must leave a rolled-up minute bit-identical: the derived projection
    // is re-aggregated from the stored histograms, which are a lossless value multiset.
    @Test
    fun `unchanged sessions leave every warm bucket byte-identical`() =
        runBlocking {
            seedRolledUpHistory(sleepEndMs = ORIGINAL_SLEEP_END)
            val before = visibleBuckets()

            val outcome = relinker.relink(0L, RANGE_END_INCLUSIVE, sleepSpans(ORIGINAL_SLEEP_END), emptyList())

            assertEquals(0, outcome.republished)
            assertEquals(before, visibleBuckets())
        }

    @Test
    fun `deleting a rolled-up source retires the minutes its evidence backed`() =
        runBlocking {
            seedRolledUpHistory(sleepEndMs = ORIGINAL_SLEEP_END)
            assertTrue(visibleBuckets().isNotEmpty())

            // Health Connect deletion reconciliation path: drops contributions, cascades raw rows.
            database.sourceRecordDao().deleteBySourceRecordId(SOURCE_ID)

            val outcome = relinker.relink(0L, RANGE_END_INCLUSIVE, sleepSpans(ORIGINAL_SLEEP_END), emptyList())

            assertEquals(5, outcome.retired)
            assertTrue("no warm projection may survive its evidence", visibleBuckets().isEmpty())
            assertTrue(
                "retired minutes must leave no coverage behind",
                database.minuteCoverageDao().getCoverageInRange(0L, 5 * MINUTE_MS).isEmpty(),
            )
            assertTrue(reader.rangeIn(0L, RANGE_END_INCLUSIVE).warmBuckets.isEmpty())
        }

    // Review round 1 / M8: retirement used to drop coverage and buckets but only the contributions
    // that happened to cascade with the deleted source, so a *superseded* generation's row belonging
    // to a source that still exists was orphaned forever -- nothing revisits a minute with no coverage.
    @Test
    fun `retiring a minute leaves no orphaned contributions at any generation`() =
        runBlocking {
            seedRolledUpHistory(sleepEndMs = ORIGINAL_SLEEP_END)
            val visibleGeneration =
                database
                    .minuteCoverageDao()
                    .getCoverageInRange(0L, MINUTE_MS)
                    .single()
                    .visibleGeneration
            // A second, still-live source whose contribution sits at a superseded generation, i.e. a
            // row the visible-generation cleanup in `publish` never sees.
            val otherRef = database.sourceRecordDao().getOrCreateSourceRef("src-b", "HEART_RATE", 0L)
            database.minuteCoverageDao().upsertContributions(
                listOf(
                    HrSourceMinuteContributionEntity(
                        sourceRecordRef = otherRef,
                        bucketStartMs = 0L,
                        generation = visibleGeneration - 1,
                        firstSampleMs = 1_000L,
                        lastSampleMs = 58_000L,
                        deviceName = "other-device",
                        bpmHistogram = BpmHistogram(mapOf(60 to 2)).encode(),
                    ),
                ),
            )

            database.sourceRecordDao().deleteBySourceRecordId(SOURCE_ID)
            val outcome = relinker.relink(0L, RANGE_END_INCLUSIVE, sleepSpans(ORIGINAL_SLEEP_END), emptyList())

            assertEquals(5, outcome.retired)
            assertTrue(
                "a retired minute must leave no contribution row at any generation",
                database.minuteCoverageDao().getContributionsForMinute(0L).isEmpty(),
            )
        }

    // Review round 1 / M6: an empty derivation used to be published as `Changed(emptyList())`, which
    // deletes the minute's slices while re-upserting its coverage -- a permanently invisible minute
    // with live coverage that the next pass reads back as `Unchanged`. It must be preserved and counted.
    @Test
    fun `a minute whose evidence accounts for no samples is preserved and counted`() =
        runBlocking {
            seedRolledUpHistory(sleepEndMs = ORIGINAL_SLEEP_END)
            val before = bucketsAt(0L)
            assertTrue("fixture precondition: minute 0 has a visible projection", before.isNotEmpty())
            val visibleGeneration =
                database
                    .minuteCoverageDao()
                    .getCoverageInRange(0L, MINUTE_MS)
                    .single()
                    .visibleGeneration
            // Replace minute 0's evidence with a readable but zero-count histogram, so the derivation
            // resolves no samples at all while the stored projection still claims some.
            val existing =
                database
                    .minuteCoverageDao()
                    .getContributionsForMinute(0L)
                    .single { it.generation == visibleGeneration }
            // "v1:" is a *valid* encoding of an empty histogram -- readable, but zero samples -- so
            // this exercises the empty-derivation path rather than the malformed-payload one.
            database.minuteCoverageDao().upsertContributions(
                listOf(existing.copy(bpmHistogram = BpmHistogram(emptyMap()).encode())),
            )

            val outcome = relinker.relink(0L, RANGE_END_INCLUSIVE, sleepSpans(MOVED_SLEEP_END), emptyList())

            assertEquals("the minute must be counted as unresolved", 1, outcome.unresolvedEvidence)
            assertEquals("and left exactly as it was", before, bucketsAt(0L))
            assertEquals(
                "its coverage must still point at a projection that exists",
                setOf(visibleGeneration),
                bucketsAt(0L).map { it.generation }.toSet(),
            )
        }

    @Test
    fun `legacy minutes are left untouched and reported unresolved`() =
        runBlocking {
            seedRolledUpHistory(sleepEndMs = ORIGINAL_SLEEP_END)
            seedLegacyMinute(10 * MINUTE_MS)
            val legacyBefore = bucketsAt(10 * MINUTE_MS)

            val outcome = relinker.relink(0L, 11 * MINUTE_MS - 1, sleepSpans(MOVED_SLEEP_END), emptyList())

            assertEquals(1, outcome.unresolvedLegacy)
            assertEquals(legacyBefore, bucketsAt(10 * MINUTE_MS))
            assertEquals(
                QUALITY_LEGACY_UNKNOWN,
                database
                    .minuteCoverageDao()
                    .getCoverageInRange(10 * MINUTE_MS, 11 * MINUTE_MS)
                    .single()
                    .quality,
            )
        }

    @Test
    fun `reconciler wires the relink between the raw passes and the workout recompute`() =
        runBlocking {
            seedRolledUpHistory(sleepEndMs = ORIGINAL_SLEEP_END)
            database.sleepSessionDao().upsertAll(listOf(sleepSession(MOVED_SLEEP_END)))

            val reconciler =
                SessionLinkReconcilerImpl(
                    sleepSessionDao = database.sleepSessionDao(),
                    workoutDao = database.workoutDao(),
                    heartRateDao = database.heartRateDao(),
                    hrvDao = database.hrvDao(),
                    transactionRunner = RoomTransactionRunner(database),
                    authoritativeReader = reader,
                    warmTierRelinker = relinker,
                )
            reconciler.reconcile(0L, RANGE_END_INCLUSIVE, ZoneThresholds.create())

            assertEquals(
                setOf(0L, MINUTE_MS),
                visibleBuckets().filter { it.sessionId == "sleep-1" }.map { it.bucketStartMs }.toSet(),
            )
            assertOneGenerationPerMinute()
        }

    // Step 1's killed/retried comparison: a pass interrupted after committing part of the range and
    // then retried over the whole range must land exactly where an uninterrupted pass does.
    @Test
    fun `a partially completed pass retried over the full range matches an uninterrupted one`() =
        runBlocking {
            seedRolledUpHistory(sleepEndMs = ORIGINAL_SLEEP_END)
            val spans = sleepSpans(MOVED_SLEEP_END)
            relinker.relink(0L, RANGE_END_INCLUSIVE, spans, emptyList())
            val uninterrupted = visibleBuckets()

            tearDown()
            setup()
            seedRolledUpHistory(sleepEndMs = ORIGINAL_SLEEP_END)
            // "Killed" after the first two minutes committed, then retried over everything.
            relinker.relink(0L, 2 * MINUTE_MS - 1, spans, emptyList())
            relinker.relink(0L, RANGE_END_INCLUSIVE, spans, emptyList())

            assertEquals(uninterrupted, visibleBuckets())
        }

    // Step 4's "recompute affected workout zones/avgHr/TRIMP through existing computeMetrics":
    // a workout whose HR now only exists in the warm tier must still be recomputed, from the
    // authoritative warm samples the reader selects -- not from an empty raw range.
    @Test
    fun `workout metrics are recomputed from the authoritative warm tier after relink`() =
        runBlocking {
            val ref = database.sourceRecordDao().getOrCreateSourceRef(SOURCE_ID, "HEART_RATE", 0L)
            database.workoutDao().upsertAll(
                listOf(
                    WorkoutRecordEntity(
                        id = "workout-1",
                        startTime = 0L,
                        endTime = 3 * MINUTE_MS - 1,
                        exerciseType = "RUNNING",
                        durationMinutes = 0,
                        zone1Minutes = 0f,
                        zone2Minutes = 0f,
                        zone3Minutes = 0f,
                        zone4Minutes = 0f,
                        zone5Minutes = 0f,
                        trimp = 0f,
                        avgHr = 0f,
                    ),
                ),
            )
            database.heartRateDao().upsertAll(
                fixtureSamples().take(3 * OFFSETS.size).map {
                    it.copy(sourceRecordRef = ref, recordType = "EXERCISE", sessionId = "workout-1")
                },
            )
            rollupManager.rollupExpiredHotTier(3 * MINUTE_MS)
            assertEquals("workout HR must be warm-only for this test", 0, database.heartRateDao().count())

            val zoneThresholds = ZoneThresholds.create()
            SessionLinkReconcilerImpl(
                sleepSessionDao = database.sleepSessionDao(),
                workoutDao = database.workoutDao(),
                heartRateDao = database.heartRateDao(),
                hrvDao = database.hrvDao(),
                transactionRunner = RoomTransactionRunner(database),
                authoritativeReader = reader,
                warmTierRelinker = relinker,
            ).reconcile(0L, 3 * MINUTE_MS - 1, zoneThresholds)

            val expected =
                ZoneThresholds.computeMetrics(
                    0L,
                    3 * MINUTE_MS - 1,
                    reader.warmSessionSamples("EXERCISE", "workout-1").map {
                        DomainHeartRateSample(Instant.ofEpochMilli(it.timestampMs), it.beatsPerMinute)
                    },
                    zoneThresholds,
                )
            val stored = database.workoutDao().getByIds(listOf("workout-1")).single()
            assertTrue("warm samples must actually reach computeMetrics", expected.avgHr > 0f)
            assertEquals(expected.trimp, stored.trimp, 1e-4f)
            assertEquals(expected.avgHr, stored.avgHr, 1e-4f)
            assertEquals(expected.durationMinutes, stored.durationMinutes)
        }

    /**
     * Step 1's separately reported **hot-versus-warm approximation error**.
     *
     * The reference is the split the *original raw timestamps* produce under the moved session
     * bounds; the measurement is the split the relinked warm tier produces. They can differ only
     * inside the single minute the session boundary falls in, because warm timestamps are
     * reconstructed from each contribution's `(firstSampleMs, lastSampleMs, count)` rather than
     * stored per sample. The assertion pins a ceiling of one misassigned sample per
     * boundary-crossing minute; the measured value is printed so the report can quote it.
     */
    @Test
    fun `hot versus warm session-split approximation error stays within one sample per boundary minute`() =
        runBlocking {
            val rawSamples = fixtureSamples()
            seedRolledUpHistory(sleepEndMs = ORIGINAL_SLEEP_END)
            relinker.relink(0L, RANGE_END_INCLUSIVE, sleepSpans(MOVED_SLEEP_END), emptyList())

            val spans = sleepSpans(MOVED_SLEEP_END)
            val rawByLink =
                rawSamples.groupBy { sample ->
                    val link = SessionLinker.resolve(sample.timestampMs, spans, emptyList())
                    link.recordType to (link.sessionId ?: "")
                }
            val warmByLink = visibleBuckets().groupBy { it.recordType to it.sessionId }

            assertEquals(
                "the set of (recordType, sessionId) keys must match exactly",
                rawByLink.keys,
                warmByLink.keys,
            )
            var worstCountDelta = 0
            var worstMeanDelta = 0.0
            for ((key, raw) in rawByLink) {
                val warm = warmByLink.getValue(key)
                val warmCount = warm.sumOf { it.sampleCount }
                val warmMean = warm.sumOf { it.avgBpm * it.sampleCount } / warmCount
                val rawMean = raw.map { it.beatsPerMinute }.average()
                worstCountDelta = maxOf(worstCountDelta, kotlin.math.abs(warmCount - raw.size))
                worstMeanDelta = maxOf(worstMeanDelta, kotlin.math.abs(warmMean - rawMean))
            }
            println(
                "WP-17 T3 measured hot-vs-warm split approximation: " +
                    "worstCountDelta=$worstCountDelta samples, " +
                    "worstMeanDelta=${"%.4f".format(worstMeanDelta)} bpm",
            )
            // One boundary-crossing minute in this fixture, so at most one sample may land on the
            // wrong side of it. Totals are conserved regardless (asserted above and in the re-key test).
            assertTrue("count delta $worstCountDelta exceeds 1 sample", worstCountDelta <= 1)
            assertTrue("mean delta $worstMeanDelta exceeds 6 bpm", worstMeanDelta <= 6.0)
            assertEquals(rawSamples.size, visibleBuckets().sumOf { it.sampleCount })
        }

    /**
     * Five whole minutes of ascending-BPM samples under a sleep session ending at [sleepEndMs],
     * ingested the way the HR mapper would tag them and then rolled fully into the warm tier.
     */
    private suspend fun seedRolledUpHistory(sleepEndMs: Long) {
        val ref = database.sourceRecordDao().getOrCreateSourceRef(SOURCE_ID, "HEART_RATE", 0L)
        database.sleepSessionDao().upsertAll(listOf(sleepSession(sleepEndMs)))
        val spans = sleepSpans(sleepEndMs)
        database.heartRateDao().upsertAll(
            fixtureSamples().map { sample ->
                val link = SessionLinker.resolve(sample.timestampMs, spans, emptyList())
                sample.copy(
                    sourceRecordRef = ref,
                    recordType = link.recordType,
                    sessionId = link.sessionId,
                )
            },
        )
        rollupManager.rollupExpiredHotTier(5 * MINUTE_MS)
        assertEquals("fixture must be fully rolled up", 0, database.heartRateDao().count())
    }

    /**
     * Four samples per minute at fixed offsets with strictly ascending BPM, so the reconstruction's
     * ascending value/timestamp pairing reproduces the original pairing and the only residual
     * difference is the reconstructed instant itself.
     */
    private fun fixtureSamples(): List<HeartRateRecordEntity> =
        (0 until 5).flatMap { minute ->
            OFFSETS.mapIndexed { index, offsetMs ->
                HeartRateRecordEntity(
                    sourceRecordRef = 0L,
                    timestampMs = minute * MINUTE_MS + offsetMs,
                    beatsPerMinute = 50 + minute * OFFSETS.size + index,
                    recordType = "RESTING",
                    sessionId = null,
                )
            }
        }

    private suspend fun visibleBuckets(): List<HrMinuteBucketEntity> =
        database
            .minuteBucketDao()
            .getVisibleBucketsInMinuteRange(0L, 11 * MINUTE_MS)
            .sortedWith(compareBy({ it.bucketStartMs }, { it.recordType }, { it.sessionId }, { it.deviceName }))

    private suspend fun bucketsAt(bucketStartMs: Long): List<HrMinuteBucketEntity> =
        database
            .minuteBucketDao()
            .getVisibleBucketsInMinuteRange(bucketStartMs, bucketStartMs + MINUTE_MS)

    /**
     * Exactly one generation per covered minute -- and at least one. Review round 1 / I2: tolerating
     * an empty generation set would pass a minute that has live coverage but no visible projection
     * at all, which is silent data loss rather than a mixed-generation defect. A minute that genuinely
     * has no projection left must have had its coverage row retired with its buckets, so it does not
     * appear in this loop at all.
     */
    private suspend fun assertOneGenerationPerMinute() {
        database.minuteCoverageDao().getCoverageInRange(0L, 11 * MINUTE_MS).forEach { coverage ->
            val generations =
                bucketsAt(coverage.bucketStartMs).map { it.generation }.toSet()
            assertEquals(
                "minute ${coverage.bucketStartMs} must be backed by exactly its visible generation",
                setOf(coverage.visibleGeneration),
                generations,
            )
        }
    }

    private fun sleepSpans(sleepEndMs: Long) = listOf(SessionSpan("sleep-1", 0L, sleepEndMs))

    private fun sleepSession(endTime: Long) =
        SleepSessionEntity(
            id = "sleep-1",
            startTime = 0L,
            endTime = endTime,
            durationMinutes = 0,
            efficiency = 0f,
            deepSleepMinutes = 0,
            remSleepMinutes = 0,
            lightSleepMinutes = 0,
            awakeMinutes = 0,
        )

    private suspend fun seedLegacyMinute(bucketStartMs: Long) {
        database.minuteBucketDao().upsertBuckets(
            listOf(
                HrMinuteBucketEntity(
                    bucketStartMs = bucketStartMs,
                    bucketEndMs = bucketStartMs + MINUTE_MS,
                    minBpm = 50,
                    maxBpm = 70,
                    avgBpm = 60.0,
                    sampleCount = 10,
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

    private companion object {
        const val MINUTE_MS = 60_000L
        const val SOURCE_ID = "src-a"

        /** Sample offsets inside each minute. */
        val OFFSETS = listOf(1_000L, 20_000L, 40_000L, 58_000L)

        /** Original bounds: the session ends exactly on a minute boundary. */
        const val ORIGINAL_SLEEP_END = 3 * 60_000L - 1

        /**
         * Moved bounds: the session now ends *between* a reconstructed instant (99_000) and the
         * original sample instant (100_000) inside minute 1, which is precisely the sub-minute
         * ambiguity the warm tier cannot resolve exactly.
         */
        const val MOVED_SLEEP_END = 99_500L

        const val RANGE_END_INCLUSIVE = 5 * 60_000L - 1
    }
}
