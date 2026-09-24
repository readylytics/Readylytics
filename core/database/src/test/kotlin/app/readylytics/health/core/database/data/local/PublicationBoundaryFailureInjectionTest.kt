package app.readylytics.health.core.database.data.local

import android.content.Context
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.mapper.DailySummaryMapper
import app.readylytics.health.core.database.data.repository.DirtySummaryPublisher
import app.readylytics.health.core.database.data.repository.ScoringDayDataLoader
import app.readylytics.health.core.databaseschema.data.local.dao.DirtyRangeDao
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.DirtyRangeEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HealthMutationStateEntity
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.ReadinessResult
import app.readylytics.health.core.model.domain.scoring.DayAssembly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PublicationBoundaryFailureInjectionTest {
    private lateinit var context: Context
    private lateinit var database: HealthDatabase
    private lateinit var transactionRunner: RoomTransactionRunner
    private lateinit var publisher: DirtySummaryPublisher

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
        publisher =
            DirtySummaryPublisher(
                transactionRunner = transactionRunner,
                healthMutationStateDao = database.healthMutationStateDao(),
                dirtyRangeDao = database.dirtyRangeDao(),
                dailySummaryDao = database.dailySummaryDao(),
                workoutDao = database.workoutDao(),
            )
    }

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    private fun productionLoader(customPublisher: DirtySummaryPublisher = publisher) =
        ScoringDayDataLoader(
            database.workoutDao(),
            database.sleepSessionDao(),
            database.dailySummaryDao(),
            transactionRunner,
            customPublisher,
        )

    @Test
    fun throwBeforePublicationLeavesTicketAndPreviousSummaryIntact() =
        runBlocking {
            database.seedDefaultMutationState(1L)
            val day = LocalDate.of(2026, 2, 1)
            val old = createTestDailySummary(day, 70f)
            database.dailySummaryDao().upsert(old)
            database.insertTestTicket(day, endInclusive = day)
            val loader = productionLoader()
            loader.captureDayPublication(day)

            // Simulate failure before publication (e.g. calculation throws before persistDayAssembly)
            val error =
                runCatching {
                    error("Injected calculation failure before publication")
                }.exceptionOrNull()
            assertTrue(error is IllegalStateException)

            reopenDatabase()
            assertEquals(old, database.dailySummaryDao().getByDate(old.dateMidnightMs))
            assertEquals(
                day.toEpochDay(),
                database
                    .dirtyRangeDao()
                    .pending(100)
                    .single()
                    .nextEpochDay,
            )
        }

    @Test
    fun throwAfterSummaryWriteBeforeTicketAdvanceRollsBackBothSummaryAndTicket() =
        runBlocking {
            database.seedDefaultMutationState(1L)
            val day = LocalDate.of(2026, 2, 1)
            val old = createTestDailySummary(day, 70f)
            database.dailySummaryDao().upsert(old)
            database.insertTestTicket(day, endInclusive = day)

            val failingDirtyDao =
                object : DirtyRangeDao by database.dirtyRangeDao() {
                    override suspend fun advance(
                        id: Long,
                        generation: Long,
                        expectedDay: Long,
                        nextDay: Long,
                    ): Int {
                        error("Injected failure during ticket advance")
                    }
                }
            val failingPublisher =
                DirtySummaryPublisher(
                    transactionRunner = transactionRunner,
                    healthMutationStateDao = database.healthMutationStateDao(),
                    dirtyRangeDao = failingDirtyDao,
                    dailySummaryDao = database.dailySummaryDao(),
                    workoutDao = database.workoutDao(),
                )
            val loader = productionLoader(failingPublisher)
            val publication = loader.captureDayPublication(day)
            val newSummary = DailySummaryMapper.toDomain(old.copy(sleepScore = 95f), ZoneOffset.UTC)

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    loader.persistDayAssembly(
                        DayAssembly.Computed(newSummary),
                        ZoneOffset.UTC,
                        emptyList(),
                        emptyList(),
                        publication,
                    )
                }
            }

            reopenDatabase()
            assertEquals(old, database.dailySummaryDao().getByDate(old.dateMidnightMs))
            assertEquals(
                day.toEpochDay(),
                database
                    .dirtyRangeDao()
                    .pending(100)
                    .single()
                    .nextEpochDay,
            )
        }

    @Test
    fun throwBetweenCommittedDayAndCheckpointLeavesCommittedDayIntactAndTicketAdvanced() =
        runBlocking {
            database.seedDefaultMutationState(1L)
            val day1 = LocalDate.of(2026, 2, 1)
            val day2 = LocalDate.of(2026, 2, 2)
            database.insertTestTicket(day1, endInclusive = day2)
            val loader = productionLoader()

            val summary1 = DailySummaryMapper.toDomain(createTestDailySummary(day1, 80f), ZoneOffset.UTC)
            val publication1 = loader.captureDayPublication(day1)
            assertTrue(
                loader.persistDayAssembly(
                    DayAssembly.Computed(summary1),
                    ZoneOffset.UTC,
                    emptyList(),
                    emptyList(),
                    publication1,
                ),
            )

            // Simulate failure between committed day 1 and checkpoint save (e.g. before day 2 or checkpoint)
            val error =
                runCatching {
                    error("Crash between committed day and checkpoint")
                }.exceptionOrNull()
            assertTrue(error is IllegalStateException)

            reopenDatabase()
            // Day 1 summary is intact and committed
            val day1Ms = day1.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            assertEquals(80f, database.dailySummaryDao().getByDate(day1Ms)?.sleepScore)
            // Ticket was advanced to day 2 (day 1 + 1)
            val pending = database.dirtyRangeDao().pending(100)
            assertEquals(1, pending.size)
            assertEquals(day2.toEpochDay(), pending.single().nextEpochDay)

            // Retry recomputing day 2 succeeds and produces final rows
            val retryLoader = productionLoader()
            val summary2 = DailySummaryMapper.toDomain(createTestDailySummary(day2, 85f), ZoneOffset.UTC)
            val publication2 = retryLoader.captureDayPublication(day2)
            assertTrue(
                retryLoader.persistDayAssembly(
                    DayAssembly.Computed(summary2),
                    ZoneOffset.UTC,
                    emptyList(),
                    emptyList(),
                    publication2,
                ),
            )
            reopenDatabase()
            assertEquals(0, database.dirtyRangeDao().count())
            val day2Ms = day2.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            assertEquals(85f, database.dailySummaryDao().getByDate(day2Ms)?.sleepScore)
        }

    @Test
    fun cancellationAfterCommittedDayPreservesCommittedDay() =
        runBlocking {
            database.seedDefaultMutationState(1L)
            val day1 = LocalDate.of(2026, 2, 1)
            val day2 = LocalDate.of(2026, 2, 2)
            database.insertTestTicket(day1, endInclusive = day2)
            val loader = productionLoader()

            val summary1 = DailySummaryMapper.toDomain(createTestDailySummary(day1, 80f), ZoneOffset.UTC)
            val publication1 = loader.captureDayPublication(day1)
            assertTrue(
                loader.persistDayAssembly(
                    DayAssembly.Computed(summary1),
                    ZoneOffset.UTC,
                    emptyList(),
                    emptyList(),
                    publication1,
                ),
            )

            // Cancellation occurs after day 1 committed
            val cancellation =
                runCatching {
                    throw CancellationException("Job cancelled")
                }.exceptionOrNull()
            assertTrue(cancellation is CancellationException)

            reopenDatabase()
            val day1Ms = day1.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            assertEquals(80f, database.dailySummaryDao().getByDate(day1Ms)?.sleepScore)
            assertEquals(
                day2.toEpochDay(),
                database
                    .dirtyRangeDao()
                    .pending(100)
                    .single()
                    .nextEpochDay,
            )
        }

    @Test
    fun roomInvalidationsEmittedPerCommittedDayDuringMultiDayRecompute() =
        runBlocking {
            database.seedDefaultMutationState(1L)
            val startDay = LocalDate.of(2026, 2, 1)
            database.insertTestTicket(startDay, endInclusive = startDay.plusDays(29))
            val loader = productionLoader()

            val invalidationCount = AtomicInteger(0)
            val observer =
                object : InvalidationTracker.Observer("daily_summaries") {
                    override fun onInvalidated(tables: Set<String>) {
                        invalidationCount.incrementAndGet()
                    }
                }
            database.invalidationTracker.addObserver(observer)
            database.invalidationTracker.refreshVersionsSync()

            for (i in 0 until 5) {
                val currentDay = startDay.plusDays(i.toLong())
                val summary = DailySummaryMapper.toDomain(createTestDailySummary(currentDay, 80f + i), ZoneOffset.UTC)
                val publication = loader.captureDayPublication(currentDay)
                assertTrue(
                    loader.persistDayAssembly(
                        DayAssembly.Computed(summary),
                        ZoneOffset.UTC,
                        emptyList(),
                        emptyList(),
                        publication,
                    ),
                )
                database.invalidationTracker.refreshVersionsSync()
            }
            database.invalidationTracker.removeObserver(observer)
            assertTrue(invalidationCount.get() >= 4)
        }

    private companion object {
        const val DB_NAME = "publication-boundary-failure-injection-robolectric.db"
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
