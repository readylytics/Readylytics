package app.readylytics.health.data.local.dao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
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

    private suspend fun seedSourceRecordParents(vararg refs: Long) {
        db.sourceRecordDao().insertAll(
            refs.map { ref ->
                HealthSourceRecordEntity(
                    id = ref,
                    sourceRecordId = "seed-$ref",
                    recordType = "HEART_RATE",
                    createdAtMs = 0L,
                )
            },
        )
    }

    @Test
    fun `sleep hrv session queries return stable timestamp order`() =
        runTest {
            seedSourceRecordParents(1L, 2L, 3L, 4L)
            hrvDao.upsertAll(
                listOf(
                    hrv(4L, timestampMs = 3_000L, rmssdMs = 30f, sessionId = "sleep-1"),
                    hrv(3L, timestampMs = 1_000L, rmssdMs = 10f, sessionId = "sleep-1"),
                    hrv(2L, timestampMs = 2_000L, rmssdMs = 25f, sessionId = "sleep-1"),
                    hrv(1L, timestampMs = 2_000L, rmssdMs = 20f, sessionId = "sleep-1"),
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
            seedSourceRecordParents(1L, 2L, 3L, 4L)
            hrvDao.upsertAll(
                listOf(
                    hrv(4L, timestampMs = 4_000L, rmssdMs = 40f, sessionId = "sleep-b"),
                    hrv(3L, timestampMs = 3_000L, rmssdMs = 30f, sessionId = "sleep-a"),
                    hrv(2L, timestampMs = 2_000L, rmssdMs = 20f, sessionId = "sleep-b"),
                    hrv(1L, timestampMs = 1_000L, rmssdMs = 10f, sessionId = "sleep-a"),
                ),
            )

            val result = hrvDao.getSleepRmssdForSessionsMap(listOf("sleep-a", "sleep-b"))

            assertEquals(listOf(10f, 30f), result["sleep-a"])
            assertEquals(listOf(20f, 40f), result["sleep-b"])
        }

    @Test
    fun `sleep heart rate grouped samples use stable tie order`() =
        runTest {
            seedSourceRecordParents(1L, 2L, 3L, 4L)
            heartRateDao.upsertAll(
                listOf(
                    hr(3L, timestampMs = 3_000L, bpm = 50, sessionId = "sleep-1"),
                    hr(4L, timestampMs = 1_500L, bpm = 51, sessionId = "sleep-1"),
                    hr(2L, timestampMs = 1_000L, bpm = 50, sessionId = "sleep-1"),
                    hr(1L, timestampMs = 1_000L, bpm = 50, sessionId = "sleep-1"),
                ),
            )

            val result = heartRateDao.getSleepHrSamplesForSessions(listOf("sleep-1"))

            assertEquals(
                listOf(1L, 2L, 3L, 4L),
                result.map { it.sourceRecordRef },
            )
        }

    private fun hrv(
        ref: Long,
        timestampMs: Long,
        rmssdMs: Float,
        sessionId: String,
    ) = HrvRecordEntity(
        sourceRecordRef = ref,
        timestampMs = timestampMs,
        rmssdMs = rmssdMs,
        recordType = "SLEEP",
        sessionId = sessionId,
        deviceName = "Pixel",
    )

    private fun hr(
        ref: Long,
        timestampMs: Long,
        bpm: Int,
        sessionId: String,
    ) = HeartRateRecordEntity(
        sourceRecordRef = ref,
        timestampMs = timestampMs,
        beatsPerMinute = bpm,
        recordType = "SLEEP",
        sessionId = sessionId,
        deviceName = "Pixel",
    )
}
