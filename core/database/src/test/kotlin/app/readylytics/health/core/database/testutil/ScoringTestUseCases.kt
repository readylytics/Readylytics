package app.readylytics.health.core.database.testutil

import app.readylytics.health.core.scoring.domain.cardio.MaterkoAdaptedVo2MaxCalculator
import app.readylytics.health.core.scoring.domain.cardio.UthVo2MaxCalculator
import app.readylytics.health.core.scoring.domain.cardio.Vo2MaxSourceResolver
import app.readylytics.health.core.scoring.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeTrainingReadinessUseCase
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase

/** Per-day use cases for [createScoringRepositoryForTest]; stateless ones default to production instances. */
internal data class ScoringTestUseCases(
    val computeDailyTrimp: ComputeDailyTrimpUseCase,
    val resolveDailyBaselines: ResolveDailyBaselinesUseCase,
    val computeTrainingReadiness: ComputeTrainingReadinessUseCase,
    val computeResidualFatigue: ComputeResidualFatigueUseCase = ComputeResidualFatigueUseCase(),
    val assembleEverydayLoadInput: AssembleEverydayLoadInputUseCase = AssembleEverydayLoadInputUseCase(),
    val uthVo2MaxCalculator: UthVo2MaxCalculator = UthVo2MaxCalculator(),
    val vo2MaxSourceResolver: Vo2MaxSourceResolver = Vo2MaxSourceResolver(),
    val materkoAdaptedVo2MaxCalculator: MaterkoAdaptedVo2MaxCalculator = MaterkoAdaptedVo2MaxCalculator(),
)
