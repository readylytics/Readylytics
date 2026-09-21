package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.scoring.domain.scoring.EverydayHrLoadResult
import app.readylytics.health.core.scoring.domain.scoring.freshDaySummary

class BaseSummaryAssembler(
    private val bodyMetricsDataLoader: BodyMetricsDataLoader,
) {
    suspend fun buildBaseSummary(
        context: ScoringDayContext,
        dailyTrimpRaw: Float,
        trimpEverydayHr: Float,
        rasTotals: RasTotalsComputer.RasTotals,
        everydayResult: EverydayHrLoadResult,
        aggregatedSleep: SleepAggregationContext?,
    ): DailySummary {
        val latest = bodyMetricsDataLoader.loadLatestBodyMetrics(context.nextDayMidnightMs)

        // C3 (WP-13): start from a genuinely fresh candidate, not the raw previous row. Using
        // context.dailySummary directly here would carry forward every stale derived field
        // (sleepScore, readinessResult, workoutRecommendation, ...) into this generation unless a
        // later stage happens to overwrite it -- which is exactly how a deleted sleep session's
        // score used to ghost through. freshDaySummary carries forward only the frozen
        // baseline/calibration snapshot fields (already null on the Room row when a mutation
        // invalidated them, see RoomHealthIngestionStore.clearFrozenBaselines) plus stepCount.
        return freshDaySummary(context.targetDate, context.dailySummary).copy(
            trimpWorkoutOnly = dailyTrimpRaw,
            trimpEverydayHr = trimpEverydayHr,
            rasWorkoutOnly = rasTotals.dailyRas,
            rasEverydayHr = rasTotals.dailyRasEverydayHr,
            totalRasWorkoutOnly = rasTotals.totalRasWorkoutOnly,
            totalRasEverydayHr = rasTotals.totalRasEverydayHr,
            everydayCoverageMinutes = everydayResult.coverageMinutes,
            everydayLoadConfidence = everydayResult.confidence.name,
            weightKg = latest.weightKg,
            bodyFatPercent = latest.bodyFatPercent,
            bloodPressureSystolic = latest.bloodPressureSystolic,
            bloodPressureDiastolic = latest.bloodPressureDiastolic,
            supplementalSleepDurationMinutes = aggregatedSleep?.aggregate?.supplementalSleepDurationMinutes,
            napCount = aggregatedSleep?.aggregate?.supplementalBlocks?.size,
        )
    }
}
