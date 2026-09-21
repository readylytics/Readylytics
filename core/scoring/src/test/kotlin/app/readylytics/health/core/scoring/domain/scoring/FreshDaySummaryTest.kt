package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.ReadinessResult
import app.readylytics.health.core.model.domain.model.RecoveryFlag
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationDecision
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationSnapshot
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationState
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * C3 (WP-13): [freshDaySummary] is the mechanism that stops a deleted day's stale derived fields
 * (sleep score, readiness, recommendation, ...) from ghosting through a later recompute. It must
 * carry forward ONLY the frozen baseline/calibration snapshot fields P2/H6 already treat as "this
 * snapshot is still valid" -- never a derived output.
 */
class FreshDaySummaryTest {
    @Test
    fun `fresh day does not retain deleted sleep`() {
        val previous =
            DailySummary(
                date = LocalDate.of(2026, 1, 1),
                sleepScore = 90f,
                sleepDurationMinutes = 480,
                nocturnalHrv = 55,
            )

        val fresh = freshDaySummary(previous.date, previous)

        assertNull(fresh.sleepScore)
        assertNull(fresh.sleepDurationMinutes)
        assertNull(fresh.nocturnalHrv)
    }

    /**
     * A fully-populated previous day, mixing every frozen baseline/calibration snapshot field
     * (must survive [freshDaySummary]) with every derived/owned output (must never survive it).
     * Shared by the two focused assertions below so each stays under detekt's method-length limit.
     */
    private fun fullyPopulatedPrevious(date: LocalDate): DailySummary =
        DailySummary(
            date = date,
            stepCount = 8500,
            baselineCalculatedAtDate = date.minusDays(1),
            hrMax = 188f,
            snapshotProfile = "BALANCED",
            snapshotCalibrationPhase = "ESTABLISHED",
            hrvSigmaPrior = 0.2f,
            rasScalingFactor = 1.1f,
            baselineObservationCount = 42,
            hrvMuMssd = 3.9f,
            hrvSigmaMssd = 0.3f,
            rhrBpm = 58f,
            rhrSigma = 2.5f,
            // derived/owned -- must never survive
            sleepScore = 91f,
            nocturnalHrv = 60,
            sleepDurationMinutes = 470,
            deepSleepPercent = 20f,
            remSleepPercent = 25f,
            readinessResult =
                ReadinessResult.EMPTY.copy(recoveryFlags = setOf(RecoveryFlag.STRONG_RECOVERY_SIGNAL)),
            trimpWorkoutOnly = 120f,
            totalRasWorkoutOnly = 300f,
            loadScoreWorkoutOnly = 55f,
            trainingReadinessWorkoutOnly = 70f,
            vo2Max = 45f,
            workoutRecommendation =
                WorkoutRecommendationSnapshot(
                    ruleVersion = 1,
                    wakeSessionId = "s1",
                    wakeTimeMs = 1_000L,
                    decision = WorkoutRecommendationDecision(WorkoutRecommendationState.EASY, emptyList()),
                ),
        )

    @Test
    fun `fresh day carries forward the frozen baseline snapshot fields`() {
        val date = LocalDate.of(2026, 3, 10)
        val fresh = freshDaySummary(date, fullyPopulatedPrevious(date))

        // Carried forward: frozen baseline/calibration snapshot + independent stepCount.
        assertEquals(8500, fresh.stepCount)
        assertEquals(date.minusDays(1), fresh.baselineCalculatedAtDate)
        assertEquals(188f, fresh.hrMax)
        assertEquals("BALANCED", fresh.snapshotProfile)
        assertEquals("ESTABLISHED", fresh.snapshotCalibrationPhase)
        assertEquals(0.2f, fresh.hrvSigmaPrior)
        assertEquals(1.1f, fresh.rasScalingFactor)
        assertEquals(42, fresh.baselineObservationCount)
        assertEquals(3.9f, fresh.hrvMuMssd)
        assertEquals(0.3f, fresh.hrvSigmaMssd)
        assertEquals(58f, fresh.rhrBpm)
        assertEquals(2.5f, fresh.rhrSigma)
    }

    @Test
    fun `fresh day never carries forward derived or owned outputs`() {
        val date = LocalDate.of(2026, 3, 10)
        val fresh = freshDaySummary(date, fullyPopulatedPrevious(date))

        assertNull(fresh.sleepScore)
        assertNull(fresh.nocturnalHrv)
        assertNull(fresh.sleepDurationMinutes)
        assertNull(fresh.deepSleepPercent)
        assertNull(fresh.remSleepPercent)
        assertEquals(ReadinessResult.EMPTY, fresh.readinessResult)
        assertNull(fresh.trimpWorkoutOnly)
        assertNull(fresh.totalRasWorkoutOnly)
        assertNull(fresh.loadScoreWorkoutOnly)
        assertNull(fresh.trainingReadinessWorkoutOnly)
        assertNull(fresh.vo2Max)
        assertNull(fresh.workoutRecommendation)
    }

    @Test
    fun `fresh day with null previous is a blank summary for the date`() {
        val date = LocalDate.of(2026, 6, 1)

        val fresh = freshDaySummary(date, null)

        assertEquals(DailySummary(date = date), fresh)
    }

    @Test
    fun `fresh day when baseline was already invalidated upstream carries no frozen metadata`() {
        // Mirrors RoomHealthIngestionStore.clearFrozenBaselines: once a mutation invalidates the
        // frozen snapshot, every one of these fields is already null on the Room row by the time
        // freshDaySummary runs -- forcing full baseline recomputation this pass.
        val date = LocalDate.of(2026, 7, 4)
        val invalidated =
            DailySummary(
                date = date,
                stepCount = 4200,
                baselineCalculatedAtDate = null,
                hrMax = null,
                snapshotProfile = null,
                snapshotCalibrationPhase = null,
                hrvSigmaPrior = null,
                rasScalingFactor = null,
                baselineObservationCount = null,
                hrvMuMssd = null,
                hrvSigmaMssd = null,
                rhrBpm = null,
                rhrSigma = null,
            )

        val fresh = freshDaySummary(date, invalidated)

        assertEquals(4200, fresh.stepCount)
        assertNull(fresh.baselineCalculatedAtDate)
        assertNull(fresh.hrMax)
        assertNull(fresh.snapshotProfile)
        assertNull(fresh.snapshotCalibrationPhase)
        assertNull(fresh.hrvSigmaPrior)
        assertNull(fresh.rasScalingFactor)
        assertNull(fresh.baselineObservationCount)
        assertNull(fresh.hrvMuMssd)
        assertNull(fresh.hrvSigmaMssd)
        assertNull(fresh.rhrBpm)
        assertNull(fresh.rhrSigma)
    }
}
