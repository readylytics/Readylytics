package app.readylytics.health.core.database.domain.scoring.golden

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.database.data.local.DataRollupManager
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.model.domain.heartrate.ZoneThresholds
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.math.round

/**
 * Golden equivalence for the warm tier: once a day's raw 1s samples are rolled into 1-minute
 * buckets, the everyday-HR load buckets (and thus TRIMP) and the sleep percentile RHR must match
 * the raw-tier result within the plan's tolerances (TRIMP ≤ 0.01%, sleep RHR ≤ 1 bpm).
 */
@RunWith(RobolectricTestRunner::class)
class ScoringEquivalenceGoldenTest {
    private lateinit var database: HealthDatabase
    private lateinit var rollupManager: DataRollupManager

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        rollupManager =
            DataRollupManager(
                minuteBucketDao = database.minuteBucketDao(),
                heartRateDao = database.heartRateDao(),
                transactionRunner = RoomTransactionRunner(database),
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun everydayHrBucketsMatchRawAfterRollupWithinTrimpTolerance() =
        runBlocking {
            val heartRateDao = database.heartRateDao()
            val minuteBucketDao = database.minuteBucketDao()
            val sourceRecordDao = database.sourceRecordDao()
            val ref = sourceRecordDao.getOrCreateSourceRef("uuid-golden", "HEART_RATE", 0L)

            val dayStart = 0L
            val dayEnd = 120 * 60_000L
            // 120 minutes: minutes 0..59 SLEEP (session s1), 60..119 RESTING. 10 samples/min with
            // within-minute variation so per-minute averages are non-integral and rounding matters.
            val entities =
                (0 until 120).flatMap { minute ->
                    val (recordType, sessionId) =
                        if (minute < 60) "SLEEP" to "s1" else "RESTING" to ""
                    val base = if (minute < 60) 50 + (minute % 20) else 60 + (minute % 30)
                    (0 until 10).map { sample ->
                        HeartRateRecordEntity(
                            sourceRecordRef = ref,
                            timestampMs = minute * 60_000L + sample * 6_000L,
                            beatsPerMinute = base + (sample % 5),
                            recordType = recordType,
                            sessionId = if (sessionId.isEmpty()) null else sessionId,
                        )
                    }
                }
            heartRateDao.upsertAll(entities)

            val rawBuckets = heartRateDao.getMinuteBuckets(dayStart, dayEnd)

            rollupManager.rollupExpiredHotTier(dayEnd)

            val warmBuckets = minuteBucketDao.getMinuteBuckets(dayStart, dayEnd)

            assertEquals(rawBuckets.size, warmBuckets.size)
            rawBuckets.zip(warmBuckets).forEach { (raw, warm) ->
                assertEquals(raw.bucketIndex, warm.bucketIndex)
                assertEquals(raw.sampleCount, warm.sampleCount)
                val delta = kotlin.math.abs(raw.avgBpm - warm.avgBpm) / raw.avgBpm
                assertEquals(0.0, delta, 0.0001)
            }
        }

    @Test
    fun sleepPercentileRhrMatchesRawAfterRollupWithinOneBpm() =
        runBlocking {
            val heartRateDao = database.heartRateDao()
            val minuteBucketDao = database.minuteBucketDao()
            val sourceRecordDao = database.sourceRecordDao()
            val ref = sourceRecordDao.getOrCreateSourceRef("uuid-golden", "HEART_RATE", 0L)

            val dayStart = 0L
            val dayEnd = 120 * 60_000L
            val entities =
                (0 until 60).flatMap { minute ->
                    val base = 45 + (minute % 25)
                    (0 until 10).map { sample ->
                        HeartRateRecordEntity(
                            sourceRecordRef = ref,
                            timestampMs = minute * 60_000L + sample * 6_000L,
                            beatsPerMinute = base + (sample % 7),
                            recordType = "SLEEP",
                            sessionId = "s1",
                        )
                    }
                }
            heartRateDao.upsertAll(entities)

            val rawSleepSamples = heartRateDao.getSleepHrSamplesForSession("s1").sorted()

            rollupManager.rollupExpiredHotTier(dayEnd)

            val warmSleepSamples =
                minuteBucketDao.getBucketsForSession("SLEEP", "s1").reconstruct().sorted()

            assertEquals(rawSleepSamples.size, warmSleepSamples.size)
            val rawPercentile = percentileRhr(rawSleepSamples, 0.25)
            val warmPercentile = percentileRhr(warmSleepSamples, 0.25)
            assertEquals(rawPercentile, warmPercentile, 1.0)
        }

    @Test
    fun sleepRhrMatchesAfterRollupWhenImplausibleSamplesPresent() =
        runBlocking {
            val heartRateDao = database.heartRateDao()
            val minuteBucketDao = database.minuteBucketDao()
            val sourceRecordDao = database.sourceRecordDao()
            val ref = sourceRecordDao.getOrCreateSourceRef("uuid-sleep-artifact", "HEART_RATE", 0L)

            val dayEnd = 120 * 60_000L
            // 60 sleep minutes, 10 samples/min; inject one sub-30 bpm artifact (20 bpm) in minute 0.
            val entities =
                (0 until 60).flatMap { minute ->
                    val base = 50 + (minute % 20)
                    (0 until 10).map { sample ->
                        val bpm = if (minute == 0 && sample == 0) 20 else base + (sample % 5)
                        HeartRateRecordEntity(
                            sourceRecordRef = ref,
                            timestampMs = minute * 60_000L + sample * 6_000L,
                            beatsPerMinute = bpm,
                            recordType = "SLEEP",
                            sessionId = "s1",
                        )
                    }
                }
            heartRateDao.upsertAll(entities)

            val rawSleepSamples = heartRateDao.getSleepHrSamplesForSession("s1").sorted()

            rollupManager.rollupExpiredHotTier(dayEnd)

            val warmSleepSamples =
                minuteBucketDao.getBucketsForSession("SLEEP", "s1").reconstruct().sorted()

            // 599 samples after the 20-bpm artifact is filtered from BOTH tiers.
            assertEquals(599, rawSleepSamples.size)
            assertEquals(rawSleepSamples.size, warmSleepSamples.size)
            assertEquals(percentileRhr(rawSleepSamples, 0.25), percentileRhr(warmSleepSamples, 0.25), 1.0)
        }

    @Test
    fun workoutReconstructionMatchesRawAfterRollupWithinTolerance() =
        runBlocking {
            val heartRateDao = database.heartRateDao()
            val minuteBucketDao = database.minuteBucketDao()
            val sourceRecordDao = database.sourceRecordDao()
            val ref = sourceRecordDao.getOrCreateSourceRef("uuid-workout-golden", "HEART_RATE", 0L)

            // Non-minute-aligned workout: 15.0s to 2145.0s (35m 30s).
            val workoutStartMs = 15_000L
            val workoutEndMs = 2_145_000L
            val rawSamples =
                (workoutStartMs..workoutEndMs step 2_000L).map { ts ->
                    val minute = ts / 60_000L
                    val bpm = 130 + (minute.toInt() % 30)
                    HeartRateRecordEntity(
                        sourceRecordRef = ref,
                        timestampMs = ts,
                        beatsPerMinute = bpm,
                        recordType = "EXERCISE",
                        sessionId = "w1",
                    )
                }
            heartRateDao.upsertAll(rawSamples)

            val thresholds = ZoneThresholds.create()
            val rawDomainSamples =
                rawSamples.map {
                    DomainHeartRateSample(Instant.ofEpochMilli(it.timestampMs), it.beatsPerMinute)
                }
            val rawMetrics =
                ZoneThresholds.computeMetrics(workoutStartMs, workoutEndMs, rawDomainSamples, thresholds)

            rollupManager.rollupExpiredHotTier(workoutEndMs + 60_000L)

            val warmBuckets = minuteBucketDao.getBucketsForSession("EXERCISE", "w1")
            val reconstructedSamples =
                warmBuckets.reconstructTimestamped().map { (ts, bpm) ->
                    DomainHeartRateSample(Instant.ofEpochMilli(ts), bpm)
                }
            val warmMetrics =
                ZoneThresholds.computeMetrics(workoutStartMs, workoutEndMs, reconstructedSamples, thresholds)

            assertEquals(rawMetrics.durationMinutes, warmMetrics.durationMinutes)
            assertEquals(rawMetrics.avgHr, warmMetrics.avgHr, 1.0f)
            assertEquals(rawMetrics.trimp, warmMetrics.trimp, 1.0f)
        }

    private fun percentileRhr(
        sortedBpm: List<Int>,
        percentile: Double,
    ): Double {
        val idx = round(percentile * (sortedBpm.size - 1)).toInt().coerceIn(0, sortedBpm.size - 1)
        return sortedBpm[idx].toDouble()
    }

    private fun List<HrMinuteBucketEntity>.reconstruct(): List<Int> =
        flatMap { bucket -> List(bucket.sampleCount) { round(bucket.avgBpm).toInt() } }

    private fun List<HrMinuteBucketEntity>.reconstructTimestamped(): List<Pair<Long, Int>> =
        flatMap { bucket ->
            val stepMs = if (bucket.sampleCount > 1) 60_000L / bucket.sampleCount else 0L
            List(bucket.sampleCount) { i ->
                val offsetMs = (i * stepMs).coerceAtMost(59_999L)
                bucket.bucketStartMs + offsetMs to round(bucket.avgBpm).toInt()
            }
        }
}
