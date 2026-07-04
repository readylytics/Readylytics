package app.readylytics.health.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.HrvRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SleepMetricDaoOrderingTest {
    private lateinit var db: HealthDatabase
    private lateinit var hrvDao: HrvDao
    private lateinit var heartRateDao: HeartRateDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        hrvDao = db.hrvDao()
        heartRateDao = db.heartRateDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `sleep hrv session queries return stable timestamp order`() =
        runTest {
            hrvDao.upsertAll(
                listOf(
                    hrv("late", timestampMs = 3_000L, rmssdMs = 30f, sessionId = "sleep-1"),
                    hrv("early", timestampMs = 1_000L, rmssdMs = 10f, sessionId = "sleep-1"),
                    hrv("middle-b", timestampMs = 2_000L, rmssdMs = 25f, sessionId = "sleep-1"),
                    hrv("middle-a", timestampMs = 2_000L, rmssdMs = 20f, sessionId = "sleep-1"),
                ),
            )

            assertEquals(
                listOf(10f, 20f, 25f, 30f),
                hrvDao.getSleepRmssdForSession("sleep-1"),
            )
            assertEquals(
                listOf(10f, 20f, 25f, 30f),
                hrvDao.getRmssdInTimeRange(1_000L, 3_000L),
            )
        }

    @Test
    fun `sleep hrv session map returns stable order inside each session`() =
        runTest {
            hrvDao.upsertAll(
                listOf(
                    hrv("b-late", timestampMs = 4_000L, rmssdMs = 40f, sessionId = "sleep-b"),
                    hrv("a-late", timestampMs = 3_000L, rmssdMs = 30f, sessionId = "sleep-a"),
                    hrv("b-early", timestampMs = 2_000L, rmssdMs = 20f, sessionId = "sleep-b"),
                    hrv("a-early", timestampMs = 1_000L, rmssdMs = 10f, sessionId = "sleep-a"),
                ),
            )

            val result = hrvDao.getSleepRmssdForSessionsMap(listOf("sleep-a", "sleep-b"))

            assertEquals(listOf(10f, 30f), result["sleep-a"])
            assertEquals(listOf(20f, 40f), result["sleep-b"])
        }

    @Test
    fun `sleep heart rate grouped samples use stable tie order`() =
        runTest {
            heartRateDao.upsertAll(
                listOf(
                    hr("tie-late", timestampMs = 3_000L, bpm = 50, sessionId = "sleep-1"),
                    hr("higher", timestampMs = 1_500L, bpm = 51, sessionId = "sleep-1"),
                    hr("tie-early-b", timestampMs = 1_000L, bpm = 50, sessionId = "sleep-1"),
                    hr("tie-early-a", timestampMs = 1_000L, bpm = 50, sessionId = "sleep-1"),
                ),
            )

            val result = heartRateDao.getSleepHrSamplesForSessions(listOf("sleep-1"))

            assertEquals(
                listOf("tie-early-a", "tie-early-b", "tie-late", "higher"),
                result.map { it.id },
            )
        }

    private fun hrv(
        id: String,
        timestampMs: Long,
        rmssdMs: Float,
        sessionId: String,
    ) = HrvRecordEntity(
        id = id,
        timestampMs = timestampMs,
        rmssdMs = rmssdMs,
        recordType = "SLEEP",
        sessionId = sessionId,
        deviceName = "Pixel",
    )

    private fun hr(
        id: String,
        timestampMs: Long,
        bpm: Int,
        sessionId: String,
    ) = HeartRateRecordEntity(
        id = id,
        timestampMs = timestampMs,
        beatsPerMinute = bpm,
        recordType = "SLEEP",
        sessionId = sessionId,
        deviceName = "Pixel",
    )
}
