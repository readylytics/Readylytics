package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.entity.StagedHeartRateEntity
import app.readylytics.health.core.databaseschema.data.local.entity.StagedSourceMetadataEntity
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * OD-1 decision evidence (WP-17 / DB-002): measures the real on-disk and write cost of
 * per-source-per-minute `hr_source_minute_contributions` under the two source distributions the
 * gate names, so `benchmark/BASELINE.md`'s DB-002 row can cite measured numbers.
 *
 * - `one-source-per-sample`: every raw sample carries its own `health_source_records` parent, so a
 *   minute accumulates one contribution per sample. Upper bound for contribution fan-out.
 * - `dense-parent`: one parent covers the whole window, so a minute yields exactly one
 *   contribution. Lower bound, and the shape real providers actually emit.
 *
 * Each figure is attributed by taking a WAL-checkpointed page snapshot between stages: sources,
 * then raw samples, then the rollup, then a whole-table delete of coverage and of contributions.
 * Auto-vacuum is on for this database, so a delete's page-count DROP is exactly what the deleted
 * rows plus their index entries occupied. `peakWalBytes` is the `-wal` length still present when
 * the publish transaction committed, i.e. before the explicit checkpoint -- with WAL
 * auto-checkpointing it is a lower bound on the writer's WAL footprint, not a hard maximum.
 * Sub-page costs (e.g. the single source row of the dense-parent case) report as 0.
 *
 * **Scope.** A real file-backed SQLite database in WAL mode -- page counts, file length and `-wal`
 * length are genuine -- but on the host JVM under Robolectric, WITHOUT SQLCipher. Production pages
 * carry SQLCipher's per-page reserve, so absolute bytes are a floor rather than the device figure;
 * the ratio between the two distributions, which is what OD-1 turns on, is unaffected. The
 * device-level SQLCipher fixture belongs in `:database-benchmark`, which does not currently
 * compile for reasons unrelated to WP-17.
 *
 * Assertions are structural only (row fan-out, positive storage cost, ordering between the two
 * distributions) so this stays a stable gate rather than a performance-threshold flake.
 */
@RunWith(RobolectricTestRunner::class)
class OdOneContributionCostMeasurementTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `measures contribution cost across source distributions`() {
        val dense = measure("dense-parent", oneSourcePerSample = false)
        val perSample = measure("one-source-per-sample", oneSourcePerSample = true)

        assertEquals(MINUTES, dense.coverageRows)
        assertEquals(MINUTES, dense.contributionRows)
        assertEquals(MINUTES, perSample.coverageRows)
        assertEquals(MINUTES * SAMPLES_PER_MINUTE, perSample.contributionRows)
        assertTrue("Contribution storage must be measurable", perSample.contributionBytes > 0L)
        assertTrue(
            "Per-sample fan-out must cost more contribution storage than a dense parent",
            perSample.contributionBytes > dense.contributionBytes,
        )
        assertTrue(
            "Per-sample fan-out must also multiply source-record storage",
            perSample.sourceBytes > dense.sourceBytes,
        )
    }

    @Suppress("LongMethod") // One linear measurement script; splitting it would hide the stage order
    private fun measure(
        label: String,
        oneSourcePerSample: Boolean,
    ): Measurement =
        runBlocking {
            val sourceCount = if (oneSourcePerSample) MINUTES * SAMPLES_PER_MINUTE else 1
            val storageFile = tempFolder.newFile("od1-$label-storage.db")
            val storage = openDatabase(storageFile)
            val deletionFile = tempFolder.newFile("od1-$label-deletion.db")
            val deletion = openDatabase(deletionFile)
            try {
                checkpoint(storage)
                val afterOpen = pageBytes(storage)

                seedSources(storage, sourceCount)
                checkpoint(storage)
                val afterSources = pageBytes(storage)

                seedSamples(storage, oneSourcePerSample)
                checkpoint(storage)
                val afterSamples = pageBytes(storage)

                val txRunner = CountingTransactionRunner(RoomTransactionRunner(storage))
                val startedNanos = System.nanoTime()
                rollupManager(storage, txRunner).rollupExpiredHotTier(app.readylytics.health.core.model.domain.sync.ScoringRunContext.capture(app.readylytics.health.core.model.domain.preferences.UserPreferences(), java.time.Instant.ofEpochMilli(WINDOW_MS)), WINDOW_MS)
                val rollupNanos = System.nanoTime() - startedNanos
                val peakWalBytes = walBytes(storageFile)
                checkpoint(storage)
                val afterRollup = pageBytes(storage)

                val contributionRows = storage.minuteCoverageMaintenanceDao().countContributions()
                val coverageRows = storage.minuteCoverageMaintenanceDao().countCoverage()
                val bucketRows = storage.minuteBucketMaintenanceDao().count()
                val stagingPeakBytes = measureStagingPeak(storage, storageFile)

                // Whole-table deletes attribute storage to each table. Auto-vacuum returns the
                // freed pages immediately, so the page-count DROP is what those rows occupied.
                storage.minuteCoverageMaintenanceDao().deleteAllCoverage()
                checkpoint(storage)
                val afterCoverageDelete = pageBytes(storage)
                storage.minuteCoverageMaintenanceDao().deleteAllContributions()
                checkpoint(storage)
                val afterContributionDelete = pageBytes(storage)

                // Deletion cost of one Health-Connect source delta-delete, on its own database so
                // the contribution rows are still intact when it runs.
                seedSources(deletion, sourceCount)
                seedSamples(deletion, oneSourcePerSample)
                rollupManager(deletion, RoomTransactionRunner(deletion)).rollupExpiredHotTier(app.readylytics.health.core.model.domain.sync.ScoringRunContext.capture(app.readylytics.health.core.model.domain.preferences.UserPreferences(), java.time.Instant.ofEpochMilli(WINDOW_MS)), WINDOW_MS)
                checkpoint(deletion)
                val beforeSourceDelete = pageBytes(deletion)
                val contributionsBeforeDelete = deletion.minuteCoverageMaintenanceDao().countContributions()
                val victim = deletion.sourceRecordDao().getAll().last().sourceRecordId
                val deletionStarted = System.nanoTime()
                deletion.sourceRecordDao().deleteBySourceRecordId(victim)
                val deletionNanos = System.nanoTime() - deletionStarted
                checkpoint(deletion)
                val contributionsAfterDelete = deletion.minuteCoverageMaintenanceDao().countContributions()

                Measurement(
                    label = label,
                    sourceRows = sourceCount,
                    contributionRows = contributionRows,
                    coverageRows = coverageRows,
                    bucketRows = bucketRows,
                    sourceBytes = afterSources - afterOpen,
                    rawSampleBytes = afterSamples - afterSources,
                    netRollupBytes = afterRollup - afterSamples,
                    coverageBytes = afterRollup - afterCoverageDelete,
                    contributionBytes = afterCoverageDelete - afterContributionDelete,
                    measuredContributionRows = contributionRows,
                    peakWalBytes = peakWalBytes,
                    rollupNanos = rollupNanos,
                    transactions = txRunner.transactionCount,
                    singleSourceDeleteNanos = deletionNanos,
                    singleSourceFreedBytes = beforeSourceDelete - pageBytes(deletion),
                    singleSourceContributionsRemoved = contributionsBeforeDelete - contributionsAfterDelete,
                    stagingPeakBytes = stagingPeakBytes,
                ).also(::report)
            } finally {
                storage.close()
                deletion.close()
            }
        }

    private fun rollupManager(
        database: HealthDatabase,
        txRunner: TransactionRunner,
    ) = DataRollupManager(
        coordinator = TestHealthMutationCoordinator,
        minuteCoverageDao = database.minuteCoverageDao(),
        heartRateDao = database.heartRateDao(),
        publisher =
            MinuteCoveragePublisher(
                database.minuteBucketDao(),
                database.minuteCoverageDao(),
            ),
        transactionRunner = txRunner,
    )

    private fun openDatabase(file: File): HealthDatabase =
        Room
            .databaseBuilder(
                ApplicationProvider.getApplicationContext(),
                HealthDatabase::class.java,
                file.absolutePath,
            ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries()
            .build()

    private fun seedSources(
        database: HealthDatabase,
        sourceCount: Int,
    ) = inTransaction(database) { db ->
        for (id in 1..sourceCount) {
            db.execSQL(sourceSql(id.toLong()))
        }
    }

    /**
     * Seeds [MINUTES] complete minutes of raw samples via raw SQL in one transaction: this
     * measures publication cost, not ingestion cost, and the distribution must be exact.
     */
    private fun seedSamples(
        database: HealthDatabase,
        oneSourcePerSample: Boolean,
    ) = inTransaction(database) { db ->
        var sampleIndex = 0
        for (minute in 0 until MINUTES) {
            for (sample in 0 until SAMPLES_PER_MINUTE) {
                sampleIndex += 1
                val sourceRef = if (oneSourcePerSample) sampleIndex.toLong() else 1L
                val timestampMs = minute * MINUTE_MS + sample * (MINUTE_MS / SAMPLES_PER_MINUTE)
                db.execSQL(sampleSql(sourceRef, timestampMs, MIN_BPM + (sample % BPM_SPREAD)))
            }
        }
    }

    private inline fun inTransaction(
        database: HealthDatabase,
        body: (androidx.sqlite.db.SupportSQLiteDatabase) -> Unit,
    ) {
        val db = database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            body(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Peak disk held by staging one hour of the same payload as a complete refresh unit. */
    private suspend fun measureStagingPeak(
        database: HealthDatabase,
        file: File,
    ): Long {
        checkpoint(database)
        val before = pageBytes(database)
        val store =
            HeartRateRefreshStagingStore(
                database.heartRateRefreshStagingDao(),
                RoomTransactionRunner(database),
            )
        val samples =
            (0 until STAGED_MINUTES * SAMPLES_PER_MINUTE).map { index ->
                StagedHeartRateEntity(
                    runId = STAGED_RUN_ID,
                    sourceId = STAGED_SOURCE_ID,
                    timestampMs = index * (MINUTE_MS / SAMPLES_PER_MINUTE),
                    beatsPerMinute = MIN_BPM + (index % BPM_SPREAD),
                    recordType = "RESTING",
                    sessionId = null,
                    deviceName = "bench-device",
                )
            }
        store.stage(STAGED_RUN_ID, STAGED_SOURCE_ID, stagedMetadata(), samples)
        val peak = pageBytes(database) - before + walBytes(file)
        store.clearRun(STAGED_RUN_ID)
        checkpoint(database)
        return peak
    }

    private fun stagedMetadata() =
        StagedSourceMetadataEntity(
            runId = STAGED_RUN_ID,
            sourceId = STAGED_SOURCE_ID,
            recordType = "HEART_RATE",
            originPackage = "app.readylytics.measurement",
            startMs = 0L,
            endExclusiveMs = STAGED_MINUTES * MINUTE_MS,
            lastModifiedMs = 0L,
            payloadComplete = true,
        )

    private fun pageBytes(database: HealthDatabase): Long =
        longPragma(database, "PRAGMA page_count") * longPragma(database, "PRAGMA page_size")

    private fun longPragma(
        database: HealthDatabase,
        pragma: String,
    ): Long =
        database.openHelper.writableDatabase
            .query(pragma)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun checkpoint(database: HealthDatabase) {
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
    }

    private fun walBytes(file: File): Long =
        File(file.path + "-wal").let { if (it.exists()) it.length() else 0L }

    private fun report(m: Measurement) {
        val perSourceMinute = m.contributionBytes.toDouble() / m.measuredContributionRows
        val perPublishedMinute = (m.contributionBytes + m.coverageBytes).toDouble() / m.coverageRows
        println(
            "OD1_METRIC dist=${m.label} sourceRows=${m.sourceRows} contribRows=${m.contributionRows} " +
                "coverageRows=${m.coverageRows} bucketRows=${m.bucketRows} " +
                "sourceTableBytes=${m.sourceBytes} rawSampleBytes=${m.rawSampleBytes} " +
                "netRollupPageDeltaBytes=${m.netRollupBytes} coverageBytes=${m.coverageBytes} " +
                "contributionBytes=${m.contributionBytes} " +
                "contributionRowsMeasured=${m.measuredContributionRows} " +
                "bytesPerSourceMinute=%.1f bytesPerPublishedMinute=%.1f ".format(
                    perSourceMinute,
                    perPublishedMinute,
                ) + "peakWalBytes=${m.peakWalBytes} rollupMs=%.1f ".format(m.rollupNanos / 1_000_000.0) +
                "transactions=${m.transactions} " +
                "singleSourceDeleteMs=%.2f ".format(m.singleSourceDeleteNanos / 1_000_000.0) +
                "singleSourceFreedBytes=${m.singleSourceFreedBytes} " +
                "singleSourceContributionsRemoved=${m.singleSourceContributionsRemoved} " +
                "stagingPeakBytes=${m.stagingPeakBytes}",
        )
    }

    private fun sourceSql(id: Long) =
        "INSERT INTO health_source_records " +
            "(id, sourceRecordId, recordType, createdAtMs, metadataState, sourceRevision) " +
            "VALUES ($id, 'od1-src-$id', 'HEART_RATE', 0, 'AUTHORITATIVE', 0)"

    private fun sampleSql(
        sourceRef: Long,
        timestampMs: Long,
        bpm: Int,
    ) = "INSERT INTO heart_rate_records " +
        "(sourceRecordRef, timestampMs, beatsPerMinute, recordType, sessionId, deviceName) " +
        "VALUES ($sourceRef, $timestampMs, $bpm, 'RESTING', NULL, 'bench-device')"

    private class CountingTransactionRunner(
        private val delegate: TransactionRunner,
    ) : TransactionRunner {
        var transactionCount: Int = 0
            private set

        override suspend fun <T> runInTransaction(block: suspend () -> T): T {
            transactionCount++
            return delegate.runInTransaction(block)
        }
    }

    private data class Measurement(
        val label: String,
        val sourceRows: Int,
        val contributionRows: Int,
        val coverageRows: Int,
        val bucketRows: Int,
        val sourceBytes: Long,
        val rawSampleBytes: Long,
        val netRollupBytes: Long,
        val coverageBytes: Long,
        val contributionBytes: Long,
        val measuredContributionRows: Int,
        val peakWalBytes: Long,
        val rollupNanos: Long,
        val transactions: Int,
        val singleSourceDeleteNanos: Long,
        val singleSourceFreedBytes: Long,
        val singleSourceContributionsRemoved: Int,
        val stagingPeakBytes: Long,
    )

    private companion object {
        const val MINUTE_MS = 60_000L
        const val MINUTES = 720
        const val SAMPLES_PER_MINUTE = 12
        const val STAGED_MINUTES = 60
        const val WINDOW_MS = MINUTES * MINUTE_MS
        const val MIN_BPM = 55
        const val BPM_SPREAD = 20
        const val STAGED_RUN_ID = "od1-run"
        const val STAGED_SOURCE_ID = "od1-source"
    }
}
