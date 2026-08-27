package app.readylytics.health.core.database.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * DB-002: verifies the keyset-batched delete used by RetentionCleanup deletes at most `limit`
 * rows per call, oldest-first, and terminates (returns 0) once nothing before the cutoff remains.
 */
@RunWith(AndroidJUnit4::class)
class DeleteBeforeTimestampBatchTest {
    private lateinit var database: HealthDatabase
    private lateinit var heartRateDao: HeartRateDao
    private lateinit var hrvDao: HrvDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        heartRateDao = database.heartRateDao()
        hrvDao = database.hrvDao()
    }

    @After
    fun cleanup() {
        database.close()
    }

    private suspend fun seedSourceRecordParents(count: Int) {
        database.sourceRecordDao().insertAll(
            (1..count).map { ref ->
                HealthSourceRecordEntity(
                    id = ref.toLong(),
                    sourceRecordId = "seed-$ref",
                    recordType = "HEART_RATE",
                    createdAtMs = 0L,
                )
            },
        )
    }

    @Test
    fun `heart rate batch delete removes at most limit rows per call and terminates at zero`() =
        runTest {
            val cutoffMs = 1_000_000L
            seedSourceRecordParents(25)
            heartRateDao.upsertAll(
                (1..25).map { i ->
                    HeartRateRecordEntity(
                        sourceRecordRef = i.toLong(),
                        timestampMs = cutoffMs - i,
                        beatsPerMinute = 60,
                        recordType = "RESTING",
                    )
                },
            )

            val deletedCounts = mutableListOf<Int>()
            while (true) {
                val deleted = heartRateDao.deleteBeforeTimestampBatch(cutoffMs, limit = 10)
                if (deleted <= 0) break
                deletedCounts.add(deleted)
            }

            assertEquals(listOf(10, 10, 5), deletedCounts)
            assertEquals(0, heartRateDao.countInRange(0L, cutoffMs))
        }

    @Test
    fun `hrv batch delete removes at most limit rows per call and terminates at zero`() =
        runTest {
            val cutoffMs = 1_000_000L
            seedSourceRecordParents(13)
            hrvDao.upsertAll(
                (1..13).map { i ->
                    HrvRecordEntity(
                        sourceRecordRef = i.toLong(),
                        timestampMs = cutoffMs - i,
                        rmssdMs = 40f,
                        recordType = "RESTING",
                    )
                },
            )

            val deletedCounts = mutableListOf<Int>()
            while (true) {
                val deleted = hrvDao.deleteBeforeTimestampBatch(cutoffMs, limit = 5)
                if (deleted <= 0) break
                deletedCounts.add(deleted)
            }

            assertEquals(listOf(5, 5, 3), deletedCounts)
            assertEquals(0, hrvDao.countInRange(0L, cutoffMs))
        }
}
