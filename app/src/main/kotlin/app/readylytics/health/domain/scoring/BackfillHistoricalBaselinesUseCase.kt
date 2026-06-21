package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.persistence.DailySummaryDao
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.repository.TransactionRunner
import kotlinx.coroutines.flow.first

class BackfillHistoricalBaselinesUseCase(
    private val dailySummaryDao: DailySummaryDao,
    private val settingsRepository: SettingsRepository,
    private val computeHistoricalBaselines: ComputeHistoricalBaselinesUseCase,
    private val transactionRunner: TransactionRunner,
) {
    suspend fun execute(): Int {
        dailySummaryDao.wipeDerivedBaselines()
        val prefs = settingsRepository.userPreferences.first()
        val allDailySummaries = dailySummaryDao.getAllSummaries()
        val backfilledSummaries =
            computeHistoricalBaselines.computeHistoricalBaselines(allDailySummaries, prefs)

        // Collapse the per-day UPDATEs (a classic N+1) into a single transaction so the whole
        // backfill incurs one disk commit instead of one per historical day. The loop only writes
        // a precomputed list — no interdependent reads — so transaction batching is safe here.
        transactionRunner.runInTransaction {
            for (summary in backfilledSummaries) {
                dailySummaryDao.updateBaselines(
                    dateMidnightMs = summary.dateMidnightMs,
                    hrvMuMssd = summary.hrvMuMssd,
                    hrvSigmaMssd = summary.hrvSigmaMssd,
                    rhrBpm = summary.rhrBpm,
                    rhrSigma = summary.rhrSigma,
                    baselineCalculatedAtDate = summary.baselineCalculatedAtDate,
                    hrMax = summary.hrMax,
                    snapshotProfile = summary.snapshotProfile,
                    hrvSigmaPrior = summary.hrvSigmaPrior,
                    rasScalingFactor = summary.rasScalingFactor,
                    baselineObservationCount = summary.baselineObservationCount,
                )
            }
        }

        return backfilledSummaries.size
    }
}
