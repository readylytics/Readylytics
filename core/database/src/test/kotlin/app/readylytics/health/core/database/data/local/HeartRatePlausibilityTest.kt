package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.database.data.repository.ScoringHistoryRepositoryImpl
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * R2-DB-003: characterizes and then locks in the 30-230 bpm plausibility predicate across every
 * scoring-facing [app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao] query,
 * and the equivalent 1.0-200.0 ms predicate for the scoring-facing
 * [app.readylytics.health.core.databaseschema.data.local.dao.HrvDao] queries (see the KDoc on
 * `HrvDao.getSleepRmssdForSession` for why 1.0-200.0 was chosen).
 */
@RunWith(RobolectricTestRunner::class)
class HeartRatePlausibilityTest {
    private lateinit var database: HealthDatabase
    private val startMs = Instant.parse("2026-01-11T22:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        runBlocking {
            val sourceRef = database.sourceRecordDao().getOrCreateSourceRef("plausibility-src", "HEART_RATE", 0L)
            // Two outliers, deliberately on both sides of the plausible range: a HIGH one (250bpm,
            // i==5) that a MAX-shaped query could pick up, and a LOW one (20bpm, i==2) that a
            // MIN-shaped query could pick up. A fixture with only a high outlier cannot exercise the
            // MIN-based queries (getMinHrTimestamp/getMinHrInRange) at all, and leaves the filtered
            // vs. unfiltered average close enough that a loose ">200" assertion passes either way --
            // see R2-DB-003 review finding. The remaining eight values (60,61,63,64,66,67,68,69) are
            // the exact set every "filtered" assertion below is computed against by hand.
            val samples =
                (0 until 10).map { i ->
                    HeartRateRecordEntity(
                        sourceRecordRef = sourceRef,
                        timestampMs = startMs + i * 60_000L,
                        beatsPerMinute =
                            when (i) {
                                HIGH_OUTLIER_INDEX -> HIGH_OUTLIER_BPM
                                LOW_OUTLIER_INDEX -> LOW_OUTLIER_BPM
                                else -> 60 + i
                            },
                        recordType = "SLEEP",
                        sessionId = SESSION_ID,
                    )
                }
            database.heartRateDao().upsertAll(samples)

            val hrvSourceRef =
                database.sourceRecordDao().getOrCreateSourceRef(
                    "plausibility-hrv-src",
                    "HEART_RATE_VARIABILITY_RMSSD",
                    0L,
                )
            val hrvSamples =
                (0 until 10).map { i ->
                    HrvRecordEntity(
                        sourceRecordRef = hrvSourceRef,
                        timestampMs = startMs + i * 60_000L,
                        rmssdMs = if (i == 5) 999f else 20f + i,
                        recordType = "SLEEP",
                        sessionId = HRV_SESSION_ID,
                    )
                }
            database.hrvDao().upsertAll(hrvSamples)
        }
    }

    @Test
    fun `implausible samples are excluded from getAvgSleepHr`() = runBlocking {
        // Filtered set is {60,61,63,64,66,67,68,69}: sum=518, count=8, avg=64.75 -> rounds to 65.
        // (Unfiltered avg would be 78.8 -> 79 -- both are "not > 200", which is why a loose
        // upper-bound assertion here previously passed whether or not the predicate existed.)
        val avg = database.heartRateDao().getAvgSleepHr(SESSION_ID)
        assertEquals(65, avg)
    }

    @Test
    fun `implausible samples are excluded from getAvgSleepHrPerSession`() = runBlocking {
        val avgs = database.heartRateDao().getAvgSleepHrPerSession(0L)
        assertEquals(listOf(65), avgs)
    }

    @Test
    fun `implausible samples are excluded from getSleepHrSampleCount`() = runBlocking {
        val count = database.heartRateDao().getSleepHrSampleCount(SESSION_ID)
        assertEquals(8, count)
    }

    @Test
    fun `implausible samples are excluded from getSleepHrSampleAtOffset`() = runBlocking {
        val last = database.heartRateDao().getSleepHrSampleAtOffset(SESSION_ID, 9)
        assertFalse(last == HIGH_OUTLIER_BPM)
    }

    @Test
    fun `implausible samples are excluded from getMinHrTimestamp`() = runBlocking {
        // Without the predicate, MIN(beatsPerMinute) is dragged down to the LOW outlier (20bpm at
        // i==2); with it, the true minimum of the plausible set is 60bpm at i==0 (timestamp startMs).
        val minTimestamp = database.heartRateDao().getMinHrTimestamp(SESSION_ID)
        assertEquals(startMs, minTimestamp)
    }

    @Test
    fun `implausible samples are excluded from getMinHrInRange`() = runBlocking {
        val min =
            database.heartRateDao().getMinHrInRange(
                startMs,
                startMs + 10 * 60_000L,
            )
        assertEquals(60, min)
    }

    @Test
    fun `implausible samples are excluded from getSleepHrSamplesForSessions`() = runBlocking {
        val samples = database.heartRateDao().getSleepHrSamplesForSessions(listOf(SESSION_ID))
        assertFalse(samples.any { it.beatsPerMinute == HIGH_OUTLIER_BPM })
        assertFalse(samples.any { it.beatsPerMinute == LOW_OUTLIER_BPM })
    }

    @Test
    fun `getSleepHrProjectionForSessions returns non-decreasing beatsPerMinute within a session`() = runBlocking {
        val repo =
            ScoringHistoryRepositoryImpl(
                database.heartRateDao(), database.hrvDao(), database.sleepSessionDao(),
                database.dailySummaryDao(), database.minuteBucketDao(),
            )
        val projected = repo.getSleepHrProjectionForSessions(listOf(SESSION_ID))
        val bpms = projected.filter { it.sessionId == SESSION_ID }.map { it.beatsPerMinute }
        assertEquals(bpms.sorted(), bpms)
    }

    @Test
    fun `implausible samples are excluded from getSleepRmssdForSession`() = runBlocking {
        val rmssd = database.hrvDao().getSleepRmssdForSession(HRV_SESSION_ID)
        assertFalse(rmssd.any { it == 999f })
        assertEquals(9, rmssd.size)
    }

    @Test
    fun `implausible samples are excluded from getSleepRmssdForSessionsMap`() = runBlocking {
        val result = database.hrvDao().getSleepRmssdForSessionsMap(listOf(HRV_SESSION_ID))
        assertFalse(result[HRV_SESSION_ID].orEmpty().any { it == 999f })
        assertEquals(9, result[HRV_SESSION_ID].orEmpty().size)
    }

    @Test
    fun `implausible samples are excluded from getRmssdInTimeRange`() = runBlocking {
        val values = database.hrvDao().getRmssdInTimeRange(startMs, startMs + 9 * 60_000L)
        assertFalse(values.any { it == 999f })
        assertEquals(9, values.size)
    }

    private companion object {
        const val SESSION_ID = "plausibility-session"
        const val HRV_SESSION_ID = "plausibility-hrv-session"
        const val HIGH_OUTLIER_INDEX = 5
        const val HIGH_OUTLIER_BPM = 250
        const val LOW_OUTLIER_INDEX = 2
        const val LOW_OUTLIER_BPM = 20
    }
}
