package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import kotlinx.coroutines.flow.first

class BackfillHistoricalBaselinesUseCase(
    private val dailySummaryDao: DailySummaryDao,
    private val settingsRepository: SettingsRepository,
    private val computeHistoricalBaselines: ComputeHistoricalBaselinesUseCase,
) {
    suspend fun execute(): Int {
        val prefs = settingsRepository.userPreferences.first()
        val allDailySummaries = dailySummaryDao.getAllSummaries()
        val backfilledSummaries =
            computeHistoricalBaselines.computeHistoricalBaselines(allDailySummaries, prefs)

        var count = 0
        for (summary in backfilledSummaries) {
            dailySummaryDao.updateBaselines(
                dateMidnightMs = summary.dateMidnightMs,
                hrvMuMssd = summary.hrvMuMssd,
                hrvSigmaMssd = summary.hrvSigmaMssd,
                rhrBpm = summary.rhrBpm,
                baselineCalculatedAtDate = summary.baselineCalculatedAtDate,
                baselineVersion = summary.baselineVersion,
                hrMax = summary.hrMax,
                snapshotProfile = summary.snapshotProfile,
                hrvSigmaPrior = summary.hrvSigmaPrior,
                paiScalingFactor = summary.paiScalingFactor,
                baselineObservationCount = summary.baselineObservationCount,
            )
            count++
        }

        return count
    }
}
