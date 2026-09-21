package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.dao.ScanStagingDao
import app.readylytics.health.core.databaseschema.data.local.entity.ScanSeenIdEntity
import app.readylytics.health.core.databaseschema.data.local.entity.ScanTypeStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScanStagingDaoTest {
    private lateinit var database: HealthDatabase
    private lateinit var dao: ScanStagingDao

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        dao = database.scanStagingDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun stagingIsScopedByRunChunkAndType() =
        runBlocking {
            dao.insertSeenIds(
                listOf(
                    ScanSeenIdEntity("run-a", "19000", "HEART_RATE", "hc-1"),
                    ScanSeenIdEntity("run-a", "19000", "HEART_RATE", "hc-2"),
                    ScanSeenIdEntity("run-a", "19000", "HRV", "hc-3"),
                    ScanSeenIdEntity("run-a", "19030", "HEART_RATE", "hc-4"),
                    ScanSeenIdEntity("run-b", "19000", "HEART_RATE", "hc-5"),
                ),
            )

            assertEquals(2, dao.countSeen("run-a", "19000", "HEART_RATE"))
            assertEquals(1, dao.countSeen("run-a", "19000", "HRV"))
            assertEquals(1, dao.countSeen("run-b", "19000", "HEART_RATE"))
        }

    @Test
    fun repeatedStagingOfSameIdIsIdempotent() =
        runBlocking {
            val row = ScanSeenIdEntity("run-a", "19000", "HEART_RATE", "hc-1")
            dao.insertSeenIds(listOf(row))
            dao.insertSeenIds(listOf(row))

            assertEquals(1, dao.countSeen("run-a", "19000", "HEART_RATE"))
        }

    @Test
    fun clearingOtherRunsKeepsTheActiveRun() =
        runBlocking {
            dao.insertSeenIds(
                listOf(
                    ScanSeenIdEntity("run-a", "19000", "HEART_RATE", "hc-1"),
                    ScanSeenIdEntity("run-b", "19000", "HEART_RATE", "hc-2"),
                ),
            )
            dao.upsertState(ScanTypeStateEntity("run-a", "19000", "HEART_RATE", ScanStagingDao.STATE_SCANNING, 1, 10L))
            dao.upsertState(ScanTypeStateEntity("run-b", "19000", "HEART_RATE", ScanStagingDao.STATE_COMPLETE, 1, 10L))

            dao.deleteSeenForOtherRuns("run-a")
            dao.deleteStateForOtherRuns("run-a")

            assertEquals(1, dao.countSeen("run-a", "19000", "HEART_RATE"))
            assertEquals(0, dao.countSeen("run-b", "19000", "HEART_RATE"))
            assertNull(dao.getState("run-b", "19000", "HEART_RATE"))
            assertEquals(
                ScanStagingDao.STATE_SCANNING,
                dao.getState("run-a", "19000", "HEART_RATE")?.state,
            )
        }

    @Test
    fun stateUpsertReplacesPreviousStateForSameKey() =
        runBlocking {
            dao.upsertState(ScanTypeStateEntity("run-a", "19000", "SLEEP", ScanStagingDao.STATE_SCANNING, 3, 10L))
            dao.upsertState(ScanTypeStateEntity("run-a", "19000", "SLEEP", ScanStagingDao.STATE_COMPLETE, 7, 20L))

            val state = dao.getState("run-a", "19000", "SLEEP")
            assertEquals(ScanStagingDao.STATE_COMPLETE, state?.state)
            assertEquals(7, state?.stagedCount)
            assertEquals(20L, state?.updatedAtMs)
        }
}
