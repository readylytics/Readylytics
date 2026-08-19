package app.readylytics.health.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.dao.SourceRecordDao
import app.readylytics.health.data.local.HealthDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SourceRecordDaoTest {
    private lateinit var database: HealthDatabase
    private lateinit var dao: SourceRecordDao

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        dao = database.sourceRecordDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun getOrCreateSourceRef_returnsSameIdForDuplicateSourceRecordId() =
        runBlocking {
            val id1 = dao.getOrCreateSourceRef("uuid-1234", "HEART_RATE", 1000L)
            val id2 = dao.getOrCreateSourceRef("uuid-1234", "HEART_RATE", 2000L)
            assertEquals(id1, id2)
            assertNotEquals(0L, id1)
        }

    @Test
    fun deleteBySourceRecordId_removesRecord() =
        runBlocking {
            val id = dao.getOrCreateSourceRef("uuid-5678", "HEART_RATE", 1000L)
            val deleted = dao.deleteBySourceRecordId("uuid-5678")
            assertEquals(1, deleted)
            val newId = dao.getOrCreateSourceRef("uuid-5678", "HEART_RATE", 3000L)
            assertNotEquals(id, newId)
        }
}
