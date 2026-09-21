package app.readylytics.health.core.database.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import app.readylytics.health.core.databaseschema.data.local.entity.Vo2MaxRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class Vo2MaxRecordDaoTest {
    private lateinit var db: HealthDatabase
    private lateinit var dao: Vo2MaxRecordDao

    private val berlin = ZoneId.of("Europe/Berlin")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.vo2MaxRecordDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createVo2Record(
        id: String,
        timestampMs: Long,
        vo2Max: Float,
    ): Vo2MaxRecordEntity =
        Vo2MaxRecordEntity(
            id = id,
            timestampMs = timestampMs,
            vo2Max = vo2Max,
            measurementMethod = null,
            deviceName = "Watch",
        )

    @Test
    fun `getLatestInRange excludes next midnight and deterministically breaks ties with id DESC`() =
        runTest {
            val targetDate = LocalDate.of(2026, 6, 15)
            val nextMidnight = targetDate.plusDays(1).atStartOfDay(berlin).toInstant().toEpochMilli()
            val thirtyDaysMs = TimeUnit.DAYS.toMillis(30)
            val minTimestampMs = nextMidnight - thirtyDaysMs

            val recordA = createVo2Record("record_a", nextMidnight - 1, 45.0f)
            val recordB = createVo2Record("record_b", nextMidnight - 1, 46.0f)
            val recordNextMidnight = createVo2Record("record_next", nextMidnight, 50.0f)

            dao.upsertAll(listOf(recordA, recordB, recordNextMidnight))

            val selected = dao.getLatestInRange(minTimestampMs, nextMidnight)

            assertNotNull(selected)
            assertEquals("record_b", selected?.id)
            assertEquals(46.0f, selected?.vo2Max)
        }

    @Test
    fun `getLatestInRange handles Berlin 23-hour DST transition`() =
        runTest {
            val dstDate = LocalDate.of(2026, 3, 29)
            val nextMidnight = dstDate.plusDays(1).atStartOfDay(berlin).toInstant().toEpochMilli()
            val minTimestampMs = nextMidnight - TimeUnit.DAYS.toMillis(30)

            val endMinusOne = createVo2Record("record_23h", nextMidnight - 1, 48.0f)
            val nextMidnightRecord = createVo2Record("record_next_dst", nextMidnight, 52.0f)

            dao.upsertAll(listOf(endMinusOne, nextMidnightRecord))

            val result = dao.getLatestInRange(minTimestampMs, nextMidnight)
            assertEquals("record_23h", result?.id)
            assertEquals(48.0f, result?.vo2Max)
        }

    @Test
    fun `getLatestInRange handles Berlin 25-hour DST transition`() =
        runTest {
            val dstDate = LocalDate.of(2026, 10, 25)
            val nextMidnight = dstDate.plusDays(1).atStartOfDay(berlin).toInstant().toEpochMilli()
            val minTimestampMs = nextMidnight - TimeUnit.DAYS.toMillis(30)

            val endMinusOne = createVo2Record("record_25h", nextMidnight - 1, 47.0f)
            val nextMidnightRecord = createVo2Record("record_next_dst2", nextMidnight, 53.0f)

            dao.upsertAll(listOf(endMinusOne, nextMidnightRecord))

            val result = dao.getLatestInRange(minTimestampMs, nextMidnight)
            assertEquals("record_25h", result?.id)
            assertEquals(47.0f, result?.vo2Max)
        }

    @Test
    fun `getLatestInRange enforces exact 30-day lower boundary`() =
        runTest {
            val targetDate = LocalDate.of(2026, 6, 15)
            val nextMidnight = targetDate.plusDays(1).atStartOfDay(berlin).toInstant().toEpochMilli()
            val thirtyDaysMs = TimeUnit.DAYS.toMillis(30)
            val minTimestampMs = nextMidnight - thirtyDaysMs

            val exactAtLower = createVo2Record("record_exact_lower", minTimestampMs, 42.0f)
            val beforeLower = createVo2Record("record_before_lower", minTimestampMs - 1, 40.0f)

            dao.upsertAll(listOf(exactAtLower, beforeLower))

            val result = dao.getLatestInRange(minTimestampMs, nextMidnight)
            assertEquals("record_exact_lower", result?.id)

            dao.deleteById("record_exact_lower")
            val emptyResult = dao.getLatestInRange(minTimestampMs, nextMidnight)
            assertNull(emptyResult)
        }
}
