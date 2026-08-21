package app.readylytics.health.core.database.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.databaseschema.data.local.dao.BodyTemperatureRecordDao
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.BodyTemperatureRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class BodyTemperatureRecordDaoTest {
    private lateinit var database: HealthDatabase
    private lateinit var dao: BodyTemperatureRecordDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.bodyTemperatureRecordDao()
    }

    @After
    fun cleanup() {
        database.close()
    }

    @Test
    fun `upsertAll then getByTimeRange returns records within bounds, ordered ascending`() =
        runTest {
            dao.upsertAll(
                listOf(
                    BodyTemperatureRecordEntity(id = "a", timestampMs = 100L, celsius = 36.6f),
                    BodyTemperatureRecordEntity(id = "b", timestampMs = 200L, celsius = 36.8f),
                    BodyTemperatureRecordEntity(id = "c", timestampMs = 300L, celsius = 37.0f),
                ),
            )

            val result = dao.getByTimeRange(150L, 250L)
            assertEquals(listOf("b"), result.map { it.id })
        }

    @Test
    fun `deleteBeforeTimestamp removes only older records`() =
        runTest {
            dao.upsertAll(
                listOf(
                    BodyTemperatureRecordEntity(id = "old", timestampMs = 100L, celsius = 36.6f),
                    BodyTemperatureRecordEntity(id = "new", timestampMs = 500L, celsius = 36.8f),
                ),
            )

            val deleted = dao.deleteBeforeTimestamp(300L)

            assertEquals(1, deleted)
            assertNull(dao.getById("old"))
        }
}
