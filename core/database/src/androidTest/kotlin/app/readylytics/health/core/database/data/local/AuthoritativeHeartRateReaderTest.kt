package app.readylytics.health.core.database.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.databaseschema.data.local.dao.getOrCreateSourceRef
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.databaseschema.data.local.entity.MinuteCoverageEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.model.domain.sync.link.SessionLinker
import app.readylytics.health.core.model.domain.sync.link.SessionSpan
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WP-17 Step 3/4 against a **real on-device SQLite file**, not an in-memory Robolectric database.
 *
 * Three things only a real instrumented run can establish, and that
 * `AuthoritativeHeartRateReaderEquivalenceTest` / `WarmTierRelinkTest` therefore deliberately do
 * not claim:
 *
 * 1. the raw-side and warm-side visibility predicates execute on the real SQLite build, including
 *    the epoch-floor `CASE` correction for pre-1970 timestamps (Robolectric ships its own SQLite,
 *    and integer `/` and `%` sign behaviour is exactly the kind of thing that can differ);
 * 2. a relink that throws mid-transaction leaves the *previously visible* projection intact after
 *    the database is closed and reopened -- i.e. the rollback really reached the file, not just an
 *    in-memory journal;
 * 3. `hr_source_minute_contributions`'s `ON DELETE RESTRICT` foreign key is enforced, so a source
 *    whose minutes were rolled up cannot be deleted out from under its evidence except through
 *    `SourceRecordDao.deleteBySourceRecordId`, after which the relink converges.
 */
@RunWith(AndroidJUnit4::class)
class AuthoritativeHeartRateReaderTest {
    private lateinit var context: Context
    private lateinit var database: HealthDatabase
    private lateinit var reader: AuthoritativeHeartRateReader

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getDatabasePath(DB_NAME).delete()
        database = openDatabase()
        runBlocking { database.healthMutationStateDao().getOrCreate() }
        reader = readerFor(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.getDatabasePath(DB_NAME).delete()
    }

    @Test
    fun quarantinedLegacyMinuteIsServedFromWarmTierOnly() =
        runBlocking {
            val ref = database.sourceRecordDao().getOrCreateSourceRef(SOURCE_ID, "HEART_RATE", 0L)
            seedLegacyMinute(BASE_MS)
            database.heartRateDao().upsertAll(listOf(hr(ref, BASE_MS + 1_000L, 200)))
            rollupManager(database).rollupExpiredHotTier(BASE_MS + MINUTE_MS)

            // Precondition: OD-1 really left the raw evidence under the cutoff.
            assertEquals(1, database.heartRateDao().countInRange(BASE_MS, BASE_MS + MINUTE_MS - 1))

            val range = reader.rangeIn(BASE_MS, BASE_MS + MINUTE_MS - 1)
            assertTrue(range.rawSamples.isEmpty())
            assertEquals(1, range.warmBuckets.size)

            val projection = reader.minuteBuckets(BASE_MS, BASE_MS + MINUTE_MS).single()
            assertEquals(10, projection.sampleCount)
            assertEquals(60.0, projection.avgBpm, 1e-4)
        }

    // The epoch-floor CASE correction: a pre-1970 sample must land in the same minute key here as
    // in `minute_coverage`, or a negative-timestamp minute would leak both tiers at once.
    @Test
    fun preEpochTimestampsUseTheSameMinuteKeyAsTheCoverageLedger() =
        runBlocking {
            val ref = database.sourceRecordDao().getOrCreateSourceRef(SOURCE_ID, "HEART_RATE", 0L)
            val minuteStart = -2 * MINUTE_MS
            database.heartRateDao().upsertAll(
                listOf(
                    hr(ref, minuteStart + 1_000L, 60),
                    hr(ref, minuteStart + 59_000L, 62),
                ),
            )
            seedLegacyMinute(minuteStart)

            // Both raw rows belong to the covered minute, so the raw side must hide both.
            assertTrue(reader.rangeIn(minuteStart, minuteStart + MINUTE_MS - 1).rawSamples.isEmpty())
            // The neighbouring minute has no coverage row, so its raw rows stay visible.
            database.heartRateDao().upsertAll(listOf(hr(ref, minuteStart - 1_000L, 70)))
            assertEquals(
                listOf(70),
                reader
                    .rangeIn(minuteStart - MINUTE_MS, minuteStart - 1L)
                    .rawSamples
                    .map { it.beatsPerMinute },
            )
        }

    @Test
    fun relinkFailureLeavesThePreviouslyVisibleProjectionIntactAfterReopen() =
        runBlocking {
            seedRolledUpMinutes()
            val before = visibleBuckets(database)

            val exploding =
                WarmTierRelinker(
                    selectionDao = database.minuteCoverageSelectionDao(),
                    minuteCoverageDao = database.minuteCoverageDao(),
                    minuteBucketDao = database.minuteBucketDao(),
                    publisher =
                        MinuteCoveragePublisher(
                            minuteBucketDao = database.minuteBucketDao(),
                            minuteCoverageDao = database.minuteCoverageDao(),
                            dirtyRangeDao = database.dirtyRangeDao(),
                            healthMutationStateDao = database.healthMutationStateDao(),
                        ),
                    // Deletes and inserts run, then the transaction throws before committing.
                    transactionRunner = ThrowAfterBodyTransactionRunner(RoomTransactionRunner(database)),
                    healthMutationStateDao = database.healthMutationStateDao(),
                )

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    exploding.relink(BASE_MS, BASE_MS + 3 * MINUTE_MS - 1, movedSleepSpans(), emptyList())
                }
            }

            database.close()
            val reopened = openDatabase()
            try {
                assertEquals(before, visibleBuckets(reopened))
            } finally {
                reopened.close()
                database = openDatabase()
            }
        }

    @Test
    fun rolledUpSourceCannotBeDeletedWithoutItsEvidenceAndRelinkConverges() =
        runBlocking {
            seedRolledUpMinutes()

            // RESTRICT is live: the raw delete path alone must not orphan the contributions.
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                runBlocking { database.sourceRecordDao().deleteSourceRecordRow(SOURCE_ID) }
            }

            // The supported path drops the contributions first, then the relink retires the minutes.
            database.sourceRecordDao().deleteBySourceRecordId(SOURCE_ID)
            val outcome =
                relinker(database).relink(
                    BASE_MS,
                    BASE_MS + 3 * MINUTE_MS - 1,
                    movedSleepSpans(),
                    emptyList(),
                )

            assertEquals(3, outcome.retired)
            assertTrue(visibleBuckets(database).isEmpty())
            assertTrue(reader.rangeIn(BASE_MS, BASE_MS + 3 * MINUTE_MS - 1).warmBuckets.isEmpty())
        }

    private fun openDatabase(): HealthDatabase =
        Room
            .databaseBuilder(context, HealthDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()

    private fun readerFor(db: HealthDatabase) =
        AuthoritativeHeartRateReader(
            heartRateDao = db.heartRateDao(),
            minuteBucketDao = db.minuteBucketDao(),
            coverageSelectionDao = db.minuteCoverageSelectionDao(),
        )

    private fun rollupManager(db: HealthDatabase) =
        DataRollupManager(
            minuteCoverageDao = db.minuteCoverageDao(),
            heartRateDao = db.heartRateDao(),
            publisher =
                MinuteCoveragePublisher(
                    minuteBucketDao = db.minuteBucketDao(),
                    minuteCoverageDao = db.minuteCoverageDao(),
                    dirtyRangeDao = db.dirtyRangeDao(),
                    healthMutationStateDao = db.healthMutationStateDao(),
                ),
            transactionRunner = RoomTransactionRunner(db),
            dirtyRangeDao = db.dirtyRangeDao(),
            healthMutationStateDao = db.healthMutationStateDao(),
        )

    private fun relinker(db: HealthDatabase) =
        WarmTierRelinker(
            selectionDao = db.minuteCoverageSelectionDao(),
            minuteCoverageDao = db.minuteCoverageDao(),
            minuteBucketDao = db.minuteBucketDao(),
            publisher =
                MinuteCoveragePublisher(
                    minuteBucketDao = db.minuteBucketDao(),
                    minuteCoverageDao = db.minuteCoverageDao(),
                    dirtyRangeDao = db.dirtyRangeDao(),
                    healthMutationStateDao = db.healthMutationStateDao(),
                ),
            transactionRunner = RoomTransactionRunner(db),
            healthMutationStateDao = db.healthMutationStateDao(),
        )

    private suspend fun seedRolledUpMinutes() {
        val ref = database.sourceRecordDao().getOrCreateSourceRef(SOURCE_ID, "HEART_RATE", 0L)
        database.sleepSessionDao().upsertAll(
            listOf(
                SleepSessionEntity(
                    id = "sleep-1",
                    startTime = BASE_MS,
                    endTime = BASE_MS + 3 * MINUTE_MS - 1,
                    durationMinutes = 0,
                    efficiency = 0f,
                    deepSleepMinutes = 0,
                    remSleepMinutes = 0,
                    lightSleepMinutes = 0,
                    awakeMinutes = 0,
                ),
            ),
        )
        val spans = listOf(SessionSpan("sleep-1", BASE_MS, BASE_MS + 3 * MINUTE_MS - 1))
        val samples =
            (0 until 3).flatMap { minute ->
                OFFSETS.mapIndexed { index, offsetMs ->
                    val timestampMs = BASE_MS + minute * MINUTE_MS + offsetMs
                    val link = SessionLinker.resolve(timestampMs, spans, emptyList())
                    HeartRateRecordEntity(
                        sourceRecordRef = ref,
                        timestampMs = timestampMs,
                        beatsPerMinute = 50 + minute * OFFSETS.size + index,
                        recordType = link.recordType,
                        sessionId = link.sessionId,
                    )
                }
            }
        database.heartRateDao().upsertAll(samples)
        rollupManager(database).rollupExpiredHotTier(BASE_MS + 3 * MINUTE_MS)
        assertEquals(0, database.heartRateDao().count())
    }

    private fun movedSleepSpans() = listOf(SessionSpan("sleep-1", BASE_MS, BASE_MS + MINUTE_MS + 39_500L))

    private suspend fun visibleBuckets(db: HealthDatabase): List<HrMinuteBucketEntity> =
        db
            .minuteBucketDao()
            .getVisibleBucketsInMinuteRange(BASE_MS, BASE_MS + 4 * MINUTE_MS)
            .sortedWith(compareBy({ it.bucketStartMs }, { it.recordType }, { it.sessionId }, { it.deviceName }))

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

    private fun hr(
        ref: Long,
        timestampMs: Long,
        bpm: Int,
    ) = HeartRateRecordEntity(
        sourceRecordRef = ref,
        timestampMs = timestampMs,
        beatsPerMinute = bpm,
        recordType = "RESTING",
        sessionId = null,
    )

    private companion object {
        const val DB_NAME = "authoritative_hr_reader_test.db"
        const val MINUTE_MS = 60_000L
        const val SOURCE_ID = "src-a"

        /** A post-1970 minute-aligned anchor, so the fixture's own minute keys are exact. */
        const val BASE_MS = 1_700_000_040_000L

        val OFFSETS = listOf(1_000L, 20_000L, 40_000L, 58_000L)
    }
}

/**
 * Runs the caller's block inside a real Room transaction and then throws, so the transaction is
 * rolled back after every write the block performed. Proves the rollback reaches the database file,
 * which a mocked transaction runner cannot.
 */
private class ThrowAfterBodyTransactionRunner(
    private val delegate: RoomTransactionRunner,
) : app.readylytics.health.core.model.domain.repository.TransactionRunner {
    override suspend fun <T> runInTransaction(block: suspend () -> T): T =
        delegate.runInTransaction {
            block()
            error("injected failure after the relink's writes, before commit")
        }
}
