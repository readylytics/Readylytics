package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.scoring.domain.scoring.components.Phase
import app.readylytics.health.core.scoring.domain.scoring.resolveCalibrationState
import javax.inject.Inject

class CalibrationGate
    @Inject
    constructor(
        private val scoringHistoryRepository: ScoringHistoryRepository,
    ) {
        /**
         * A frozen day short-circuits to calibrated -- but only once its persisted
         * `baselineObservationCount`/`snapshotCalibrationPhase` pair is confirmed internally
         * consistent via [resolveCalibrationState]. The freeze stamp alone (`baselineCalculatedAtDate`)
         * is only written once a day has already passed this gate, so for a row with no
         * count/phase metadata at all (pre-C2 legacy data) re-deriving it could only ever disagree
         * with what the user was actually scored against, and it is trusted as before. But when
         * count/phase metadata IS present, it must actually agree -- otherwise this is exactly the
         * "needs repair" case [resolveCalibrationState]'s mismatch branch exists to catch (see
         * `CalibrationState.kt`), and `ComputeSleepMetricsUseCase` will independently resolve that
         * same row to `isCalibrating = true` a few lines later. Trusting the timestamp anyway would
         * route [FinalSummaryAssembler] into the calibrated-summary branch while the persisted
         * summary ends up flagged `isCalibrating = true` -- an internally inconsistent row. Consult
         * the same resolver here so both call sites agree at the point of decision, rather than
         * merely converging after one bad pass.
         *
         * Task C2 (OD-2): the prior-days count comes from
         * [ScoringHistoryRepository.countEligibleSleepDaysThrough] -- the cumulative,
         * unbounded-history maturity counter -- rather than `BaselineComputer`'s HRV mu/sigma
         * *statistical* window (bounded to `HRV_SIGMA_WINDOW_DAYS`), which stays reserved for
         * baseline math only. This is a day-granularity cumulative count through
         * yesterday, so unlike the old HRV-window computation it needs no same-day time bound: a
         * nap recorded later the same day cannot retroactively count as a *prior* day either way,
         * since `hasSession` (today's own eligibility) is applied as a separate `+1` on top of the
         * "through yesterday" count below, resolved by the caller's own live scoring pass (which
         * does apply the wake-time/session bound where relevant, e.g. the morning
         * workout-recommendation path).
         */
        suspend fun isCalibrated(
            context: ScoringDayContext,
            hasSession: Boolean,
        ): Boolean {
            if (context.dailySummary?.baselineCalculatedAtDate != null && isFrozenTrustworthy(context.dailySummary)) {
                return true
            }
            return scoringHistoryRepository
                .countEligibleSleepDaysThrough(
                    endDay = context.targetDate.minusDays(1),
                    zoneId = context.zoneId,
                )?.plus(if (hasSession) 1 else 0)
                ?.let { it >= ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION }
                ?: false
        }

        /**
         * A frozen day with no count/phase metadata at all (pre-C2 legacy rows) is trusted purely
         * from the freeze stamp, as before. Once either field IS present, [resolveCalibrationState]
         * must actually resolve it to a non-calibrating state (i.e. agree the row is mature) --
         * never re-implemented here, always delegated to the one resolver.
         */
        private fun isFrozenTrustworthy(dailySummary: DailySummary): Boolean {
            val frozenCount = dailySummary.baselineObservationCount
            val frozenPhase =
                dailySummary.snapshotCalibrationPhase?.let { name -> runCatching { Phase.valueOf(name) }.getOrNull() }
            if (frozenCount == null && frozenPhase == null) return true
            return !resolveCalibrationState(liveCount = null, frozenCount = frozenCount, frozenPhase = frozenPhase)
                .isCalibrating
        }
    }
