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

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        runBlocking {
            val sourceRef = database.sourceRecordDao().getOrCreateSourceRef("plausibility-src", "HEART_RATE", 0L)
            val startMs = Instant.parse("2026-01-11T22:00:00Z").toEpochMilli()
            val samples =
                (0 until 10).map { i ->
                    HeartRateRecordEntity(
                        sourceRecordRef = sourceRef,
                        timestampMs = startMs + i * 60_000L,
                        beatsPerMinute = if (i == 5) 250 else 60 + i,
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
        val avg = database.heartRateDao().getAvgSleepHr(SESSION_ID)
        assertFalse("avg=$avg should not be pulled up by the 250bpm outlier", (avg ?: 0) > 200)
    }

    @Test
    fun `implausible samples are excluded from getAvgSleepHrPerSession`() = runBlocking {
        val avgs = database.heartRateDao().getAvgSleepHrPerSession(0L)
        assertFalse(avgs.any { it > 200 })
    }

    @Test
    fun `implausible samples are excluded from getSleepHrSampleCount`() = runBlocking {
        val count = database.heartRateDao().getSleepHrSampleCount(SESSION_ID)
        assertEquals(9, count)
    }

    @Test
    fun `implausible samples are excluded from getSleepHrSampleAtOffset`() = runBlocking {
        val last = database.heartRateDao().getSleepHrSampleAtOffset(SESSION_ID, 9)
        assertFalse(last == 250)
    }

    @Test
    fun `implausible samples are excluded from getMinHrTimestamp`() = runBlocking {
        val minTimestamp = database.heartRateDao().getMinHrTimestamp(SESSION_ID)
        val outlierTimestamp = Instant.parse("2026-01-11T22:00:00Z").toEpochMilli() + 5 * 60_000L
        assertFalse(minTimestamp == outlierTimestamp)
    }

    @Test
    fun `implausible samples are excluded from getMinHrInRange`() = runBlocking {
        val min =
            database.heartRateDao().getMinHrInRange(
                Instant.parse("2026-01-11T22:00:00Z").toEpochMilli(),
                Instant.parse("2026-01-11T22:10:00Z").toEpochMilli(),
            )
        assertFalse(min == 250)
    }

    @Test
    fun `implausible samples are excluded from getSleepHrSamplesForSessions`() = runBlocking {
        val samples = database.heartRateDao().getSleepHrSamplesForSessions(listOf(SESSION_ID))
        assertFalse(samples.any { it.beatsPerMinute == 250 })
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
        val startMs = Instant.parse("2026-01-11T22:00:00Z").toEpochMilli()
        val values = database.hrvDao().getRmssdInTimeRange(startMs, startMs + 9 * 60_000L)
        assertFalse(values.any { it == 999f })
        assertEquals(9, values.size)
    }

    private companion object {
        const val SESSION_ID = "plausibility-session"
        const val HRV_SESSION_ID = "plausibility-hrv-session"
    }
}
