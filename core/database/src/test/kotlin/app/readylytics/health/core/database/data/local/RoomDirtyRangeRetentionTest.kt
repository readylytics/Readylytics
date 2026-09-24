package app.readylytics.health.core.database.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.databaseschema.data.local.entity.HealthMutationStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RoomDirtyRangeRetentionTest {
    private lateinit var database: HealthDatabase
    private lateinit var store: RoomDirtyRangeStore
    private val cutoff = LocalDate.of(2026, 2, 1)

    @Before
    fun setUp() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database =
                Room.inMemoryDatabaseBuilder(context, HealthDatabase::class.java).allowMainThreadQueries().build()
            database.healthMutationStateDao().upsert(HealthMutationStateEntity(id = 1, sourceGeneration = 7))
            store = RoomDirtyRangeStore(database.dirtyRangeDao(), database.healthMutationStateDao())
        }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun expiredTicketsCannotStarveRetainedWorkBeyondFirstPage() =
        runBlocking {
            repeat(105) { store.append(cutoff.minusDays(10), cutoff.minusDays(1), "EXPIRED", "snapshot") }
            val retainedId = store.append(cutoff, cutoff.plusDays(2), "RETAINED", "snapshot")

            store.discardBefore(cutoff)

            assertEquals(listOf(retainedId), store.pending(100).map { it.id })
            assertEquals(1, database.dirtyRangeDao().count())
        }

    @Test
    fun retiredAgingTicketsAreDiscardedWhileSyncWorkSurvives() =
        runBlocking {
            store.append(cutoff, cutoff.plusDays(60), "HOT_TIER_ROLLUP", "ACTIVE")
            store.append(cutoff, cutoff.plusDays(60), "RETENTION_CLEANUP", "ACTIVE")
            val syncId = store.append(cutoff, cutoff.plusDays(1), "AUTHORITATIVE_SOURCE_REPLACEMENT", "ACTIVE")

            assertEquals(2, store.discardRetiredAgingTickets())

            val pending = store.pending(100).single()
            assertEquals(syncId, pending.id)
            assertEquals("AUTHORITATIVE_SOURCE_REPLACEMENT", pending.reason)
        }

    @Test
    fun expiredPrefixAdvancesToFirstRetainedDayWithoutAcknowledgingIt() =
        runBlocking {
            val id = store.append(cutoff.minusDays(10), cutoff.plusDays(2), "OVERLAP", "snapshot")

            store.discardBefore(cutoff)
            store.discardBefore(cutoff)

            val pending = store.pending(100).single()
            assertEquals(id, pending.id)
            assertEquals(cutoff, pending.nextDay)
            assertEquals(cutoff.plusDays(2), pending.endInclusive)
            assertEquals(7L, pending.sourceGeneration)
            assertEquals("snapshot", pending.scoringSnapshotId)
            assertEquals(listOf(id), database.dirtyRangeDao().pendingForDay(cutoff.toEpochDay()).map { it.id })
        }

    @Test
    fun trimmingDoesNotRewindCompletedPrefixOrDropBoundaryDay() =
        runBlocking {
            val boundaryId = store.append(cutoff.minusDays(1), cutoff, "BOUNDARY", "snapshot")
            val advancedId = store.append(cutoff.plusDays(1), cutoff.plusDays(3), "ADVANCED", "snapshot")

            store.discardBefore(cutoff)

            val pending = store.pending(100).associateBy { it.id }
            assertEquals(cutoff, pending.getValue(boundaryId).nextDay)
            assertEquals(cutoff.plusDays(1), pending.getValue(advancedId).nextDay)
            assertTrue(pending.values.all { !it.nextDay.isBefore(cutoff) })
        }
}
