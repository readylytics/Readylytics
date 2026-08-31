package app.readylytics.health.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.HealthRecordDaos
import app.readylytics.health.core.database.data.local.RoomHealthIngestionStore
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** B2 (R2-PERF-003): persistHeartRateSamples for 100 k rows, cold + idempotent re-ingest. */
@RunWith(AndroidJUnit4::class)
class HrUpsertBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var database: HealthDatabase
    private lateinit var store: RoomHealthIngestionStore
    private val zoneId = ZoneId.of("Europe/Berlin")

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
                .build()
        val daos =
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
                minuteBucketDao = database.minuteBucketDao(),
            )
        store =
            RoomHealthIngestionStore(
                daos = daos,
                dailySummaryDao = database.dailySummaryDao(),
                transactionRunner = RoomTransactionRunner(database),
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun coldHundredThousandRows() {
        val baseMs =
            LocalDate
                .of(2026, 1, 1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        val samples =
            (0 until 100_000).map { i ->
                HeartRateInput(
                    id = "bench-src-$i",
                    timestampMs = baseMs + i * 1_000L,
                    beatsPerMinute = 60 + (i % 60),
                    recordType = if (i % 3 == 0) "SLEEP" else "RESTING",
                    sessionId = if (i % 3 == 0) "bench-sleep-1" else null,
                    deviceName = "bench-device",
                )
            }
        benchmarkRule.measureRepeated {
            runBlocking { store.persistHeartRateSamples(samples) }
        }
        BenchmarkFixtures.recordAllocationDelta("B2.coldHundredThousandRows") {
            runBlocking { store.persistHeartRateSamples(samples) }
        }
    }

    @Test
    fun idempotentReingestHundredThousandRows() {
        val baseMs =
            LocalDate
                .of(2026, 1, 1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        val samples =
            (0 until 100_000).map { i ->
                HeartRateInput(
                    id = "bench-src-$i",
                    timestampMs = baseMs + i * 1_000L,
                    beatsPerMinute = 60 + (i % 60),
                    recordType = if (i % 3 == 0) "SLEEP" else "RESTING",
                    sessionId = if (i % 3 == 0) "bench-sleep-1" else null,
                    deviceName = "bench-device",
                )
            }
        runBlocking { store.persistHeartRateSamples(samples) } // seed once, outside timing
        benchmarkRule.measureRepeated {
            runBlocking { store.persistHeartRateSamples(samples) } // identical re-ingest → changes() = 0
        }
        BenchmarkFixtures.recordAllocationDelta("B2.idempotentReingestHundredThousandRows") {
            runBlocking { store.persistHeartRateSamples(samples) }
        }
    }
}
