package app.readylytics.health.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.repository.ScoringDayDataLoader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** B5: getMinuteBuckets (hot), getMinuteBuckets (warm), loadMergedMinuteBuckets over one day. */
@RunWith(AndroidJUnit4::class)
class HeartRateAggregationBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var database: HealthDatabase
    private val zoneId = ZoneId.of("Europe/Berlin")
    private var dayStartMs: Long = 0L
    private var dayEndMs: Long = 0L

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
                .build()
        val day = LocalDate.of(2026, 1, 1)
        dayStartMs = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
        dayEndMs =
            day
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        // One dense hot day (1 Hz) plus a warm morning so both tiers are populated.
        BenchmarkFixtures.seedHeartRateRows(
            database = database,
            startTimeMs = dayStartMs,
            days = 1,
            sessionIds = listOf("bench-sleep-1"),
            recordTypes = listOf("SLEEP"),
        )
        BenchmarkFixtures.seedWarmBuckets(
            database = database,
            startTimeMs = dayStartMs,
            bucketCount = 720, // morning half already rolled up
            recordType = "SLEEP",
            sessionId = "bench-sleep-1",
            sampleCount = 60,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun hotMinuteBucketsOneDay() {
        val dao = database.heartRateDao()
        benchmarkRule.measureRepeated {
            runBlocking { dao.getMinuteBuckets(dayStartMs, dayEndMs) }
        }
    }

    @Test
    fun warmMinuteBucketsOneDay() {
        val dao = database.minuteBucketDao()
        benchmarkRule.measureRepeated {
            runBlocking { dao.getMinuteBuckets(dayStartMs, dayEndMs) }
        }
    }

    @Test
    fun loadMergedMinuteBucketsOneDay() {
        val loader =
            ScoringDayDataLoader(
                workoutDao = database.workoutDao(),
                sleepSessionDao = database.sleepSessionDao(),
                dailySummaryDao = database.dailySummaryDao(),
                heartRateDao = database.heartRateDao(),
                minuteBucketDao = database.minuteBucketDao(),
                weightRecordDao = database.weightRecordDao(),
                bodyFatRecordDao = database.bodyFatRecordDao(),
                bloodPressureRecordDao = database.bloodPressureRecordDao(),
                oxygenSaturationRecordDao = database.oxygenSaturationRecordDao(),
                bodyTemperatureRecordDao = database.bodyTemperatureRecordDao(),
            )
        benchmarkRule.measureRepeated {
            runBlocking { loader.loadMergedMinuteBuckets(dayStartMs, dayEndMs) }
        }
    }
}
