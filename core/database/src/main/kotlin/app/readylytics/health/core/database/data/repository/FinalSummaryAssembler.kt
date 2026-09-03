package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.model.data.preferences.appliedTrainingReadinessConfig
import app.readylytics.health.core.scoring.domain.cardio.UthVo2MaxCalculator
import app.readylytics.health.core.scoring.domain.cardio.Vo2MaxResolution
import app.readylytics.health.core.scoring.domain.cardio.Vo2MaxSourceResolver
import app.readylytics.health.core.scoring.domain.scoring.ComputeTrainingReadinessUseCase
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.EverydayHrLoadResult
import app.readylytics.health.core.scoring.domain.scoring.TrainingReadinessProjection

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
    private val computeTrainingReadiness: ComputeTrainingReadinessUseCase,
    private val uthVo2MaxCalculator: UthVo2MaxCalculator,
    private val vo2MaxSourceResolver: Vo2MaxSourceResolver,
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
        val withFatigue = summary.copy(
            residualFatigue = residualFatigueComputer.compute(inputs.context, inputs.fatigueContext)
        )
        val (projectionForWorkout, projectionForEveryday) = resolveReadinessProjections(withFatigue, inputs)
        val vo2MaxResolution = resolveVo2Max(inputs, isCalibrated)
        return withFatigue.copy(
            acuteLoadRecovery = projectionForWorkout.acuteLoadRecovery,
            trainingLoadReadinessWorkoutOnly = projectionForWorkout.trainingLoadReadiness,
            trainingReadinessWorkoutOnly = projectionForWorkout.trainingReadiness,
            trainingLoadReadinessEverydayHr = projectionForEveryday.trainingLoadReadiness,
            trainingReadinessEverydayHr = projectionForEveryday.trainingReadiness,
            vo2Max = vo2MaxResolution.vo2Max,
            vo2MaxSource = vo2MaxResolution.source,
        )
    }

    private fun resolveReadinessProjections(
        withFatigue: DailySummary,
        inputs: Inputs,
    ): Pair<TrainingReadinessProjection, TrainingReadinessProjection> {
        val config = inputs.context.prefs.appliedTrainingReadinessConfig()
        val projectionForWorkout = computeTrainingReadiness.compute(
            restoration = withFatigue.sRest,
            sleepScore = withFatigue.sleepScore,
            loadScore = withFatigue.loadScoreWorkoutOnly,
            legacyReadiness = withFatigue.readinessWorkoutOnly,
            residualFatigue = withFatigue.residualFatigue,
            recoveryFlags = withFatigue.recoveryFlags,
            config = config,
        )
        val projectionForEveryday = computeTrainingReadiness.compute(
            restoration = withFatigue.sRest,
            sleepScore = withFatigue.sleepScore,
            loadScore = withFatigue.loadScoreEverydayHr,
            legacyReadiness = withFatigue.readinessEverydayHr,
            residualFatigue = withFatigue.residualFatigue,
            recoveryFlags = withFatigue.recoveryFlags,
            config = config,
        )
        require(projectionForWorkout.acuteLoadRecovery == projectionForEveryday.acuteLoadRecovery) {
            "Acute load recovery must match between load variants"
        }
        return projectionForWorkout to projectionForEveryday
    }

    private suspend fun resolveVo2Max(inputs: Inputs, isCalibrated: Boolean): Vo2MaxResolution {
        val uthEstimate =
            uthVo2MaxCalculator.estimate(
                hrMax = inputs.context.initialBaselines.hrMax,
                rhrBaselineBpm = inputs.context.initialBaselines.rhrBaselineValue,
                isCalibrating = !isCalibrated,
            )
        val wearableVo2Max = bodyMetricsDataLoader.loadLatestVo2Max(inputs.context.nextDayMidnightMs)?.vo2Max
        return vo2MaxSourceResolver.resolve(
            mode = inputs.context.prefs.vo2MaxSourceMode,
            wearableVo2Max = wearableVo2Max,
            uthEstimatedVo2Max = uthEstimate,
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
