package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.scoring.domain.scoring.EverydayHrLoadResult

class BaseSummaryAssembler(
    private val dataLoader: ScoringDayDataLoader,
) {
    suspend fun buildBaseSummary(
        context: ScoringDayContext,
        dailyTrimpRaw: Float,
        trimpEverydayHr: Float,
        rasTotals: RasTotalsComputer.RasTotals,
        everydayResult: EverydayHrLoadResult,
        aggregatedSleep: SleepAggregationContext?,
    ): DailySummary {
        val latest = dataLoader.loadLatestBodyMetrics(context.nextDayMidnightMs)

        return (context.dailySummary ?: DailySummary(date = context.targetDate)).copy(
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
