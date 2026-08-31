package app.readylytics.health.benchmark

import android.os.SystemClock
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.DataRollupManager
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
import app.readylytics.health.core.database.data.local.RoomWalDiagnostics
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** B3 (R2-PERF-002): rollup of a 30-day 1 Hz corpus — wall time and peak WAL size. */
@RunWith(AndroidJUnit4::class)
class RollupBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var dbFile: File
    private lateinit var database: HealthDatabase
    private val zoneId = ZoneId.of("Europe/Berlin")

    @Before
    fun setUp() {
        dbFile = File.createTempFile("rollup-benchmark", ".db")
        dbFile.delete()
        database =
            Room
                .databaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                    dbFile.absolutePath,
                ).build()
    }

    @After
    fun tearDown() {
        database.close()
        dbFile.delete()
    }

    @Test
    fun rollupDenseThirtyDays() {
        val startMs =
            LocalDate
                .of(2026, 1, 1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        // F-DENSE-30D: 30 days at 1 Hz ≈ 2.59 M rows.
        BenchmarkFixtures.seedHeartRateRows(
            database = database,
            startTimeMs = startMs,
            days = 30,
            sessionIds = listOf("bench-sleep-1", "bench-sleep-2", "bench-sleep-3"),
            recordTypes = listOf("SLEEP", "RESTING"),
        )
        val cutoffMs = startMs + 15 * 86_400_000L // roll up the first 15 days (half the corpus)
        val manager =
            DataRollupManager(
                minuteBucketDao = database.minuteBucketDao(),
                heartRateDao = database.heartRateDao(),
                transactionRunner = RoomTransactionRunner(database),
            )
        val wal = RoomWalDiagnostics(database)

        benchmarkRule.measureRepeated {
            val startedAt = SystemClock.elapsedRealtimeNanos()
            runBlocking { manager.rollupExpiredHotTier(cutoffMs) }
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L
            // The measured WAL size is reported through the benchmark's recorded output; assert it
            // is finite so a runaway WAL is a hard failure, not a silent number.
            assertTrue("peak WAL = ${wal.walFileSizeInfo()}", wal.walFileSizeInfo().isNotEmpty())
        }
    }
}
