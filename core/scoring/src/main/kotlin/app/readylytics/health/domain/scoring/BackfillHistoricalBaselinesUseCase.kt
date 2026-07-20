package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.persistence.DailySummaryDao
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.repository.TransactionRunner
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class BackfillHistoricalBaselinesUseCase
    @Inject
    constructor(
        private val dailySummaryDao: DailySummaryDao,
        private val settingsRepository: SettingsRepository,
        private val computeHistoricalBaselines: ComputeHistoricalBaselinesUseCase,
        private val transactionRunner: TransactionRunner,
    ) {
    /**
     * True freeze (OD-4): a day's baseline, once frozen, is never wiped or rewritten by this
     * backfill — only days that have never been computed (`baselineCalculatedAtDate == null`,
     * e.g. newly ingested history, or a day the batched computation couldn't produce a value for
     * yet) are candidates. This makes the backfill incremental: a second consecutive run with no
     * new unfrozen days is a 0-write no-op, and retention cleanup deleting old raw rows can no
     * longer silently change an already-frozen day's stored baseline.
     */
    suspend fun execute(): Int {
        val allDailySummaries = dailySummaryDao.getAllSummaries()
        val unfrozenSummaries = allDailySummaries.filter { it.baselineCalculatedAtDate == null }
        if (unfrozenSummaries.isEmpty()) return 0

        val prefs = settingsRepository.userPreferences.first()
        val backfilledSummaries =
            computeHistoricalBaselines.computeHistoricalBaselines(unfrozenSummaries, prefs)

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
