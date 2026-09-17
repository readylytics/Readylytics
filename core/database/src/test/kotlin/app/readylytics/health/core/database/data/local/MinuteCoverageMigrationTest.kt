package app.readylytics.health.core.database.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.core.database.data.local.migration.Migration21To22
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * WP-17 Step 1/2: migration 21→22 must preserve every pre-existing warm bucket as
 * `LEGACY_WARM`/`LEGACY_UNKNOWN`, keep source IDs stable, invent no lineage, and never delete raw
 * evidence -- including for a minute where raw and warm data already overlap.
 */
@RunWith(RobolectricTestRunner::class)
class MinuteCoverageMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HealthDatabase::class.java)

    @Test
    fun migrationExecutesExpectedSchemaAlter() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)

        Migration21To22.migrate(db)

        verify {
            db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `minute_coverage`") })
            db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `hr_source_minute_contributions`") })
            db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `staged_hr_sources`") })
            db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `staged_hr_samples`") })
            db.execSQL("ALTER TABLE `hr_minute_buckets` ADD COLUMN `generation` INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Test
    fun migrate21To22PreservesLegacyWarmBucketsAndRawEvidence() {
        seedVersion21Fixture()

        val database = helper.runMigrationsAndValidate(TEST_DATABASE, 22, true, *DatabaseMigrations.all)

        assertGenerationDefaultedToZero(database)
        assertLegacyCoverageInitialized(database)
        assertRawEvidencePreserved(database)
    }

    @Test
    fun migrate21To22LeavesNoForeignKeyViolationsForRealContributions() {
        seedVersion21Fixture()
        val database = helper.runMigrationsAndValidate(TEST_DATABASE, 22, true, *DatabaseMigrations.all)

        // Non-vacuous guard: prove `PRAGMA foreign_key_check` actually reports a dangling
        // `sourceRecordRef` before asserting that a genuine contribution row produces no rows.
        database.execSQL("PRAGMA foreign_keys = OFF")
        database.execSQL(insertContributionSql(sourceRecordRef = 999L, bucketStartMs = 0L))
        database.query("PRAGMA foreign_key_check").use { cursor ->
            assertTrue("foreign_key_check must flag a dangling sourceRecordRef", cursor.moveToFirst())
        }
        database.execSQL("DELETE FROM hr_source_minute_contributions WHERE sourceRecordRef = 999")

        database.execSQL(insertContributionSql(sourceRecordRef = 1L, bucketStartMs = 0L))
        database.query("SELECT COUNT(*) FROM hr_source_minute_contributions").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        database.query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse("A contribution with a valid source FK must not be flagged", cursor.moveToFirst())
        }
    }

    /**
     * Three distinct minute shapes at v21:
     * - minute 0: a warm bucket AND a raw sample -> the pre-existing overlap OD-1 calls unresolved
     * - minute 60_000: a warm bucket only
     * - minute 180_000: a raw sample only, with no warm bucket at all
     */
    private fun seedVersion21Fixture() {
        helper.createDatabase(TEST_DATABASE, 21).apply {
            execSQL(
                "INSERT INTO health_source_records " +
                    "(id, sourceRecordId, recordType, createdAtMs, metadataState, sourceRevision) " +
                    "VALUES (1, 'src-1', 'HEART_RATE', 0, 'UNKNOWN', 0)",
            )
            execSQL(rawSampleSql(timestampMs = 1_000L, bpm = 60))
            execSQL(rawSampleSql(timestampMs = 185_000L, bpm = 72))
            execSQL(warmBucketSql(bucketStartMs = 0L, minBpm = 50, maxBpm = 70, avgBpm = 60.0, sampleCount = 10))
            execSQL(warmBucketSql(bucketStartMs = 60_000L, minBpm = 55, maxBpm = 75, avgBpm = 65.0, sampleCount = 15))
            close()
        }
    }

    private fun assertGenerationDefaultedToZero(database: SupportSQLiteDatabase) {
        database
            .query("SELECT bucketStartMs, generation FROM hr_minute_buckets ORDER BY bucketStartMs")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
                assertEquals(0L, cursor.getLong(1))

                assertTrue(cursor.moveToNext())
                assertEquals(60_000L, cursor.getLong(0))
                assertEquals(0L, cursor.getLong(1))

                assertFalse(cursor.moveToNext())
            }
    }

    /**
     * Coverage is derived from existing warm buckets only. The raw-only minute (180_000) gets NO
     * coverage row -- it keeps backward-compatible raw-only reading, and no lineage is invented for
     * it. The overlapping minute (0) keeps the last published warm projection at legacy quality
     * rather than concatenating the raw sample into it.
     */
    private fun assertLegacyCoverageInitialized(database: SupportSQLiteDatabase) {
        database
            .query("SELECT bucketStartMs, visibleGeneration, tier, quality FROM minute_coverage ORDER BY bucketStartMs")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
                assertEquals(0L, cursor.getLong(1))
                assertEquals("LEGACY_WARM", cursor.getString(2))
                assertEquals("LEGACY_UNKNOWN", cursor.getString(3))

                assertTrue(cursor.moveToNext())
                assertEquals(60_000L, cursor.getLong(0))
                assertEquals(0L, cursor.getLong(1))
                assertEquals("LEGACY_WARM", cursor.getString(2))
                assertEquals("LEGACY_UNKNOWN", cursor.getString(3))

                assertFalse("The raw-only minute must not get an invented coverage row", cursor.moveToNext())
            }
    }

    private fun assertRawEvidencePreserved(database: SupportSQLiteDatabase) {
        database
            .query("SELECT timestampMs, sourceRecordRef FROM heart_rate_records ORDER BY timestampMs")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1_000L, cursor.getLong(0))
                assertEquals(1L, cursor.getLong(1))

                assertTrue(cursor.moveToNext())
                assertEquals(185_000L, cursor.getLong(0))
                assertEquals(1L, cursor.getLong(1))

                assertFalse(cursor.moveToNext())
            }
    }

    private fun rawSampleSql(
        timestampMs: Long,
        bpm: Int,
    ) = "INSERT INTO heart_rate_records " +
        "(sourceRecordRef, timestampMs, beatsPerMinute, recordType, sessionId, deviceName) " +
        "VALUES (1, $timestampMs, $bpm, 'RESTING', NULL, 'device-1')"

    private fun warmBucketSql(
        bucketStartMs: Long,
        minBpm: Int,
        maxBpm: Int,
        avgBpm: Double,
        sampleCount: Int,
    ) = "INSERT INTO hr_minute_buckets " +
        "(bucketStartMs, bucketEndMs, minBpm, maxBpm, avgBpm, sampleCount, recordType, sessionId, deviceName) " +
        "VALUES ($bucketStartMs, ${bucketStartMs + 60_000L}, $minBpm, $maxBpm, $avgBpm, $sampleCount, " +
        "'RESTING', '', '')"

    private fun insertContributionSql(
        sourceRecordRef: Long,
        bucketStartMs: Long,
    ) = "INSERT INTO hr_source_minute_contributions " +
        "(sourceRecordRef, bucketStartMs, generation, firstSampleMs, lastSampleMs, deviceName, bpmHistogram) " +
        "VALUES ($sourceRecordRef, $bucketStartMs, 1, 1000, 5000, 'device-1', 'v1:60:2')"

    private companion object {
        const val TEST_DATABASE = "migration-21-22-test"
    }
}
