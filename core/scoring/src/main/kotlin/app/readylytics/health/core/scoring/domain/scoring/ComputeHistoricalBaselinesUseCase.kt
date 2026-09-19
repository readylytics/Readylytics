package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.ComputeHistoricalBaselinesUseCase

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayPolicy
import app.readylytics.health.core.scoring.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.core.scoring.domain.util.HeartRateFormulas
import app.readylytics.health.core.scoring.domain.util.stdev
import javax.inject.Inject
import kotlin.math.ln

class ComputeHistoricalBaselinesUseCase
    @Inject
    constructor(
        private val baselineComputer: BaselineComputer,
        private val loadScoringStrategy: LoadScoringStrategy,
        private val scoringHistoryRepository: ScoringHistoryRepository,
    ) {
    suspend fun computeHistoricalBaselines(
        allDailySummaries: List<DailySummary>,
        prefs: UserPreferences,
    ): List<DailySummary> {
        if (allDailySummaries.isEmpty()) return emptyList()

        val profile = prefs.physiologyProfile
        val hrMax = HeartRateFormulas.resolveMaxHeartRate(prefs)
        val rasScalingFactor = prefs.rasScalingFactor
        val sigmaPrior = profile.lnSigmaPrior
        val sleepDayPolicy =
            SleepDayPolicy(
                coreMergeGapMinutes = prefs.coreMergeGapMinutes,
                supplementalCutoffMinutesOfDay = prefs.supplementalCutoffMinutesOfDay,
                minimumCountedSleepSegmentMinutes = prefs.minimumCountedSleepSegmentMinutes,
                supplementalArchitectureCoveragePercent = prefs.supplementalArchitectureCoveragePercent,
                scoringZoneId = prefs.scoringZone(),
            )

        // Batch all per-day baseline windows (HRV mu/sigma + RHR) in a fixed, small number of DB
        // reads for the entire history instead of ~11 queries per day (classic N+1). The batched
        // path reproduces the per-day [BaselineComputer] window/validity/nadir logic exactly, so
        // frozen baseline values are identical — see BaselineComputer.computeBackfillBaselines and
        // its equivalence test.
        val baselines =
            baselineComputer.computeBackfillBaselines(
                allDailySummaries,
                prefs.restingHrPercentile,
                zoneId = sleepDayPolicy.scoringZoneId,
                sleepDayPolicy = sleepDayPolicy,
            )

        // Task C2 (WP-12, OD-2 gate): the persisted baselineObservationCount is the cumulative,
        // unbounded maturity counter -- NOT windows.muHistory.size, which is capped at
        // HRV_MU_WINDOW_DAYS (7) and belongs to the HRV statistical window only. One batched scan
        // covers every day in this backfill instead of one full-history scan per day.
        val eligibleDayCounts =
            scoringHistoryRepository.countEligibleSleepDaysThroughBatch(
                allDailySummaries.map { it.date },
                sleepDayPolicy.scoringZoneId,
            )

        return allDailySummaries.mapNotNull { summary ->
            val windows = baselines[summary.date] ?: return@mapNotNull null
            if (windows.muHistory.isEmpty()) return@mapNotNull null

            val lnMuHistory = windows.muHistory.map { ln(it.coerceAtLeast(0.001f)) }
            val lnSigmaHistory = windows.sigmaHistory.map { ln(it.coerceAtLeast(0.001f)) }

            val hrvMu = lnMuHistory.average().toFloat()
            val hrvSigma = loadScoringStrategy.hrvSigma(lnSigmaHistory, sigmaPrior)
            val rhrSigma =
                windows.rhrHistory
                    .takeIf { it.size > 1 }
                    ?.stdev()
                    ?.takeIf { it > 0f }

            summary.copy(
                hrvMuMssd = hrvMu,
                hrvSigmaMssd = hrvSigma,
                rhrBpm = windows.rhrBpm,
                rhrSigma = rhrSigma,
                hrMax = hrMax,
                snapshotProfile = profile.name,
                rasScalingFactor = rasScalingFactor,
                hrvSigmaPrior = sigmaPrior,
                baselineCalculatedAtDate = summary.date,
                baselineObservationCount = eligibleDayCounts[summary.date],
            )
        }
    }

}
