package app.readylytics.health.core.database.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.mapper.DailySummaryMapper
import app.readylytics.health.core.database.data.repository.DirtySummaryPublisher
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.DirtyRangeEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HealthMutationStateEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.model.ReadinessResult
import app.readylytics.health.core.model.domain.scoring.DayAssembly
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class DirtyMutationRecoveryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: HealthDatabase
    private lateinit var transactionRunner: RoomTransactionRunner
    private lateinit var changeStore: RoomHealthChangeIngestionStore
    private lateinit var publisher: DirtySummaryPublisher
    private lateinit var dirtyRangeStore: RoomDirtyRangeStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getDatabasePath(DB_NAME).delete()
        openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.getDatabasePath(DB_NAME).delete()
    }

    private fun openDatabase() {
        database =
            Room
                .databaseBuilder(context, HealthDatabase::class.java, DB_NAME)
                .allowMainThreadQueries()
                .build()
        transactionRunner = RoomTransactionRunner(database)
        dirtyRangeStore =
            RoomDirtyRangeStore(
                dirtyRangeDao = database.dirtyRangeDao(),
                healthMutationStateDao = database.healthMutationStateDao(),
            )
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
                dirtyRangeStore = dirtyRangeStore,
                healthMutationStateDao = database.healthMutationStateDao(),
                transactionRunner = transactionRunner,
            )
        publisher =
            DirtySummaryPublisher(
                transactionRunner = transactionRunner,
                healthMutationStateDao = database.healthMutationStateDao(),
                dirtyRangeDao = database.dirtyRangeDao(),
            )
    }

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    @Test
    fun mutationInterruptionLeavesSourceAbsentAndJournalSurvives() =
        runBlocking {
            database.seedDefaultMutationState(0L)
            val day = LocalDate.of(2026, 1, 15)
            val dayMs = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val oldSummary = createTestDailySummary(day, 80f)
            database.dailySummaryDao().upsert(oldSummary)

            val sourceRef =
                database.sourceRecordDao().insertIgnore(
                    HealthSourceRecordEntity(
                        sourceRecordId = "source-hr-42",
                        recordType = "HEART_RATE",
                        createdAtMs = dayMs,
                    ),
                )
            database.heartRateDao().upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        sourceRecordRef = sourceRef,
                        timestampMs = dayMs + 3600_000L,
                        beatsPerMinute = 70,
                        recordType = "RESTING",
                        sessionId = null,
                        deviceName = "Watch",
                    ),
                ),
            )

            changeStore.deleteRecord(HealthDataType.HEART_RATE, "source-hr-42")
            reopenDatabase()

            assertNull(database.sourceRecordDao().getSourceRef("source-hr-42"))
            assertEquals(1, database.dirtyRangeDao().pending(100).size)
            val pending = database.dirtyRangeDao().pending(100).first()
            assertEquals(day.toEpochDay(), pending.nextEpochDay)
            assertTrue(pending.endEpochDayInclusive >= day.toEpochDay())
            assertEquals(1L, database.healthMutationStateDao().current().sourceGeneration)

            changeStore.deleteRecord(HealthDataType.HEART_RATE, "source-hr-42")
            assertEquals(1, database.dirtyRangeDao().pending(100).size)
            assertEquals(pending.id, database.dirtyRangeDao().pending(100).first().id)
            assertEquals(1L, database.healthMutationStateDao().current().sourceGeneration)
        }

    @Test
    fun mutationRollbackLeavesSourceAndJournalUnchanged() =
        runBlocking {
            database.seedDefaultMutationState(0L)
            val day = LocalDate.of(2026, 1, 15)
            val dayMs = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val oldSummary = createTestDailySummary(day, 80f)
            database.dailySummaryDao().upsert(oldSummary)

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    transactionRunner.runInTransaction {
                        database.sourceRecordDao().insertIgnore(
                            HealthSourceRecordEntity(
                                sourceRecordId = "temp-rollback",
                                recordType = "HEART_RATE",
                                createdAtMs = dayMs,
                            ),
                        )
                        database.dirtyRangeDao().insert(
                            DirtyRangeEntity(
                                sourceGeneration = 2L,
                                startEpochDay = day.plusDays(10).toEpochDay(),
                                nextEpochDay = day.plusDays(10).toEpochDay(),
                                endEpochDayInclusive = day.plusDays(12).toEpochDay(),
                                reason = "temp",
                                scoringSnapshotId = "s1",
                            ),
                        )
                        error("Simulated rollback")
                    }
                }
            }

            reopenDatabase()
            assertNull(database.sourceRecordDao().getSourceRef("temp-rollback"))
            assertEquals(0, database.dirtyRangeDao().pending(100).size)
            assertEquals(oldSummary, database.dailySummaryDao().getByDate(dayMs))
        }

    @Test
    fun publicationInterruptionPreservesOldSummaryAndPendingTicket() =
        runBlocking {
            database.seedDefaultMutationState(1L)
            val day = LocalDate.of(2026, 2, 1)
            val dayMs = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val oldSummary = createTestDailySummary(day, 70f)
            database.dailySummaryDao().upsert(oldSummary)
            database.insertTestWorkout("workout-adv-fail", dayMs)
            val ticketId = database.insertTestTicket(day = day)
            val publication = publisher.captureDay(day)
            val updatedSummary = oldSummary.copy(sleepScore = 95f)

            // Another publisher advances the captured cursor before this publication commits.
            assertEquals(
                1,
                database.dirtyRangeDao().advance(ticketId, 1L, day.toEpochDay(), day.plusDays(1).toEpochDay()),
            )
            val published = publisher.publishDay(publication) {
                database.dailySummaryDao().upsert(updatedSummary)
                val workout = checkNotNull(database.workoutDao().getById("workout-adv-fail"))
                database.workoutDao().upsertAll(listOf(workout.copy(modelTrimp = 42f)))
            }
            assertFalse(published)

            reopenDatabase()
            val pending = database.dirtyRangeDao().pending(100)
            assertEquals(1, pending.size)
            assertEquals(day.plusDays(1).toEpochDay(), pending.first().nextEpochDay)
            assertEquals(oldSummary, database.dailySummaryDao().getByDate(dayMs))
            assertNull(database.workoutDao().getById("workout-adv-fail")?.modelTrimp)
        }

    @Test
    fun successfulPublicationAdvancesTicketCursor() =
        runBlocking {
            database.seedDefaultMutationState(1L)
            val day = LocalDate.of(2026, 2, 1)
            val dayMs = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val oldSummary = createTestDailySummary(day, 70f)
            database.dailySummaryDao().upsert(oldSummary)
            database.insertTestTicket(day = day)
            val publication = publisher.captureDay(day)
            val newSummary = createTestDailySummary(day, 88f)

            assertTrue(publisher.publishDay(publication) { database.dailySummaryDao().upsert(newSummary) })

            reopenDatabase()
            val pending = database.dirtyRangeDao().pending(100)
            assertEquals(1, pending.size)
            assertEquals(day.plusDays(1).toEpochDay(), pending.first().nextEpochDay)
            assertEquals(day.plusDays(100).toEpochDay(), pending.first().endEpochDayInclusive)
            assertEquals(88f, database.dailySummaryDao().getByDate(dayMs)?.sleepScore)
        }

    @Test
    fun stalePublicationRejectedWhenGenerationIncrements() =
        runBlocking {
            database.seedDefaultMutationState(1L)
            val day = LocalDate.of(2026, 2, 1)
            val publishDay = day.plusDays(1)
            val dayMs = publishDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val oldSummary = createTestDailySummary(publishDay, 70f)
            database.dailySummaryDao().upsert(oldSummary)
            val ticketId = database.insertTestTicket(day = day, nextDay = publishDay)
            val publication = publisher.captureDay(publishDay)
            database.healthMutationStateDao().incrementGeneration()
            assertEquals(2L, database.healthMutationStateDao().current().sourceGeneration)

            val published = publisher.publishDay(publication) {
                database.dailySummaryDao().upsert(createTestDailySummary(publishDay, 88f))
            }
            assertFalse(published)

            reopenDatabase()
            val pending = database.dirtyRangeDao().pending(100)
            assertEquals(1, pending.size)
            assertEquals(ticketId, pending.first().id)
            assertEquals(publishDay.toEpochDay(), pending.first().nextEpochDay)
            assertEquals(oldSummary, database.dailySummaryDao().getByDate(dayMs))
        }

    @Test
    fun unavailableAssemblyIsRejectedWithoutTouchingSummaryOrTicket() =
        runBlocking {
            database.seedDefaultMutationState(1L)
            val day = LocalDate.of(2026, 2, 1)
            val dayMs = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val oldSummary = createTestDailySummary(day, 70f)
            database.dailySummaryDao().upsert(oldSummary)
            database.insertTestWorkout("workout-unavailable", dayMs)
            database.insertTestTicket(day = day)
            val loader = scoringDayLoader()
            val publication = loader.captureDayPublication(day)

            assertFalse(loader.persistDayAssembly(
                DayAssembly.Unavailable("FINAL_ASSEMBLY_FAILED"),
                ZoneOffset.UTC,
                emptyList(),
                emptyList(),
                publication,
            ))

            reopenDatabase()
            assertEquals(oldSummary, database.dailySummaryDao().getByDate(dayMs))
            assertNull(database.workoutDao().getById("workout-unavailable")?.modelTrimp)
            val pending = database.dirtyRangeDao().pending(100)
            assertEquals(1, pending.size)
            assertEquals(day.toEpochDay(), pending.first().nextEpochDay)
        }

    @Test
    fun absentAssemblyPublishesAndAdvancesTicketLikeComputed() =
        runBlocking {
            database.seedDefaultMutationState(1L)
            val day = LocalDate.of(2026, 2, 1)
            val dayMs = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            database.dailySummaryDao().upsert(createTestDailySummary(day, 70f))
            database.insertTestTicket(day = day)
            val loader = scoringDayLoader()
            val publication = loader.captureDayPublication(day)
            val absentSummary = DailySummary(
                date = day,
                sleepScore = null,
                readinessResult = ReadinessResult.EMPTY,
                isCalibrating = false,
            )

            assertTrue(loader.persistDayAssembly(
                DayAssembly.Absent(absentSummary),
                ZoneOffset.UTC,
                emptyList(),
                emptyList(),
                publication,
            ))

            reopenDatabase()
            val pending = database.dirtyRangeDao().pending(100)
            assertEquals(1, pending.size)
            assertEquals(day.plusDays(1).toEpochDay(), pending.first().nextEpochDay)
            assertNull(database.dailySummaryDao().getByDate(dayMs)?.sleepScore)
        }

    @Test
    fun completingPublicationDeletesTicket() = runBlocking {
        database.seedDefaultMutationState(1L)
        val day = LocalDate.of(2026, 2, 1)
        database.insertTestTicket(day = day, endInclusive = day)
        val publication = publisher.captureDay(day)

        assertTrue(publisher.publishDay(publication) {
            database.dailySummaryDao().upsert(createTestDailySummary(day, 88f))
        })

        reopenDatabase()
        assertEquals(0, database.dirtyRangeDao().count())
    }

    @Test
    fun retentionTrimmedTicketPublishesFromRetainedDay() = runBlocking {
        database.seedDefaultMutationState(1L)
        val day = LocalDate.of(2026, 2, 1)
        val retainedDay = day.plusDays(5)
        database.insertTestTicket(day = day, endInclusive = day.plusDays(10))
        dirtyRangeStore.discardBefore(retainedDay)
        val publication = publisher.captureDay(retainedDay)
        assertEquals(1, publication.tickets.size)
        assertEquals(retainedDay.toEpochDay(), publication.tickets.single().nextEpochDay)

        assertTrue(publisher.publishDay(publication) {
            database.dailySummaryDao().upsert(createTestDailySummary(retainedDay, 88f))
        })

        reopenDatabase()
        val pending = database.dirtyRangeDao().pending(100).single()
        assertEquals(retainedDay.plusDays(1).toEpochDay(), pending.nextEpochDay)
        assertEquals(day.plusDays(10).toEpochDay(), pending.endEpochDayInclusive)
    }

    private fun scoringDayLoader() =
        app.readylytics.health.core.database.data.repository.ScoringDayDataLoader(
            database.workoutDao(),
            database.sleepSessionDao(),
            database.dailySummaryDao(),
            transactionRunner,
            publisher,
        )

    private companion object {
        const val DB_NAME = "dirty-mutation-recovery-test.db"
    }
}

private suspend fun HealthDatabase.seedDefaultMutationState(generation: Long = 0L) {
    healthMutationStateDao().upsert(
        HealthMutationStateEntity(
            id = 1,
            sourceGeneration = generation,
            maintenanceOperationId = null,
            maintenancePhase = null,
            backfillAfterSourceRef = 0,
        ),
    )
}

private fun createTestDailySummary(
    date: LocalDate,
    sleepScore: Float,
): DailySummaryEntity =
    DailySummaryMapper.toEntity(
        DailySummary(
            date = date,
            sleepScore = sleepScore,
            readinessResult = ReadinessResult.EMPTY,
            isCalibrating = false,
        ),
        ZoneOffset.UTC,
    )

private suspend fun HealthDatabase.insertTestWorkout(
    id: String,
    dayMs: Long,
): WorkoutRecordEntity {
    val workout =
        WorkoutRecordEntity(
            id = id,
            startTime = dayMs + 1000L,
            endTime = dayMs + 2000L,
            exerciseType = "Run",
            durationMinutes = 15,
            zone1Minutes = 5f,
            zone2Minutes = 5f,
            zone3Minutes = 5f,
            zone4Minutes = 0f,
            zone5Minutes = 0f,
            trimp = 25f,
            avgHr = 135f,
            modelTrimp = null,
        )
    workoutDao().upsertAll(listOf(workout))
    return workout
}

private suspend fun HealthDatabase.insertTestTicket(
    day: LocalDate,
    generation: Long = 1L,
    nextDay: LocalDate = day,
    endInclusive: LocalDate = day.plusDays(100),
    snapshotId: String = "snap-1",
): Long =
    dirtyRangeDao().insert(
        DirtyRangeEntity(
            sourceGeneration = generation,
            startEpochDay = day.toEpochDay(),
            nextEpochDay = nextDay.toEpochDay(),
            endEpochDayInclusive = endInclusive.toEpochDay(),
            reason = "test_publication",
            scoringSnapshotId = snapshotId,
        ),
    )
