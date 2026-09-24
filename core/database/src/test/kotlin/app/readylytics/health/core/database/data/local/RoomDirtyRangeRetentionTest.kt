package app.readylytics.health.core.database.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.databaseschema.data.local.entity.HealthMutationStateEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class RoomDirtyRangeRetentionTest {
    private lateinit var database: HealthDatabase
    private lateinit var store: RoomDirtyRangeStore
    private lateinit var changeStore: RoomHealthChangeIngestionStore
    private val cutoff = LocalDate.of(2026, 2, 1)

    @Before
    fun setUp() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database =
                Room.inMemoryDatabaseBuilder(context, HealthDatabase::class.java).allowMainThreadQueries().build()
            database.healthMutationStateDao().upsert(HealthMutationStateEntity(id = 1, sourceGeneration = 7))
            store = RoomDirtyRangeStore(database.dirtyRangeDao(), database.healthMutationStateDao())
            val daos =
                HealthRecordDaos(
                    sleepSessionDao = database.sleepSessionDao(),
                    sleepStageDao = database.sleepStageDao(),
                    heartRateDao = database.heartRateDao(),
                    hrvDao = database.hrvDao(),
                    workoutDao = database.workoutDao(),
                    workoutRoutePointDao = database.workoutRoutePointDao(),
                    weightRecordDao = database.weightRecordDao(),
                    bodyFatRecordDao = database.bodyFatRecordDao(),
                    bloodPressureRecordDao = database.bloodPressureRecordDao(),
                    oxygenSaturationRecordDao = database.oxygenSaturationRecordDao(),
                    bodyTemperatureRecordDao = database.bodyTemperatureRecordDao(),
                    stepRecordDao = database.stepRecordDao(),
                    sourceRecordDao = database.sourceRecordDao(),
                    minuteBucketMaintenanceDao = database.minuteBucketMaintenanceDao(),
                )
            changeStore =
                RoomHealthChangeIngestionStore(
                    daos = daos,
                    dirtyRangeStore = store,
                    healthMutationStateDao = database.healthMutationStateDao(),
                    transactionRunner = RoomTransactionRunner(database),
                )
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

    @Test
    fun legacyReasonStringResolvesToUnknown() {
        val reason = ScoreInvalidation.reasonFromStored("OLDER_APP_REASON")
        assertEquals(ScoreInvalidation.Reason.UNKNOWN, reason)

        val today = LocalDate.of(2026, 9, 23)
        val changedDay = LocalDate.of(2026, 5, 1)
        val closure =
            ScoreInvalidation.dependencyClosure(
                ScoreInvalidation.AffectedRange(changedDay, changedDay),
                reason,
                cutoff,
                today,
            )
        assertEquals(ScoreInvalidation.AffectedRange(changedDay, today), closure)
    }

    @Test
    fun workoutCorrectionIn200DayFixtureEndsOnCapturedToday() =
        runBlocking {
            val today = LocalDate.of(2026, 9, 23)
            val dayD = today.minusDays(150)
            val dayDMs = dayD.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

            database.workoutDao().upsertAll(
                listOf(
                    WorkoutRecordEntity(
                        id = "workout-200",
                        startTime = dayDMs + 3600_000L,
                        endTime = dayDMs + 7200_000L,
                        exerciseType = "RUNNING",
                        durationMinutes = 60,
                        zone1Minutes = 10f,
                        zone2Minutes = 20f,
                        zone3Minutes = 20f,
                        zone4Minutes = 10f,
                        zone5Minutes = 0f,
                        trimp = 80f,
                        avgHr = 145f,
                        deviceName = "Watch",
                    ),
                ),
            )

            changeStore.deleteRecordAndJournal(
                type = HealthDataType.EXERCISE,
                hcRecordId = "workout-200",
                zoneId = ZoneOffset.UTC,
                today = today,
            )

            val ticket = database.dirtyRangeDao().pending(1).single()
            assertEquals(dayD.toEpochDay(), ticket.nextEpochDay)
            assertEquals(today.toEpochDay(), ticket.endEpochDayInclusive)
        }

    @Test
    fun sparseSleepAndHrvDeletionInNonUtcZoneWithNoHrRowsJournalsAffectedScoreDay() =
        runBlocking {
            val nyZone = ZoneId.of("America/New_York")
            val today = LocalDate.of(2026, 9, 23)
            val zonedDateTime = java.time.ZonedDateTime.of(2026, 6, 15, 3, 0, 0, 0, ZoneOffset.UTC)
            val timestampMs = zonedDateTime.toInstant().toEpochMilli()
            val expectedScoreDay = LocalDate.of(2026, 6, 14)

            database.sleepSessionDao().upsertAll(
                listOf(
                    SleepSessionEntity(
                        id = "sparse-sleep-1",
                        startTime = timestampMs,
                        endTime = timestampMs + 7 * 3600_000L,
                        durationMinutes = 420,
                        efficiency = 90f,
                        deepSleepMinutes = 60,
                        remSleepMinutes = 90,
                        lightSleepMinutes = 240,
                        awakeMinutes = 30,
                    ),
                ),
            )

            changeStore.deleteRecordAndJournal(
                type = HealthDataType.SLEEP,
                hcRecordId = "sparse-sleep-1",
                zoneId = nyZone,
                today = today,
            )

            val tickets = database.dirtyRangeDao().pending(100)
            val ticket = tickets.firstOrNull { it.reason == "RECORD_DELETION" }
            assertNotNull(ticket)
            assertEquals(expectedScoreDay.toEpochDay(), ticket!!.nextEpochDay)
            assertEquals(today.toEpochDay(), ticket.endEpochDayInclusive)
        }

    @Test
    fun dbReopeningAdvancesNextEpochDayAndDeletesOnFullPublish() =
        runBlocking {
            val dayD = LocalDate.of(2026, 5, 1)
            val today = LocalDate.of(2026, 5, 10)
            val ticketId = store.append(dayD, today, "WORKOUT", "snap-1")

            var ticket = database.dirtyRangeDao().pending(1).single()
            assertEquals(dayD.toEpochDay(), ticket.nextEpochDay)
            assertEquals(today.toEpochDay(), ticket.endEpochDayInclusive)

            database.dirtyRangeDao().advance(
                id = ticketId,
                generation = 7L,
                expectedDay = dayD.toEpochDay(),
                nextDay = dayD.plusDays(1).toEpochDay(),
            )

            ticket = database.dirtyRangeDao().pending(1).single()
            assertEquals(dayD.plusDays(1).toEpochDay(), ticket.nextEpochDay)

            database.dirtyRangeDao().advance(
                id = ticketId,
                generation = 7L,
                expectedDay = dayD.plusDays(1).toEpochDay(),
                nextDay = today.plusDays(1).toEpochDay(),
            )
            database.dirtyRangeDao().deleteCompleted(ticketId, 7L)

            assertTrue(database.dirtyRangeDao().pending(1).isEmpty())
        }
}
