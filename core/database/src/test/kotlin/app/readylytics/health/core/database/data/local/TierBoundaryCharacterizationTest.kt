package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.database.data.repository.ScoringDayDataLoader
import app.readylytics.health.core.database.data.repository.ScoringHistoryRepositoryImpl
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.scoring.domain.scoring.RasCalculator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * R2-DB-001 / R2-DB-004 characterization. The R2-DB-001 tests were originally written (Phase 0)
 * to lock in the incorrect hot-only tier behavior; they were flipped in Task 1 of the Phase-1
 * plan once the hot/warm union landed, and now assert the correct unioned behavior plus the
 * non-overlap invariant it depends on. The R2-DB-004 drift test recorded the Phase-0 flat-mean
 * warm-reconstruction drift baseline (p25Delta<=10, p75Delta<=10, trimpDelta<=20f); Task 4 of the
 * Phase-1 plan replaced flat-mean replay with percentile-sketch interpolation (falling back to a
 * 3-point min/avg/max replay for pre-v15 buckets) and tightened this test's threshold to the
 * newly measured, much smaller bound -- see `WarmTierReconstructor` and
 * `internal-docs/DATA_FLOW.md`'s "Determinism across tiers" note.
 */
@RunWith(RobolectricTestRunner::class)
class TierBoundaryCharacterizationTest {
    private lateinit var database: HealthDatabase

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // R2-DB-001: fixed in Task 1 of the Phase-1 plan — unions hot and warm.
    @Test
    fun `straddling sleep session returns both its hot and warm halves`() {
        val (hot, warm) = seedStraddlingSleepSessionAndRollUp()
        val repo = scoringHistoryRepository()
        val all = runBlocking { repo.getSleepHrSamplesForSession(SLEEP_SESSION_ID) }
        assertEquals(hot.size + warm.size, all.size)
    }

    // R2-DB-001: fixed in Task 1 — the batch projection now unions every session's hot and warm samples.
    @Test
    fun `projection includes both hot and warm samples for a straddling session`() {
        val (hot, warm) = seedStraddlingSleepSessionAndRollUp()
        val repo = scoringHistoryRepository()
        val projected = runBlocking { repo.getSleepHrProjectionForSessions(listOf(SLEEP_SESSION_ID)) }
        assertEquals(hot.size + warm.size, projected.size)
    }

    // R2-DB-001: fixed in Task 1 — the average is now computed over the unioned sample set.
    @Test
    fun `average sleep hr is computed from both hot and warm samples`() {
        val (hot, warm) = seedStraddlingSleepSessionAndRollUp()
        val repo = scoringHistoryRepository()
        val avg = runBlocking { repo.getAvgSleepHrForSessions(listOf(SLEEP_SESSION_ID)) }
        val expected = (hot.map { it.beatsPerMinute } + warm.map { it.beatsPerMinute }).average().roundToInt()
        assertEquals(expected, avg[SLEEP_SESSION_ID])
    }

    // R2-DB-001: fixed in Task 1 — a straddling workout now returns both halves.
    @Test
    fun `straddling workout returns both its hot and warm halves`() {
        val workout =
            WorkoutRecordEntity(
                id = WORKOUT_ID,
                startTime = Instant.parse("2026-01-11T01:00:00Z").toEpochMilli(),
                endTime = Instant.parse("2026-01-11T02:59:00Z").toEpochMilli(),
                exerciseType = "RUNNING",
                durationMinutes = 120,
                zone1Minutes = 0f, zone2Minutes = 0f, zone3Minutes = 0f,
                zone4Minutes = 0f, zone5Minutes = 0f,
                trimp = 0f, avgHr = 0f,
            )
        val hot = seedStraddlingWorkoutAndRollUp(workout)
        val loader =
            ScoringDayDataLoader(
                workoutDao = database.workoutDao(),
                sleepSessionDao = database.sleepSessionDao(),
                dailySummaryDao = database.dailySummaryDao(),
                heartRateDao = database.heartRateDao(),
                minuteBucketDao = database.minuteBucketDao(),
                weightRecordDao = database.weightRecordDao(),
                bodyFatRecordDao = database.bodyFatRecordDao(),
                bloodPressureRecordDao = database.bloodPressureRecordDao(),
                oxygenSaturationRecordDao = database.oxygenSaturationRecordDao(),
                bodyTemperatureRecordDao = database.bodyTemperatureRecordDao(),
            )
        val hotSamples = runBlocking { loader.loadExerciseHrSamples(listOf(workout)) }
        val result = runBlocking { loader.loadWorkoutSamples(workout, hotSamples) }
        val warmCount = 120 - hot.size
        assertEquals(hot.size + warmCount, result.size)
    }

    // R2-DB-001: the invariant every union above depends on — rollup never leaves a raw row
    // at or before its own cutoff, so hot ∪ warm never double-counts a minute.
    @Test
    fun `rollup leaves no raw row at or before its own cutoff`() {
        seedStraddlingSleepSessionAndRollUp()
        val cutoffMs = Instant.parse("2026-01-11T02:00:00Z").toEpochMilli()
        val remainingBeforeCutoff = runBlocking { database.heartRateDao().countInRange(0L, cutoffMs - 1) }
        assertEquals(0, remainingBeforeCutoff)
    }

    // R2-DB-004: records the CURRENT drift baseline; WP-05 tightens the bound and publishes it.
    @Test
    fun `warm reconstruction drift is recorded for a fully hot fixture day`() {
        val rawSamples = seedFullyHotDayAndRollUp()
        val repo = scoringHistoryRepository()
        val warm = runBlocking { repo.getSleepHrSamplesForSession(SLEEP_SESSION_ID) }

        val prefs = UserPreferences()
        val rawP25 = percentile(rawSamples, 0.25)
        val warmP25 = percentile(warm, 0.25)
        val rawP50 = percentile(rawSamples, 0.50)
        val warmP50 = percentile(warm, 0.50)
        val rawP75 = percentile(rawSamples, 0.75)
        val warmP75 = percentile(warm, 0.75)
        val rawTrimp = trimpOver(rawSamples, prefs)
        val warmTrimp = trimpOver(warm, prefs)

        val p25Delta = abs(rawP25 - warmP25)
        val p75Delta = abs(rawP75 - warmP75)
        val trimpDelta = abs(rawTrimp - warmTrimp)
        assertTrue(
            "R2-DB-004 drift bound (Task 4, percentile-sketch reconstruction): p25 $rawP25->$warmP25 " +
                "(Δ=$p25Delta), p50 $rawP50->$warmP50, p75 $rawP75->$warmP75 (Δ=$p75Delta), " +
                "TRIMP ${"%.3f".format(rawTrimp)}->${"%.3f".format(warmTrimp)} (Δ=${"%.3f".format(trimpDelta)}). " +
                "Measured on this fixture: p25Delta=0, p75Delta=0, trimpDelta≈2.608 (down from the Phase-0 " +
                "flat-mean baseline of p25Delta<=10, p75Delta<=10, trimpDelta<=20); threshold tightened to " +
                "the measured bound plus a small margin.",
            p25Delta <= P25_P75_DRIFT_BOUND_BPM &&
                p75Delta <= P25_P75_DRIFT_BOUND_BPM &&
                trimpDelta <= TRIMP_DRIFT_BOUND,
        )
    }

    private fun scoringHistoryRepository(): ScoringHistoryRepositoryImpl =
        ScoringHistoryRepositoryImpl(
            database.heartRateDao(),
            database.hrvDao(),
            database.sleepSessionDao(),
            database.dailySummaryDao(),
            database.minuteBucketDao(),
        )

    private fun seedStraddlingSleepSessionAndRollUp(): Pair<List<HeartRateRecordEntity>, List<HeartRateRecordEntity>> {
        val sourceRef =
            runBlocking {
                database.sourceRecordDao().getOrCreateSourceRef("straddle-hr-src", "HEART_RATE", 0L)
            }
        val startMs = Instant.parse("2026-01-10T22:00:00Z").toEpochMilli()
        val cutoffMs = Instant.parse("2026-01-11T02:00:00Z").toEpochMilli()
        val samples =
            (0 until 97).map { i ->
                HeartRateRecordEntity(
                    sourceRecordRef = sourceRef,
                    timestampMs = startMs + i * 5 * 60_000L,
                    beatsPerMinute = 55 + (i % 20),
                    recordType = "SLEEP",
                    sessionId = SLEEP_SESSION_ID,
                )
            }
        runBlocking { database.heartRateDao().upsertAll(samples) }
        rollUp(cutoffMs)
        return samples.partition { it.timestampMs >= cutoffMs }
    }

    private fun seedStraddlingWorkoutAndRollUp(
        workout: WorkoutRecordEntity,
    ): List<HeartRateRecordEntity> {
        val sourceRef =
            runBlocking {
                database.sourceRecordDao().getOrCreateSourceRef("straddle-w-src", "HEART_RATE", 0L)
            }
        val cutoffMs = Instant.parse("2026-01-11T02:00:00Z").toEpochMilli()
        val samples =
            (0 until 120).map { i ->
                HeartRateRecordEntity(
                    sourceRecordRef = sourceRef,
                    timestampMs = Instant.parse("2026-01-11T01:00:00Z").toEpochMilli() + i * 60_000L,
                    beatsPerMinute = 120 + (i % 30),
                    recordType = "EXERCISE",
                    sessionId = WORKOUT_ID,
                )
            }
        runBlocking {
            database.heartRateDao().upsertAll(samples)
            database.workoutDao().upsertAll(listOf(workout))
        }
        rollUp(cutoffMs)
        return samples.filter { it.timestampMs >= cutoffMs }
    }

    private fun seedFullyHotDayAndRollUp(): List<Int> {
        val sourceRef =
            runBlocking {
                database.sourceRecordDao().getOrCreateSourceRef("hot-day-src", "HEART_RATE", 0L)
            }
        val startMs = Instant.parse("2026-01-05T22:00:00Z").toEpochMilli()
        val cutoffMs = Instant.parse("2026-01-11T02:00:00Z").toEpochMilli()
        val samples =
            (0 until 2400).map { i ->
                HeartRateRecordEntity(
                    sourceRecordRef = sourceRef,
                    timestampMs = startMs + i * 12_000L,
                    // Intra-minute variance (5 samples/minute cycling 55..67) guarantees the flat-mean
                    // warm reconstruction cannot reproduce the raw distribution.
                    beatsPerMinute = 55 + (i % 13),
                    recordType = "SLEEP",
                    sessionId = SLEEP_SESSION_ID,
                )
            }
        runBlocking { database.heartRateDao().upsertAll(samples) }
        rollUp(cutoffMs)
        return samples.map { it.beatsPerMinute }
    }

    private fun rollUp(cutoffMs: Long) {
        runBlocking {
            DataRollupManager(
                minuteBucketDao = database.minuteBucketDao(),
                heartRateDao = database.heartRateDao(),
                transactionRunner = RoomTransactionRunner(database),
            ).rollupExpiredHotTier(cutoffMs)
        }
    }

    private fun percentile(
        values: List<Int>,
        p: Double,
    ): Int {
        val sorted = values.sorted()
        if (sorted.isEmpty()) return 0
        return sorted[((sorted.size - 1) * p).toInt()]
    }

    private fun trimpOver(
        values: List<Int>,
        prefs: UserPreferences,
    ): Float =
        values.sumOf { bpm ->
            RasCalculator.calculateDailyTrimp(
                durationMinutes = 1f,
                hrAvg = bpm.toFloat(),
                rhrBaseline = 60f,
                hrMax = 190f,
                gender = prefs.gender,
                trimpModel = prefs.trimpModel,
                banisterMultiplier = prefs.banisterMultiplier,
                chengBeta = prefs.chengBeta,
                itrimB = prefs.itrimB,
                ltBpm = prefs.zone3MaxBpm.toFloat(),
            ).toDouble()
        }.toFloat()

    private companion object {
        const val SLEEP_SESSION_ID = "sleep-1"
        const val WORKOUT_ID = "w1"

        // R2-DB-004 (Task 4): tightened from the Phase-0 flat-mean baseline (p25<=10, p75<=10,
        // trimp<=20f) once percentile-sketch reconstruction landed. Measured on this fixture:
        // p25Delta=0, p75Delta=0, trimpDelta≈2.608 -- bounds below are the measured value plus a
        // small margin, not the loose Phase-0 placeholder.
        const val P25_P75_DRIFT_BOUND_BPM = 2
        const val TRIMP_DRIFT_BOUND = 3f
    }
}
