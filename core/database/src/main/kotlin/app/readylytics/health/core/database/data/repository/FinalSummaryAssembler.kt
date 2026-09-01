package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.EverydayHrLoadResult

/**
 * Assembles the final per-day [DailySummary] from the raw scoring inputs, mirroring
 * [BaseSummaryAssembler]/[CalibrationGate]: the repository orchestrates, this class owns the
 * calibrated/uncalibrated finalization plus the shadow-mode residual-fatigue snapshot.
 */
class FinalSummaryAssembler(
    private val baseSummaryAssembler: BaseSummaryAssembler,
    private val calibrationGate: CalibrationGate,
    private val baselineComputer: BaselineComputer,
    private val bodyMetricsDataLoader: BodyMetricsDataLoader,
    private val readinessSummaryCoordinator: ReadinessSummaryCoordinator,
    private val residualFatigueComputer: ResidualFatigueComputer,
) {
    data class Inputs(
        val context: ScoringDayContext,
        val session: SleepSessionEntity?,
        val currentSessionIds: Set<String>,
        val dailyTrimpRaw: Float,
        val trimpEverydayHr: Float,
        val rasTotals: RasTotalsComputer.RasTotals,
        val everydayResult: EverydayHrLoadResult,
        val aggregatedSleep: SleepAggregationContext?,
        val trimpContext: WalkForwardTrimpContext?,
        val baselineContext: WalkForwardBaselineContext?,
        val fatigueContext: WalkForwardFatigueContext?,
    )

    suspend fun assemble(inputs: Inputs): DailySummary {
        val baseSummary =
            baseSummaryAssembler.buildBaseSummary(
                inputs.context,
                inputs.dailyTrimpRaw,
                inputs.trimpEverydayHr,
                inputs.rasTotals,
                inputs.everydayResult,
                inputs.aggregatedSleep,
            )
        val isCalibrated =
            calibrationGate.isCalibrated(
                inputs.context,
                inputs.baselineContext?.sessions,
                inputs.session != null,
            )
        val base =
            ReadinessBaseInputs(
                session = inputs.session,
                currentSessionIds = inputs.currentSessionIds,
                baseSummary = baseSummary,
                avgSpo2 = bodyMetricsDataLoader.loadAvgSpo2(inputs.session),
                avgBodyTemp = bodyMetricsDataLoader.loadAvgBodyTemp(inputs.session),
            )
        val summary = resolveScoredSummary(base, inputs, isCalibrated)
        return summary.copy(
            residualFatigue = residualFatigueComputer.compute(inputs.context, inputs.fatigueContext),
        )
    }

    private suspend fun resolveScoredSummary(
        base: ReadinessBaseInputs,
        inputs: Inputs,
        isCalibrated: Boolean,
    ): DailySummary =
        if (!isCalibrated) {
            val calibHrvBaseline =
                baselineComputer.computeHrvBaselineBetween(
                    fromMs = inputs.context.dayMidnightMs,
                    toMs = inputs.context.nextDayMidnightMs,
                    hrvBaselineOverride = inputs.context.prefs.hrvBaselineOverride,
                    zoneId = inputs.context.zoneId,
                    sleepDayPolicy = inputs.context.sleepDayPolicy,
                    prefetchedSessions = inputs.baselineContext?.sessions,
                )
            readinessSummaryCoordinator.computeUncalibratedSummary(
                base = base,
                calibHrvBaseline = calibHrvBaseline,
                rhrBaselineValue = inputs.context.initialBaselines.rhrBaselineValue,
                prefs = inputs.context.prefs,
            )
        } else {
            readinessSummaryCoordinator.computeCalibratedSummary(
                base = base,
                context =
                    CalibratedScoringContext(
                        targetDate = inputs.context.targetDate,
                        zoneId = inputs.context.zoneId,
                        nextDayMidnightMs = inputs.context.nextDayMidnightMs,
                        dailyTrimpRaw = inputs.dailyTrimpRaw,
                        trimpEverydayHr = inputs.trimpEverydayHr,
                        initialBaselines = inputs.context.initialBaselines,
                        scoringConfig = inputs.context.scoringConfig,
                        prefs = inputs.context.prefs,
                        sleepDayPolicy = inputs.context.sleepDayPolicy,
                        trimpContext = inputs.trimpContext,
                        baselineContext = inputs.baselineContext,
                    ),
            )
        }
}
