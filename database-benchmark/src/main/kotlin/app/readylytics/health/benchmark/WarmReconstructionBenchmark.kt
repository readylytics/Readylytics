package app.readylytics.health.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.repository.ScoringHistoryRepositoryImpl
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** B4 (R2-PERF-001): reconstruction of a 30-day warm baseline window (boxed objects today). */
@RunWith(AndroidJUnit4::class)
class WarmReconstructionBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var database: HealthDatabase
    private val zoneId = ZoneId.of("Europe/Berlin")

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
                .build()
        val startMs =
            LocalDate
                .of(2026, 1, 1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        // F-WARM shape: one sleep session per night, 1,440 buckets/night × 30 nights.
        val buckets =
            (0 until 30).flatMap { night ->
                val nightStart = startMs + night * 86_400_000L
                (0 until 1_440).map { i ->
                    HrMinuteBucketEntity(
                        bucketStartMs = nightStart + i * 60_000L,
                        bucketEndMs = nightStart + (i + 1) * 60_000L,
                        minBpm = 55,
                        maxBpm = 67,
                        avgBpm = 61.0,
                        sampleCount = 60,
                        recordType = "SLEEP",
                        sessionId = "bench-warm-$night",
                    )
                }
            }
        runBlocking { database.minuteBucketDao().upsertBuckets(buckets) }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun warmThirtyDayWindow() {
        val repo =
            ScoringHistoryRepositoryImpl(
                database.heartRateDao(),
                database.hrvDao(),
                database.sleepSessionDao(),
                database.dailySummaryDao(),
                database.minuteBucketDao(),
            )
        val sessionIds = (0 until 30).map { "bench-warm-$it" }
        benchmarkRule.measureRepeated {
            runBlocking { repo.getSleepHrProjectionForSessions(sessionIds) }
        }
        BenchmarkFixtures.recordAllocationDelta("B4.warmThirtyDayWindow") {
            runBlocking { repo.getSleepHrProjectionForSessions(sessionIds) }
        }
    }
}
