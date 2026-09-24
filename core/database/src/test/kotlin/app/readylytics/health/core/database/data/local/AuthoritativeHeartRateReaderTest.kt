package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.dao.getOrCreateSourceRef
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.databaseschema.data.local.entity.MinuteCoverageEntity
import app.readylytics.health.core.model.domain.model.RecordType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.round

@RunWith(RobolectricTestRunner::class)
class AuthoritativeHeartRateReaderTest {
    private lateinit var database: HealthDatabase
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
    fun `raw-only session calculates mean and projection from raw samples`() =
        runBlocking {
            val sourceRef = seedSource("src-raw")
            val sessionId = "session-raw"
            val rawRecords =
                listOf(
                    sleepHr(sourceRef, 1_000L, 60, sessionId),
                    sleepHr(sourceRef, 2_000L, 62, sessionId),
                )
            database.heartRateDao().upsertAll(rawRecords)

            val projection = reader.sleepProjectionForSessions(listOf(sessionId))
            assertEquals(2, projection.size)
            assertEquals(listOf(60, 62), projection.map { it.beatsPerMinute })

            val means = reader.sleepMeanForSessions(listOf(sessionId))
            assertEquals(61, means[sessionId])
        }

    @Test
    fun `warm-only session calculates mean and projection from warm buckets`() =
        runBlocking {
            val sessionId = "session-warm"
            val bucketStartMs = 60_000L
            val bucket =
                HrMinuteBucketEntity(
                    bucketStartMs = bucketStartMs,
                    bucketEndMs = bucketStartMs + 60_000L,
                    minBpm = 60,
                    maxBpm = 70,
                    avgBpm = 65.0,
                    sampleCount = 4,
                    recordType = RecordType.SLEEP.name,
                    sessionId = sessionId,
                    generation = 1L,
                )
            database.minuteBucketDao().upsertBuckets(listOf(bucket))
            database.minuteCoverageDao().upsertCoverage(
                listOf(
                    MinuteCoverageEntity(
                        bucketStartMs = bucketStartMs,
                        visibleGeneration = 1L,
                        tier = "WARM",
                        quality = "SOURCE_BACKED",
                    ),
                ),
            )

            val projection = reader.sleepProjectionForSessions(listOf(sessionId))
            assertEquals(4, projection.size)

            val means = reader.sleepMeanForSessions(listOf(sessionId))
            assertEquals(65, means[sessionId])
        }

    @Test
    fun `raw and warm overlap hidden by coverage ledger serves warm only`() =
        runBlocking {
            val sourceRef = seedSource("src-overlap")
            val sessionId = "session-overlap"
            val bucketStartMs = 120_000L

            // Raw record at 120 bpm in the same minute
            val raw = sleepHr(sourceRef, bucketStartMs + 1000L, 120, sessionId)
            database.heartRateDao().upsertAll(listOf(raw))

            // Warm bucket at 55 bpm with WARM tier coverage at generation 1
            val bucket =
                HrMinuteBucketEntity(
                    bucketStartMs = bucketStartMs,
                    bucketEndMs = bucketStartMs + 60_000L,
                    minBpm = 50,
                    maxBpm = 60,
                    avgBpm = 55.0,
                    sampleCount = 2,
                    recordType = RecordType.SLEEP.name,
                    sessionId = sessionId,
                    generation = 1L,
                )
            database.minuteBucketDao().upsertBuckets(listOf(bucket))
            database.minuteCoverageDao().upsertCoverage(
                listOf(
                    MinuteCoverageEntity(
                        bucketStartMs = bucketStartMs,
                        visibleGeneration = 1L,
                        tier = "WARM",
                        quality = "SOURCE_BACKED",
                    ),
                ),
            )

            // Because coverage is WARM at generation 1, the 120 bpm raw record must be hidden
            val projection = reader.sleepProjectionForSessions(listOf(sessionId))
            assertEquals(2, projection.size)
            assertTrue("Expected warm samples around 55 bpm, not 120", projection.none { it.beatsPerMinute > 100 })

            val means = reader.sleepMeanForSessions(listOf(sessionId))
            assertEquals(55, means[sessionId])
        }

    @Test
    fun `empty session produces empty projection and no mean entry`() =
        runBlocking {
            val emptySessionId = "session-empty"
            val projection = reader.sleepProjectionForSessions(listOf(emptySessionId))
            assertTrue(projection.isEmpty())

            val means = reader.sleepMeanForSessions(listOf(emptySessionId))
            assertNull(means[emptySessionId])
            assertTrue(means.isEmpty())
        }

    @Test
    fun `501 session IDs preserves parity between mean and projection and maintains sorting`() =
        runBlocking {
            val sourceRef = seedSource("src-501")
            val ids = (1..501).map { "sleep-$it" }
            val rawRecords = mutableListOf<HeartRateRecordEntity>()
            val warmBuckets = mutableListOf<HrMinuteBucketEntity>()
            val coverageEntries = mutableListOf<MinuteCoverageEntity>()

            for (i in 1..501) {
                val sid = "sleep-$i"
                val baseTimeMs = i * 60_000L
                if (i % 2 == 1) {
                    // Raw session
                    rawRecords += sleepHr(sourceRef, baseTimeMs, 50 + (i % 30), sid)
                    rawRecords += sleepHr(sourceRef, baseTimeMs + 10_000L, 52 + (i % 30), sid)
                } else {
                    // Warm session
                    warmBuckets +=
                        HrMinuteBucketEntity(
                            bucketStartMs = baseTimeMs,
                            bucketEndMs = baseTimeMs + 60_000L,
                            minBpm = 50 + (i % 30),
                            maxBpm = 60 + (i % 30),
                            avgBpm = (55 + (i % 30)).toDouble(),
                            sampleCount = 2,
                            recordType = RecordType.SLEEP.name,
                            sessionId = sid,
                            generation = 1L,
                        )
                    coverageEntries +=
                        MinuteCoverageEntity(
                            bucketStartMs = baseTimeMs,
                            visibleGeneration = 1L,
                            tier = "WARM",
                            quality = "SOURCE_BACKED",
                        )
                }
            }

            database.heartRateDao().upsertAll(rawRecords)
            database.minuteBucketDao().upsertBuckets(warmBuckets)
            database.minuteCoverageDao().upsertCoverage(coverageEntries)

            val projection = reader.sleepProjectionForSessions(ids)
            // Verify percentile projection ordering (sessionId, beatsPerMinute)
            for (j in 0 until projection.size - 1) {
                val curr = projection[j]
                val next = projection[j + 1]
                val cmp = curr.sessionId.compareTo(next.sessionId)
                assertTrue(
                    "Projection must be sorted by (sessionId, bpm): $curr vs $next",
                    cmp < 0 || (cmp == 0 && curr.beatsPerMinute <= next.beatsPerMinute),
                )
            }

            val expectedMeans =
                projection
                    .groupBy { it.sessionId }
                    .mapValues { (_, rows) -> round(rows.map { it.beatsPerMinute }.average()).toInt() }

            val actualMeans = reader.sleepMeanForSessions(ids)
            assertEquals(expectedMeans, actualMeans)
            assertEquals(501, actualMeans.size)
        }

    @Test
    fun `getVisibleBucketsForSessions matches getVisibleBucketsForSession across tier states`() =
        runBlocking {
            val minuteBucketDao = database.minuteBucketDao()
            val coverageDao = database.minuteCoverageDao()

            val sessions = listOf("s-hot", "s-warm", "s-legacy", "s-missing")
            val buckets =
                sessions.mapIndexed { index, sid ->
                    val startMs = (index + 1) * 60_000L
                    HrMinuteBucketEntity(
                        bucketStartMs = startMs,
                        bucketEndMs = startMs + 60_000L,
                        minBpm = 50,
                        maxBpm = 70,
                        avgBpm = 60.0,
                        sampleCount = 3,
                        recordType = RecordType.SLEEP.name,
                        sessionId = sid,
                        generation = 1L,
                    )
                }
            minuteBucketDao.upsertBuckets(buckets)

            coverageDao.upsertCoverage(
                listOf(
                    MinuteCoverageEntity(60_000L, visibleGeneration = 1L, tier = "HOT", quality = "SOURCE_BACKED"),
                    MinuteCoverageEntity(120_000L, visibleGeneration = 1L, tier = "WARM", quality = "SOURCE_BACKED"),
                    MinuteCoverageEntity(
                        180_000L,
                        visibleGeneration = 1L,
                        tier = "LEGACY_WARM",
                        quality = "LEGACY_UNKNOWN",
                    ),
                    // 240_000L has no coverage entry and no raw records -> missing coverage
                ),
            )

            val batched = minuteBucketDao.getVisibleBucketsForSessions(RecordType.SLEEP.name, sessions)
            val individual =
                sessions.flatMap { sid ->
                    minuteBucketDao.getVisibleBucketsForSession(RecordType.SLEEP.name, sid)
                }

            assertEquals(individual.sortedBy { it.bucketStartMs }, batched.sortedBy { it.bucketStartMs })
        }

    private suspend fun seedSource(id: String): Long =
        database.sourceRecordDao().getOrCreateSourceRef(id, "HEART_RATE", 0L)

    private fun sleepHr(
        ref: Long,
        timestampMs: Long,
        bpm: Int,
        sessionId: String,
    ) = HeartRateRecordEntity(
        sourceRecordRef = ref,
        timestampMs = timestampMs,
        beatsPerMinute = bpm,
        recordType = RecordType.SLEEP.name,
        sessionId = sessionId,
    )
}
