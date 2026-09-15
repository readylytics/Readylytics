package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import javax.inject.Inject

class CalibrationGate
    @Inject
    constructor(
        private val scoringHistoryRepository: ScoringHistoryRepository,
    ) {
        /**
         * A frozen day short-circuits to calibrated: the freeze stamp is only written once a day
         * has already passed this gate, so re-deriving it could only ever disagree with what the
         * user was actually scored against.
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
        ): Boolean =
            context.dailySummary?.baselineCalculatedAtDate != null ||
                scoringHistoryRepository
                    .countEligibleSleepDaysThrough(
                        endDay = context.targetDate.minusDays(1),
                        zoneId = context.zoneId,
                    )?.plus(if (hasSession) 1 else 0)
                    ?.let { it >= ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION }
                ?: false
    }
