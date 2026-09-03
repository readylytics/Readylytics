package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.scoring.domain.cardio.UthVo2MaxCalculator
import app.readylytics.health.core.scoring.domain.cardio.Vo2MaxSourceResolver
import app.readylytics.health.core.scoring.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeTrainingReadinessUseCase
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The pure-Kotlin scoring use cases `ScoringRepositoryImpl` fans out to when scoring one day.
 *
 * Grouped into a parameter object so the repository's constructor stays inside the detekt
 * `LongParameterList` threshold: it already sat over the limit on a baseline entry, and injecting
 * [computeResidualFatigue] (previously hand-constructed, MEDIUM-3) would have pushed it further.
 * Every member is `@Inject`-constructed and stateless, so Hilt builds this holder for free.
 */
@Singleton
data class ScoringDayUseCases
    @Inject
    constructor(
        val computeDailyTrimp: ComputeDailyTrimpUseCase,
        val computeResidualFatigue: ComputeResidualFatigueUseCase,
        val resolveDailyBaselines: ResolveDailyBaselinesUseCase,
        val assembleEverydayLoadInput: AssembleEverydayLoadInputUseCase,
        val computeTrainingReadiness: ComputeTrainingReadinessUseCase,
        val uthVo2MaxCalculator: UthVo2MaxCalculator,
        val vo2MaxSourceResolver: Vo2MaxSourceResolver,
    )
