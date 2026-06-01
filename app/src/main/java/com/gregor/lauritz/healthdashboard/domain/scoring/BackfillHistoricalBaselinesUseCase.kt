package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao

class BackfillHistoricalBaselinesUseCase(
    private val dailySummaryDao: DailySummaryDao,
    private val computeHistoricalBaselines: ComputeHistoricalBaselinesUseCase,
) {
    suspend fun execute(): Int {
        val allDailySummaries = dailySummaryDao.getAllSummaries()
        val backfilledSummaries =
            computeHistoricalBaselines.computeHistoricalBaselines(
                allDailySummaries,
            )

        // Column-scoped updates: prevent dual-writer race by only updating baseline columns
        var count = 0
        for (summary in backfilledSummaries) {
            dailySummaryDao.updateBaselines(
                dateMidnightMs = summary.dateMidnightMs,
                hrvMuMssd = summary.hrvMuMssd,
                hrvSigmaMssd = summary.hrvSigmaMssd,
                rhrBpm = summary.rhrBpm,
                baselineCalculatedAtDate = summary.baselineCalculatedAtDate,
                baselineVersion = summary.baselineVersion,
            )
            count++
        }

        return count
    }
}
