package app.readylytics.health.domain.scoring

import app.readylytics.health.data.local.entity.DailySummaryEntity
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.domain.util.HeartRateFormulas
import app.readylytics.health.domain.util.stdev
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ln

class ComputeHistoricalBaselinesUseCase(
    private val baselineComputer: BaselineComputer,
    private val loadScoringStrategy: LoadScoringStrategy,
) {
    suspend fun computeHistoricalBaselines(
        allDailySummaries: List<DailySummaryEntity>,
        prefs: UserPreferences,
    ): List<DailySummaryEntity> {
        if (allDailySummaries.isEmpty()) return emptyList()

        val profile = prefs.physiologyProfile
        val hrMax = HeartRateFormulas.resolveMaxHeartRate(prefs)
        val paiScalingFactor = prefs.paiScalingFactor
        val sigmaPrior = profile.lnSigmaPrior

        // Batch all per-day baseline windows (HRV mu/sigma + RHR) in a fixed, small number of DB
        // reads for the entire history instead of ~11 queries per day (classic N+1). The batched
        // path reproduces the per-day [BaselineComputer] window/validity/nadir logic exactly, so
        // frozen baseline values are identical — see BaselineComputer.computeBackfillBaselines and
        // its equivalence test.
        val baselines =
            baselineComputer.computeBackfillBaselines(allDailySummaries, prefs.restingHrPercentile)

        return allDailySummaries.mapNotNull { summary ->
            val windows = baselines[summary.dateMidnightMs] ?: return@mapNotNull null
            if (windows.muHistory.isEmpty()) return@mapNotNull null

            val date = summary.dateMidnightMs.toLocalDate()
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
                paiScalingFactor = paiScalingFactor,
                hrvSigmaPrior = sigmaPrior,
                baselineCalculatedAtDate = date,
                baselineObservationCount = windows.muHistory.size,
            )
        }
    }

    private fun Long.toLocalDate(): LocalDate =
        Instant
            .ofEpochMilli(this)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
}
