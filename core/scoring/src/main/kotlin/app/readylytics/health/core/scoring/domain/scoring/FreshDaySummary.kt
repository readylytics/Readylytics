package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.DailySummary
import java.time.LocalDate

/**
 * Builds a genuinely fresh [DailySummary] candidate for [date], carrying forward ONLY the frozen
 * baseline/calibration snapshot fields from [previous] -- the ones [ResolveDailyBaselinesUseCase]
 * and the calibration-phase logic in `ComputeSleepMetricsUseCase` already treat as "this snapshot
 * is still valid, don't recompute it" (guarded there by `baselineCalculatedAtDate != null`) --
 * plus the independent [DailySummary.stepCount] input.
 *
 * Every other field -- every derived output (`sleepScore`, `sleepDurationMinutes`,
 * `nocturnalHrv`, `readinessResult`, `workoutRecommendation`, load/RAS/TRIMP totals, etc.) -- comes
 * back at its default (usually `null`). This is deliberate: those fields are always recomputed by
 * the assembler chain in the same pass, and a fresh candidate must never let a stale value from a
 * previous generation survive assembly by omission (e.g. a deleted sleep session's score ghosting
 * through because nothing this pass happened to overwrite it).
 *
 * [previous] is expected to be the day's current Room row (already reflecting any upstream
 * generation-invalidating mutation -- see `RoomHealthIngestionStore.clearFrozenBaselines`, which
 * nulls exactly the fields below when a mutation invalidates the frozen calibration snapshot).
 * Callers must never widen this by resurrecting a field this function deliberately omits.
 */
fun freshDaySummary(date: LocalDate, previous: DailySummary?): DailySummary =
    DailySummary(date = date).copy(
        stepCount = previous?.stepCount,
        baselineCalculatedAtDate = previous?.baselineCalculatedAtDate,
        hrMax = previous?.hrMax,
        snapshotProfile = previous?.snapshotProfile,
        snapshotCalibrationPhase = previous?.snapshotCalibrationPhase,
        hrvSigmaPrior = previous?.hrvSigmaPrior,
        rasScalingFactor = previous?.rasScalingFactor,
        baselineObservationCount = previous?.baselineObservationCount,
        hrvMuMssd = previous?.hrvMuMssd,
        hrvSigmaMssd = previous?.hrvSigmaMssd,
        rhrBpm = previous?.rhrBpm,
        rhrSigma = previous?.rhrSigma,
    )
