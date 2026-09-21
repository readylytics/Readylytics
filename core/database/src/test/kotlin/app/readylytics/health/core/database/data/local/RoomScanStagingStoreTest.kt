package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.ScanIdentity
import app.readylytics.health.core.model.domain.sync.ScanStagingStore
import app.readylytics.health.core.model.domain.sync.TypeScanState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class RoomScanStagingStoreTest {
    private lateinit var database: HealthDatabase
    private lateinit var store: ScanStagingStore
    private val scan = ScanIdentity(runId = "run-a", chunkId = "19000")

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        store =
            RoomScanStagingStore(
                scanStagingDao = database.scanStagingDao(),
                clock = Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC),
            )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun beginWithoutResumeClearsPreviousStagingForThatTypeOnly() =
        runBlocking {
            store.beginTypeScan(scan, HealthDataType.HEART_RATE, resume = false)
            store.stageIds(scan, HealthDataType.HEART_RATE, listOf("hc-1", "hc-2"))
            store.beginTypeScan(scan, HealthDataType.HRV, resume = false)
            store.stageIds(scan, HealthDataType.HRV, listOf("hc-3"))

            store.beginTypeScan(scan, HealthDataType.HEART_RATE, resume = false)

            assertEquals(0, store.stagedCount(scan, HealthDataType.HEART_RATE))
            assertEquals(1, store.stagedCount(scan, HealthDataType.HRV))
            assertEquals(TypeScanState.SCANNING, store.stateOf(scan, HealthDataType.HEART_RATE))
        }

    @Test
    fun beginWithResumeKeepsStagedIds() =
        runBlocking {
            store.beginTypeScan(scan, HealthDataType.HEART_RATE, resume = false)
            store.stageIds(scan, HealthDataType.HEART_RATE, listOf("hc-1", "hc-2"))

            store.beginTypeScan(scan, HealthDataType.HEART_RATE, resume = true)

            assertEquals(2, store.stagedCount(scan, HealthDataType.HEART_RATE))
            assertEquals(TypeScanState.SCANNING, store.stateOf(scan, HealthDataType.HEART_RATE))
        }

    @Test
    fun markCompleteRecordsStateAndCount() =
        runBlocking {
            store.beginTypeScan(scan, HealthDataType.SLEEP, resume = false)
            store.stageIds(scan, HealthDataType.SLEEP, listOf("s1", "s2", "s3"))

            store.markTypeScanComplete(scan, HealthDataType.SLEEP)

            assertEquals(TypeScanState.COMPLETE, store.stateOf(scan, HealthDataType.SLEEP))
            assertEquals(3, store.stagedCount(scan, HealthDataType.SLEEP))
        }

    @Test
    fun stagingMoreThanOneBatchStoresEveryId() =
        runBlocking {
            val ids = (1..1_250).map { "hc-$it" }
            store.beginTypeScan(scan, HealthDataType.HEART_RATE, resume = false)

            store.stageIds(scan, HealthDataType.HEART_RATE, ids)

            assertEquals(1_250, store.stagedCount(scan, HealthDataType.HEART_RATE))
        }

    @Test
    fun clearRunsOtherThanDropsAbandonedRunsOnly() =
        runBlocking {
            store.beginTypeScan(scan, HealthDataType.HEART_RATE, resume = false)
            store.stageIds(scan, HealthDataType.HEART_RATE, listOf("hc-1"))
            val abandoned = ScanIdentity(runId = "run-old", chunkId = "18000")
            store.beginTypeScan(abandoned, HealthDataType.HEART_RATE, resume = false)
            store.stageIds(abandoned, HealthDataType.HEART_RATE, listOf("hc-9"))

            store.clearRunsOtherThan("run-a")

            assertEquals(1, store.stagedCount(scan, HealthDataType.HEART_RATE))
            assertEquals(0, store.stagedCount(abandoned, HealthDataType.HEART_RATE))
            assertNull(store.stateOf(abandoned, HealthDataType.HEART_RATE))
        }

    @Test
    fun clearTypeScanRemovesSeenIdsAndStateForSpecificType() =
        runBlocking {
            store.beginTypeScan(scan, HealthDataType.HEART_RATE, resume = false)
            store.stageIds(scan, HealthDataType.HEART_RATE, listOf("hc-1"))
            store.beginTypeScan(scan, HealthDataType.HRV, resume = false)
            store.stageIds(scan, HealthDataType.HRV, listOf("hc-2"))

            store.clearTypeScan(scan, HealthDataType.HEART_RATE)

            assertEquals(0, store.stagedCount(scan, HealthDataType.HEART_RATE))
            assertNull(store.stateOf(scan, HealthDataType.HEART_RATE))
            assertEquals(1, store.stagedCount(scan, HealthDataType.HRV))
            assertEquals(TypeScanState.SCANNING, store.stateOf(scan, HealthDataType.HRV))
        }

    @Test
    fun clearRunRemovesAllChunksAndTypesForThatRun() =
        runBlocking {
            val chunk2 = ScanIdentity(runId = "run-a", chunkId = "19030")
            val runB = ScanIdentity(runId = "run-b", chunkId = "19000")

            store.beginTypeScan(scan, HealthDataType.HEART_RATE, resume = false)
            store.stageIds(scan, HealthDataType.HEART_RATE, listOf("hc-1"))
            store.beginTypeScan(chunk2, HealthDataType.HRV, resume = false)
            store.stageIds(chunk2, HealthDataType.HRV, listOf("hc-2"))
            store.beginTypeScan(runB, HealthDataType.HEART_RATE, resume = false)
            store.stageIds(runB, HealthDataType.HEART_RATE, listOf("hc-3"))

            store.clearRun("run-a")

            assertEquals(0, store.stagedCount(scan, HealthDataType.HEART_RATE))
            assertNull(store.stateOf(scan, HealthDataType.HEART_RATE))
            assertEquals(0, store.stagedCount(chunk2, HealthDataType.HRV))
            assertNull(store.stateOf(chunk2, HealthDataType.HRV))
            assertEquals(1, store.stagedCount(runB, HealthDataType.HEART_RATE))
            assertEquals(TypeScanState.SCANNING, store.stateOf(runB, HealthDataType.HEART_RATE))
        }

    @Test
    fun stagingEmptyCollectionIsNoOp() =
        runBlocking {
            store.beginTypeScan(scan, HealthDataType.HEART_RATE, resume = false)
            store.stageIds(scan, HealthDataType.HEART_RATE, emptyList())

            assertEquals(0, store.stagedCount(scan, HealthDataType.HEART_RATE))
        }
}
