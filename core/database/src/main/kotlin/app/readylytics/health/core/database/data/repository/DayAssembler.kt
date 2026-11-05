package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.database.data.repository.recommendation.MorningRecommendationAssembler
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.di.DefaultDispatcher
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.DailyRasValues
import app.readylytics.health.core.model.domain.repository.WalkForwardContexts
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.scoring.DayAssembly
import app.readylytics.health.core.model.domain.scoring.DayAssemblyUnavailableReason
import app.readylytics.health.core.model.domain.scoring.summaryOrNull
import app.readylytics.health.core.model.domain.sync.ScoringRunContext
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.EverydayHrLoadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles one scoring day (context → TRIMP → RAS → final summary → morning recommendation) for
 * [ScoringRepositoryImpl], without persisting anything. The caller owns persistence and decides
 * when [ComputedDay.commitWalkForwardContexts] may run.
 */
@Singleton
class DayAssembler
    @Inject
    constructor(
        private val readinessSummaryCoordinator: ReadinessSummaryCoordinator,
        private val dataLoader: ScoringDayDataLoader,
        private val dailyTrimpComputer: DailyTrimpComputer,
        private val rasTotalsComputer: RasTotalsComputer,
        private val finalSummaryAssembler: FinalSummaryAssembler,
        private val morningRecommendationAssembler: MorningRecommendationAssembler,
        private val scoringDayContextResolver: ScoringDayContextResolver,
        @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    ) {
        suspend fun computeDay(
            targetDate: LocalDate,
            prefs: UserPreferences,
            contexts: WalkForwardContexts,
            runContext: ScoringRunContext,
        ): ComputedDay =
            withContext(defaultDispatcher) {
                val context =
                    scoringDayContextResolver.resolveScoringDayContext(
                        targetDate,
                        prefs,
                        contexts.baseline,
                        runContext,
                    )
                logD("ScoringRepository") { "RAS CALC START [$targetDate]" }
                val processed = dailyTrimpComputer.processWorkouts(context)
                contexts.fatigue?.registerCanonicalImpulses(processed.fatigueInputs)
                val dailyTrimpRaw = processed.dailyTrimpRaw
                if (dailyTrimpRaw == null) {
                    ComputedDay(
                        assembly = DayAssembly.Unavailable(DayAssemblyUnavailableReason.WORKOUT_LOAD_UNAVAILABLE),
                        workouts = processed.workouts,
                        workoutUpdates = processed.workoutModelTrimpUpdates,
                        commitWalkForwardContexts = {},
                    )
                } else {
                    assemble(context, contexts, processed, dailyTrimpRaw)
                }
            }

        private suspend fun assemble(
            context: ScoringDayContext,
            contexts: WalkForwardContexts,
            processed: DailyTrimpComputer.ProcessedWorkoutDay,
            dailyTrimpRaw: Float,
        ): ComputedDay {
            val aggregatedSleep =
                readinessSummaryCoordinator.resolveSleepAggregation(
                    context.targetDate,
                    context.zoneId,
                    context.prefs,
                )
            val session =
                aggregatedSleep?.scoringSession
                    ?: dataLoader.loadSessionEndingInRange(context.dayMidnightMs, context.nextDayMidnightMs)
            val currentSessionIds = aggregatedSleep?.coreSessionIds ?: session?.let { setOf(it.id) }.orEmpty()
            val everydayResult =
                dailyTrimpComputer.resolveEverydayTrimp(context, processed, session, aggregatedSleep)
            val scalingFactor =
                context.initialBaselines.frozenRasScalingFactor ?: context.scoringConfig.rasScalingFactor
            val rasTotals =
                rasTotalsComputer.compute(
                    dailyTrimpRaw = dailyTrimpRaw,
                    trimpEverydayHr = everydayResult.totalEverydayTrimp,
                    scalingFactor = scalingFactor,
                    targetDate = context.targetDate,
                    zoneId = context.zoneId,
                    rasWindow = contexts.ras,
                )
            val inputs =
                contexts.buildFinalSummaryInputs(
                    context = context,
                    session = session,
                    currentSessionIds = currentSessionIds,
                    processed = processed,
                    everydayResult = everydayResult,
                    aggregatedSleep = aggregatedSleep,
                    rasTotals = rasTotals,
                )
            val assembly =
                finalizeAssembly(
                    assembly = finalSummaryAssembler.assemble(inputs),
                    inputs = inputs,
                    morningRecommendationAssembler = morningRecommendationAssembler,
                )
            return ComputedDay(
                assembly = assembly,
                workouts = processed.workouts,
                workoutUpdates = processed.workoutModelTrimpUpdates,
                commitWalkForwardContexts =
                    createCommitAction(
                        contexts = contexts,
                        context = context,
                        everydayResult = everydayResult,
                        dailyTrimpRaw = dailyTrimpRaw,
                        hasWorkouts = processed.workouts.isNotEmpty(),
                        assembly = assembly,
                    ),
            )
        }

        private fun createCommitAction(
            contexts: WalkForwardContexts,
            context: ScoringDayContext,
            everydayResult: EverydayHrLoadResult,
            dailyTrimpRaw: Float,
            hasWorkouts: Boolean,
            assembly: DayAssembly,
        ): () -> Unit = {
            dailyTrimpComputer.publishTrimpToContext(
                contexts.trimp,
                context.targetDate,
                everydayResult.totalEverydayTrimp,
                dailyTrimpRaw,
                hasWorkouts,
            )
            contexts.fatigue?.let { fatigueContext ->
                fatigueContext.morningCursor.lastCandidate?.let {
                    fatigueContext.morningCursor.commit(it)
                }
                fatigueContext.dayEndCursor.lastCandidate?.let {
                    fatigueContext.dayEndCursor.commit(it)
                }
            }
            assembly.summaryOrNull()?.let { summary ->
                contexts.ras?.commit(
                    DailyRasValues(
                        date = context.targetDate,
                        rasWorkoutOnly = summary.rasWorkoutOnly,
                        rasEverydayHr = summary.rasEverydayHr,
                    ),
                )
            }
        }
    }

data class ComputedDay(
    val assembly: DayAssembly,
    val workouts: List<WorkoutRecordEntity>,
    val workoutUpdates: List<ComputeDailyTrimpUseCase.WorkoutModelTrimpUpdate>,
    /**
     * Applies this day's mutations to the shared walk-forward contexts (`contexts.trimp`).
     * Callers must only invoke this AFTER a successful persist (or, for the non-persisting
     * `computeDailySummary` path, after confirming a genuine candidate) -- never speculatively.
     */
    val commitWalkForwardContexts: () -> Unit,
)

/**
 * C3 (WP-13): applies telemetry and the morning recommendation on top of [assembly] only when it
 * carries a genuine candidate ([DayAssembly.Computed]/[DayAssembly.Absent]); [DayAssembly.Unavailable]
 * passes through untouched. A thrown failure while applying the recommendation converts to
 * [DayAssembly.Unavailable] with its own reason code, same as every other assembler boundary --
 * `assemble(...)` returning `null` (see [applyRecommendation]) is a distinct, legitimate "keep the
 * previous guidance" outcome, not a failure.
 */
private suspend fun finalizeAssembly(
    assembly: DayAssembly,
    inputs: FinalSummaryAssembler.Inputs,
    morningRecommendationAssembler: MorningRecommendationAssembler,
): DayAssembly {
    val summary = assembly.summaryOrNull()
    if (summary != null) {
        ScoringTelemetry.logTelemetry(
            inputs.context.scoringConfig,
            inputs.context.prefs,
            inputs.rasTotals.last6DaysRasWorkoutOnly,
            summary.totalRasWorkoutOnly,
        )
    }
    val summaryWithRecommendation =
        summary?.let { applyRecommendationOrNull(it, inputs, morningRecommendationAssembler) }

    // Single terminal return keeps this within detekt's ReturnCount limit.
    return when {
        summary == null -> assembly
        summaryWithRecommendation == null ->
            DayAssembly.Unavailable(DayAssemblyUnavailableReason.RECOMMENDATION_ASSEMBLY_FAILED)
        assembly is DayAssembly.Absent -> DayAssembly.Absent(summaryWithRecommendation)
        else -> DayAssembly.Computed(summaryWithRecommendation)
    }
}

private suspend fun applyRecommendationOrNull(
    summary: DailySummary,
    inputs: FinalSummaryAssembler.Inputs,
    morningRecommendationAssembler: MorningRecommendationAssembler,
): DailySummary? =
    try {
        morningRecommendationAssembler.applyRecommendation(
            context = inputs.context,
            finalSummary = summary,
            fatigueContext = inputs.fatigueContext,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logE("ScoringRepository", e) { "Recommendation assembly failed for ${inputs.context.targetDate}" }
        null
    }

/**
 * Anchored to the wake time, entirely independent of the TRIMP/readiness pipeline that produced
 * [finalSummary]. Never recurses back into `computeDailySummary`.
 *
 * A day whose recovery inputs could not be computed yields a `null` assembly. That must not abort
 * the day — the readiness pipeline has already produced a complete [finalSummary] and the day still
 * scores and persists — and it must not *erase* guidance that a previous run already computed for
 * this day, so the stored snapshot is preserved (the same "no fresh value means keep the stored one"
 * rule `withStepCount` applies to step counts). A day that has never had one simply stays `null`.
 */
private suspend fun MorningRecommendationAssembler.applyRecommendation(
    context: ScoringDayContext,
    finalSummary: DailySummary,
    fatigueContext: WalkForwardFatigueContext? = null,
): DailySummary {
    val previous = context.dailySummary?.workoutRecommendation
    val recommendation =
        when (val fresh = assemble(context, previous = previous, fatigueContext = fatigueContext)) {
            // Unavailable this pass (recovery inputs couldn't be computed): preserve whatever
            // guidance a previous run already computed for this day, rather than erasing it.
            null -> previous
            // Computed: a fresh snapshot, which may itself explicitly encode "no sleep this
            // morning" -- see MorningRecommendationAssembler.noSleepInput.
            else -> fresh
        }
    return finalSummary.copy(workoutRecommendation = recommendation)
}

private fun WalkForwardContexts.buildFinalSummaryInputs(
    context: ScoringDayContext,
    session: SleepSessionEntity?,
    currentSessionIds: Set<String>,
    processed: DailyTrimpComputer.ProcessedWorkoutDay,
    everydayResult: EverydayHrLoadResult,
    aggregatedSleep: SleepAggregationContext?,
    rasTotals: RasTotalsComputer.RasTotals,
): FinalSummaryAssembler.Inputs =
    FinalSummaryAssembler.Inputs(
        context = context,
        session = session,
        currentSessionIds = currentSessionIds,
        dailyTrimpRaw = processed.dailyTrimpRaw!!,
        trimpEverydayHr = everydayResult.totalEverydayTrimp,
        rasTotals = rasTotals,
        everydayResult = everydayResult,
        aggregatedSleep = aggregatedSleep,
        trimpContext = trimp,
        baselineContext = baseline,
        fatigueContext = fatigue,
        vo2MaxContext = vo2Max,
        stagedFatigueInputs = processed.fatigueInputs,
        stagedWorkouts = processed.workouts,
    )
