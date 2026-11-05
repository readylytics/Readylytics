package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayPolicy

/**
 * Resolves the HRV/RHR baseline window for one night: the persisted frozen snapshot when
 * [frozenBaseline] is set, otherwise the live history queried from [BaselineComputer].
 *
 * Extracted verbatim from `ComputeSleepMetricsUseCase` (structural move only).
 */
internal class SleepBaselineWindowResolver(
    private val baselineComputer: BaselineComputer,
) {
    suspend fun resolve(
        request: SleepMetricsRequest,
        frozenBaseline: Boolean,
        sleepDayPolicy: SleepDayPolicy,
    ): BaselineWindowResult {
        val summary = request.summary
        return if (frozenBaseline) {
            BaselineWindowResult(
                rhrValues = emptyList(),
                muHrvHistory = emptyList(),
                sigmaHrvHistory = emptyList(),
                validHistoricalSessionIds = emptyList(),
                validHistoricalDayCount = 0,
                frozenHrvMu = summary.hrvMuMssd,
                frozenHrvSigma = summary.hrvSigmaMssd,
                frozenRhr = summary.rhrBpm,
                frozenRhrSigma = summary.rhrSigma,
            )
        } else {
            val rhrValues =
                baselineComputer.rhrHistoryBetween(
                    fromMs = request.dayMidnight.toEpochMilli(),
                    toMs = request.dayEndMs,
                    percentile = request.prefs.restingHrPercentile,
                    zoneId = sleepDayPolicy.scoringZoneId,
                    sleepDayPolicy = sleepDayPolicy,
                )
            val hrvWindows =
                baselineComputer.computeHrvWindowsBetween(
                    fromMs = request.dayMidnight.toEpochMilli(),
                    toMs = request.dayEndMs,
                    zoneId = sleepDayPolicy.scoringZoneId,
                    excludeSessionIds = request.currentSessionIds,
                    sleepDayPolicy = sleepDayPolicy,
                    ignoreFrozenSnapshot = request.forceLiveBaselines,
                ) ?: BaselineComputer.HrvWindows(
                    muHistory = emptyList(),
                    sigmaHistory = emptyList(),
                    historicalSessions = emptyList(),
                    validHistoricalSessionIds = emptyList(),
                )
            BaselineWindowResult(
                rhrValues = rhrValues,
                muHrvHistory = hrvWindows.muHistory,
                sigmaHrvHistory = hrvWindows.sigmaHistory,
                validHistoricalSessionIds = hrvWindows.validHistoricalSessionIds,
                validHistoricalDayCount = hrvWindows.validHistoricalDayCount,
                frozenHrvMu = null,
                frozenHrvSigma = null,
                frozenRhr = null,
                frozenRhrSigma = null,
            )
        }
    }
}
