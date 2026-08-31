package app.readylytics.health.databasebenchmark.data.migration

import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.os.SystemClock
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.model.domain.migration.V7MigrationPhase
import app.readylytics.health.core.model.domain.migration.V7MigrationResult
import app.readylytics.health.data.migration.V7DatabaseBenchmarkDriver
import app.readylytics.health.databasebenchmark.di.benchmarkIoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

data class DatabaseBenchmarkResult(
    val schemaVersion: Int,
    val databaseBytes: Long,
    val ingestRowsPerSecond: Double,
    val ingestSamplesRowsPerSecond: List<Double>,
    val migrationDurationMs: Long,
    val requiredBytes: Long,
    val peakDiskBytes: Long,
    val peakAdditionalBytes: Long,
)

private data class FullMigrationResult(
    val durationMs: Long,
    val requiredBytes: Long,
    val peakDiskBytes: Long,
    val peakAdditionalBytes: Long,
)

private data class ResumeBenchmarkResult(
    val copiedRowsBeforeCancellation: Long,
    val resumeDurationMs: Long,
    val result: V7MigrationResult,
)

@LargeTest
@RunWith(AndroidJUnit4::class)
class V7DatabaseMigrationBenchmark {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            HealthDatabase::class.java,
        )

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val fixtures by lazy { DatabaseBenchmarkFixture(context, helper) }

    @After
    fun cleanUp() {
        fixtures.cleanUp()
    }

    @Test
    fun oneMillionRowV7Gate() =
        runBlocking {
            val v6 = fixtures.createTemplate(version = 6, suffix = "v6-template")
            val v7 = fixtures.createTemplate(version = 7, suffix = "v7-template")
            val v6Bytes = v6.file.length()
            val v7Bytes = v7.file.length()

            warmUpDiscardedPairs(v6, v7)
            val (v6Samples, v7Samples) = measureBalancedPairs(v6, v7)
            val fullMigration = measureFullMigration(fixtures.copyTemplate(v6, "full-migration"))
            val resume = measureCancellationAndResume(fixtures.copyTemplate(v6, "resume-migration"))

            val v6Result =
                DatabaseBenchmarkResult(
                    schemaVersion = 6,
                    databaseBytes = v6Bytes,
                    ingestRowsPerSecond = median(v6Samples),
                    ingestSamplesRowsPerSecond = v6Samples,
                    migrationDurationMs = 0L,
                    requiredBytes = 0L,
                    peakDiskBytes = 0L,
                    peakAdditionalBytes = 0L,
                )
            val v7Result =
                DatabaseBenchmarkResult(
                    schemaVersion = 7,
                    databaseBytes = v7Bytes,
                    ingestRowsPerSecond = median(v7Samples),
                    ingestSamplesRowsPerSecond = v7Samples,
                    migrationDurationMs = fullMigration.durationMs,
                    requiredBytes = fullMigration.requiredBytes,
                    peakDiskBytes = fullMigration.peakDiskBytes,
                    peakAdditionalBytes = fullMigration.peakAdditionalBytes,
                )

            val throughputGain =
                (v7Result.ingestRowsPerSecond - v6Result.ingestRowsPerSecond) /
                    v6Result.ingestRowsPerSecond
            val sizeReduction =
                (v6Result.databaseBytes - v7Result.databaseBytes).toDouble() /
                    v6Result.databaseBytes

            report(v6Result, v7Result, throughputGain, sizeReduction, resume)
            assertTrue(
                "DB-001 gate failed: throughput=$throughputGain size=$sizeReduction",
                throughputGain >= 0.30 || sizeReduction >= 0.25,
            )
        }

    private fun warmUpDiscardedPairs(
        v6: Fixture,
        v7: Fixture,
    ) {
        fixtures.measureFreshIngest(v6, "warmup-0-v6")
        fixtures.measureFreshIngest(v7, "warmup-0-v7")
        fixtures.measureFreshIngest(v7, "warmup-1-v7")
        fixtures.measureFreshIngest(v6, "warmup-1-v6")
    }

    private fun measureBalancedPairs(
        v6: Fixture,
        v7: Fixture,
    ): Pair<List<Double>, List<Double>> {
        val v6Samples = mutableListOf<Double>()
        val v7Samples = mutableListOf<Double>()
        repeat(MEASURED_PAIR_COUNT) { pairIndex ->
            if (pairIndex % 2 == 0) {
                v6Samples += fixtures.measureFreshIngest(v6, "pair-$pairIndex-v6")
                v7Samples += fixtures.measureFreshIngest(v7, "pair-$pairIndex-v7")
            } else {
                v7Samples += fixtures.measureFreshIngest(v7, "pair-$pairIndex-v7")
                v6Samples += fixtures.measureFreshIngest(v6, "pair-$pairIndex-v6")
            }
        }
        return v6Samples to v7Samples
    }

    private suspend fun measureFullMigration(fixture: Fixture): FullMigrationResult {
        val failClosedDriver = V7DatabaseBenchmarkDriver(context, fixture.file) { 0L }
        val preflight = failClosedDriver.migrate {}
        assertTrue("Production space preflight must fail closed", preflight is V7MigrationResult.InsufficientSpace)
        val requiredBytes = (preflight as V7MigrationResult.InsufficientSpace).requiredBytes

        val initialBytes = databaseFootprintBytes(fixture.file)
        val peakBytes = AtomicLong(initialBytes)
        val actualDriver =
            V7DatabaseBenchmarkDriver(context, fixture.file) { file ->
                StatFs(requireNotNull(file.parentFile).absolutePath).availableBytes
            }
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val result =
            coroutineScope {
                val sampler =
                    launch(benchmarkIoDispatcher) {
                        while (isActive) {
                            peakBytes.updateAndGet { maxOf(it, databaseFootprintBytes(fixture.file)) }
                            delay(DISK_SAMPLE_INTERVAL_MS)
                        }
                    }
                try {
                    actualDriver.migrate {}
                } finally {
                    sampler.cancelAndJoin()
                    peakBytes.updateAndGet { maxOf(it, databaseFootprintBytes(fixture.file)) }
                }
            }
        val durationMs = nanosToMillis(SystemClock.elapsedRealtimeNanos() - startedAt)
        assertEquals(V7MigrationResult.Complete, result)
        fixtures.assertMigratedCounts(fixture)
        return FullMigrationResult(
            durationMs = durationMs,
            requiredBytes = requiredBytes,
            peakDiskBytes = peakBytes.get(),
            peakAdditionalBytes = (peakBytes.get() - initialBytes).coerceAtLeast(0L),
        )
    }

    private suspend fun measureCancellationAndResume(fixture: Fixture): ResumeBenchmarkResult {
        val driver =
            V7DatabaseBenchmarkDriver(context, fixture.file) { file ->
                StatFs(requireNotNull(file.parentFile).absolutePath).availableBytes
            }
        var cancelled = false
        try {
            driver.migrate { progress ->
                if (progress.phase == V7MigrationPhase.COPY_HEART_RATE &&
                    progress.copiedRows == BenchmarkConstants.MIGRATION_COPY_BATCH_ROWS.toLong()
                ) {
                    throw CancellationException("DB-001 cancel after first durable copy batch")
                }
            }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue("Migration must cancel after one durable copy batch", cancelled)
        val copiedRows = fixtures.checkpointHeartRateCopiedRows(fixture)
        assertEquals(BenchmarkConstants.MIGRATION_COPY_BATCH_ROWS.toLong(), copiedRows)

        val startedAt = SystemClock.elapsedRealtimeNanos()
        val result = driver.migrate {}
        val resumeDurationMs = nanosToMillis(SystemClock.elapsedRealtimeNanos() - startedAt)
        assertEquals(V7MigrationResult.Complete, result)
        fixtures.assertMigratedCounts(fixture)
        return ResumeBenchmarkResult(copiedRows, resumeDurationMs, result)
    }

    private fun report(
        v6: DatabaseBenchmarkResult,
        v7: DatabaseBenchmarkResult,
        throughputGain: Double,
        sizeReduction: Double,
        resume: ResumeBenchmarkResult,
    ) {
        val report =
            "DB-001 device=${Build.MANUFACTURER} ${Build.MODEL} api=${Build.VERSION.SDK_INT}; " +
                "v6=$v6; v7=$v7; throughputGain=$throughputGain; sizeReduction=$sizeReduction; resume=$resume"
        InstrumentationRegistry.getInstrumentation().addResults(
            Bundle().apply { putString("DB-001 benchmark", report) },
        )
        println(report)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun nanosToMillis(nanos: Long): Long = nanos / 1_000_000L

    private companion object {
        const val MEASURED_PAIR_COUNT = 8
        const val DISK_SAMPLE_INTERVAL_MS = 10L
    }
}
