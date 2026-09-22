package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.dao.getOrCreateSourceRef
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PERF-003: proves [MinuteRollupStreamer] never publishes a partial minute (the exact DB-002
 * defect class Phase 1 already fixed once), regardless of how the page boundary lands relative to
 * a minute boundary, and that it respects [MinuteRollupStreamer.GROUP_MINUTE_BUDGET] /
 * [MinuteRollupStreamer.SAMPLE_PAGE_SIZE] while applying the same plausibility predicate as the
 * single-pass query it replaces.
 */
@RunWith(RobolectricTestRunner::class)
class MinuteRollupStreamerTest {
    private lateinit var database: HealthDatabase
    private lateinit var streamer: MinuteRollupStreamer

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        streamer = MinuteRollupStreamer(database.heartRateDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun noGroupEverSplitsAMinute() =
        runBlocking {
            val ref = database.sourceRecordDao().getOrCreateSourceRef("hc-1", "HEART_RATE", 0L)
            // 10 minutes x 60 samples = 600 samples; a 25-sample page deliberately lands mid-minute.
            val samples =
                (0 until 10).flatMap { minute ->
                    (0 until 60).map { second ->
                        hr(ref, minute * 60_000L + second * 1_000L, 60 + second % 20)
                    }
                }
            database.heartRateDao().upsertAll(samples)

            val seenMinutes = mutableListOf<Set<Long>>()
            var totalSamples = 0
            streamer.streamGroups(fromMs = 0L, toMs = 10 * 60_000L, pageSize = 25, groupMinuteBudget = 3) { group ->
                seenMinutes += group.samples.map { it.timestampMs / 60_000L * 60_000L }.toSet()
                totalSamples += group.samples.size
                group.samples.groupBy { it.timestampMs / 60_000L }.forEach { (_, minuteSamples) ->
                    assertEquals("every minute in a group must be complete", 60, minuteSamples.size)
                }
            }

            assertEquals(600, totalSamples)
            val flattened = seenMinutes.flatten()
            assertEquals("no minute may appear in two groups", flattened.size, flattened.toSet().size)
        }

    @Test
    fun groupsRespectTheMinuteBudget() =
        runBlocking {
            val ref = database.sourceRecordDao().getOrCreateSourceRef("hc-1", "HEART_RATE", 0L)
            database.heartRateDao().upsertAll((0 until 10).map { hr(ref, it * 60_000L, 70) })

            val groupSizes = mutableListOf<Int>()
            streamer.streamGroups(fromMs = 0L, toMs = 10 * 60_000L, pageSize = 100, groupMinuteBudget = 4) { group ->
                groupSizes += group.samples.map { it.timestampMs / 60_000L }.distinct().size
            }

            assertTrue("minute budget exceeded: $groupSizes", groupSizes.all { it <= 4 })
            assertEquals(10, groupSizes.sum())
        }

    @Test
    fun implausibleSamplesAreExcludedJustLikeTheSinglePassQuery() =
        runBlocking {
            val ref = database.sourceRecordDao().getOrCreateSourceRef("hc-1", "HEART_RATE", 0L)
            database.heartRateDao().upsertAll(
                listOf(hr(ref, 0L, 70), hr(ref, 1_000L, 250), hr(ref, 2_000L, 20)),
            )

            var streamed = 0
            streamer.streamGroups(fromMs = 0L, toMs = 60_000L, pageSize = 10, groupMinuteBudget = 1) { group ->
                streamed += group.samples.size
            }

            assertEquals(1, streamed)
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
        deviceName = "watch",
    )
}
