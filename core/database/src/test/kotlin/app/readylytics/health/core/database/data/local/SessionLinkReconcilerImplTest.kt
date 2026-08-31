package app.readylytics.health.core.database.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.domain.heartrate.ZoneThresholds
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HC-002: proves batched workout recomputation (groups of 20) produces identical TRIMP/zone/avgHr
 * output to the pure [ZoneThresholds.computeMetrics] function, at batch-interior indices and at
 * batch boundaries (20, 40), and that it opens far fewer transactions than one-per-workout.
 */
@RunWith(AndroidJUnit4::class)
class SessionLinkReconcilerImplTest {
    private lateinit var database: HealthDatabase
    private lateinit var workoutDao: WorkoutDao
    private lateinit var heartRateDao: HeartRateDao
    private lateinit var hrvDao: HrvDao
    private lateinit var sleepSessionDao: SleepSessionDao
    private lateinit var countingRunner: CountingTransactionRunner
    private lateinit var reconciler: SessionLinkReconcilerImpl

    private val zoneThresholds = ZoneThresholds.create()

    private class CountingTransactionRunner(
        private val delegate: TransactionRunner,
    ) : TransactionRunner {
        var transactionCount = 0
            private set

        override suspend fun <R> runInTransaction(block: suspend () -> R): R {
            transactionCount++
            return delegate.runInTransaction(block)
        }
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        workoutDao = database.workoutDao()
        heartRateDao = database.heartRateDao()
        hrvDao = database.hrvDao()
        sleepSessionDao = database.sleepSessionDao()
        countingRunner = CountingTransactionRunner(RoomTransactionRunner(database))
        reconciler =
            SessionLinkReconcilerImpl(
                sleepSessionDao = sleepSessionDao,
                workoutDao = workoutDao,
                heartRateDao = heartRateDao,
                hrvDao = hrvDao,
                transactionRunner = countingRunner,
            )
    }

    @After
    fun cleanup() {
        database.close()
    }

    @Test
    fun `recomputes 45 workouts in 3 batches with metrics matching the pure calculator`() =
        runTest {
            val workouts = seedWorkoutsWithExerciseSamples(workoutCount = 45)

            reconciler.reconcile(startMs = 0L, endMs = workouts.last().endTime, zoneThresholds = zoneThresholds)

            // ceil(45 / 20) = 3 workout-recompute batches (HC-002's target of this test), plus 1
            // relinkHeartRate transaction: all 45 EXERCISE samples fall inside their workout's span
            // and start with sessionId == null, so the (pre-existing, unmodified by HC-002) HR relink
            // pass tags all 45 with their workout's sessionId in a single getKeysetPage batch. Sleep
            // and HRV relink phases fire zero transactions here because there are no sleep spans and
            // no HRV rows to relink.
            assertEquals(4, countingRunner.transactionCount)

            // Check batch-interior and batch-boundary workouts (0, 19, 20, 39, 40, 44).
            listOf(0, 19, 20, 39, 40, 44).forEach { index -> assertMatchesPureCalculator(workouts[index]) }
        }

    /**
     * Inserts [workoutCount] chronologically-adjacent workouts (100s apart, 60s long) plus a
     * source-record row and one 140bpm EXERCISE heart-rate sample at each workout's midpoint.
     */
    private suspend fun seedWorkoutsWithExerciseSamples(workoutCount: Int): List<WorkoutRecordEntity> {
        val sourceRefs = (1..workoutCount).map { it.toLong() }
        database.sourceRecordDao().insertAll(
            sourceRefs.map { ref ->
                HealthSourceRecordEntity(
                    id = ref,
                    sourceRecordId = "seed-$ref",
                    recordType = "HEART_RATE",
                    createdAtMs = 0L,
                )
            },
        )

        val workouts =
            (0 until workoutCount).map { i ->
                val start = i * 100_000L
                val end = start + 60_000L
                WorkoutRecordEntity(
                    id = "w$i",
                    startTime = start,
                    endTime = end,
                    exerciseType = "Run",
                    // Garbage placeholder metrics -- reconcile must overwrite every one of these.
                    durationMinutes = 0,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 0f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 0f,
                    avgHr = 0f,
                )
            }
        workoutDao.upsertAll(workouts)

        // One EXERCISE sample at each workout's midpoint, 140 bpm -> zone index 2 (zone3Minutes).
        heartRateDao.upsertAll(
            workouts.mapIndexed { index, workout ->
                HeartRateRecordEntity(
                    sourceRecordRef = sourceRefs[index],
                    timestampMs = workout.startTime + 30_000L,
                    beatsPerMinute = 140,
                    recordType = "EXERCISE",
                )
            },
        )
        return workouts
    }

    /** Asserts the persisted [workout] matches [ZoneThresholds.computeMetrics] run on its own single sample. */
    private suspend fun assertMatchesPureCalculator(workout: WorkoutRecordEntity) {
        val expected =
            ZoneThresholds.computeMetrics(
                workout.startTime,
                workout.endTime,
                listOf(
                    DomainHeartRateSample(
                        time = Instant.ofEpochMilli(workout.startTime + 30_000L),
                        beatsPerMinute = 140,
                    ),
                ),
                zoneThresholds,
            )
        val actual = requireNotNull(workoutDao.getById(workout.id))
        assertEquals(expected.durationMinutes, actual.durationMinutes)
        assertEquals(expected.trimp, actual.trimp)
        assertEquals(expected.avgHr, actual.avgHr)
        val actualZones =
            floatArrayOf(
                actual.zone1Minutes,
                actual.zone2Minutes,
                actual.zone3Minutes,
                actual.zone4Minutes,
                actual.zone5Minutes,
            )
        assertTrue(expected.zoneMinutes.contentEquals(actualZones))
    }
}
