package app.readylytics.health.core.database.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * DB-001: verifies `HeartRateDao.getByTypeAndTimeRange` returns only rows matching the requested
 * `recordType`, using the existing `index_hr_v10_type_timestamp` index instead of an in-memory
 * Kotlin `.filter` over the full time-range result set.
 */
@RunWith(AndroidJUnit4::class)
class HeartRateExerciseRangeQueryTest {
    private lateinit var database: HealthDatabase
    private lateinit var heartRateDao: HeartRateDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        heartRateDao = database.heartRateDao()
    }

    @After
    fun cleanup() {
        database.close()
    }

    private suspend fun seedSourceRecordParents(vararg refs: Long) {
        database.sourceRecordDao().insertAll(
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
    fun `returns only rows matching recordType within range, ordered by timestamp`() =
        runTest {
            seedSourceRecordParents(1L, 2L, 3L, 4L)
            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        sourceRecordRef = 1L,
                        timestampMs = 200L,
                        beatsPerMinute = 140,
                        recordType = "EXERCISE",
                    ),
                    HeartRateRecordEntity(
                        sourceRecordRef = 2L,
                        timestampMs = 100L,
                        beatsPerMinute = 130,
                        recordType = "EXERCISE",
                    ),
                    HeartRateRecordEntity(
                        sourceRecordRef = 3L,
                        timestampMs = 150L,
                        beatsPerMinute = 55,
                        recordType = "SLEEP",
                    ),
                    HeartRateRecordEntity(
                        sourceRecordRef = 4L,
                        timestampMs = 160L,
                        beatsPerMinute = 60,
                        recordType = "RESTING",
                    ),
                ),
            )

            val result = heartRateDao.getByTypeAndTimeRange("EXERCISE", 0L, 1000L)

            assertEquals(listOf(100L, 200L), result.map { it.timestampMs })
            assertEquals(listOf("EXERCISE", "EXERCISE"), result.map { it.recordType })
        }

    @Test
    fun `excludes rows outside the time range`() =
        runTest {
            seedSourceRecordParents(1L, 2L)
            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        sourceRecordRef = 1L,
                        timestampMs = 50L,
                        beatsPerMinute = 140,
                        recordType = "EXERCISE",
                    ),
                    HeartRateRecordEntity(
                        sourceRecordRef = 2L,
                        timestampMs = 2000L,
                        beatsPerMinute = 140,
                        recordType = "EXERCISE",
                    ),
                ),
            )

            val result = heartRateDao.getByTypeAndTimeRange("EXERCISE", 100L, 1000L)

            assertEquals(emptyList(), result)
        }
}
