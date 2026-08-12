package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.preferences.scoringZone
import app.readylytics.health.domain.scoring.sleep.SleepDayPolicy
import app.readylytics.health.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.domain.util.HeartRateFormulas
import app.readylytics.health.domain.util.stdev
import javax.inject.Inject
import kotlin.math.ln

class ComputeHistoricalBaselinesUseCase
    @Inject
    constructor(
        private val baselineComputer: BaselineComputer,
        private val loadScoringStrategy: LoadScoringStrategy,
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
                sleepDayPolicy = sleepDayPolicy,
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
                baselineObservationCount = windows.muHistory.size,
            )
        }
    }

}
