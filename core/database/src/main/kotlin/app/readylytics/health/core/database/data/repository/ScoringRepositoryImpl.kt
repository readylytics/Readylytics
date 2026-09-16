package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.data.preferences.scoringZone
import app.readylytics.health.core.model.di.DefaultDispatcher
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.ReadinessResult
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.repository.ScoringRepository
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.model.domain.repository.WalkForwardContexts
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.model.domain.repository.WalkForwardVo2MaxContext
import app.readylytics.health.core.model.domain.scoring.DayAssembly
import app.readylytics.health.core.model.domain.scoring.DayAssemblyUnavailableReason
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.model.domain.scoring.summaryOrNull
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.database.data.repository.recommendation.MorningRecommendationAssembler
import app.readylytics.health.core.database.data.repository.recommendation.MorningRecoveryLoader
import app.readylytics.health.core.database.data.repository.recommendation.WorkoutExampleLoader
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.EverydayHrLoadResult
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory
import app.readylytics.health.core.scoring.domain.scoring.TrimpDateBucketer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap
import javax.inject.Inject
import javax.inject.Singleton

/** Trailing lookback for wearable VO2 Max readings, mirroring [FinalSummaryAssembler]'s per-day window. */
private const val VO2_MAX_LOOKBACK_DAYS = 30L

@Singleton
class ScoringRepositoryImpl
    @Inject
    constructor(
        private val loaders: ScoringDataLoaders,
        private val settingsRepo: SettingsRepository,
        private val baselineComputer: BaselineComputer,
        private val scoringConfigFactory: ScoringConfigFactory,
        private val useCases: ScoringDayUseCases,
        private val scoringHistoryRepository: ScoringHistoryRepository,
        private val readinessSummaryCoordinator: ReadinessSummaryCoordinator,
        @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
        private val recommendationDependencies: MorningRecommendationDependencies,
    ) : ScoringRepository {
        private val calculationMutex = Mutex()

        private val dataLoader = loaders.day
        private val bodyMetricsDataLoader = loaders.bodyMetrics
        private val seriesLoader = loaders.series
        private val heartRateDataLoader = loaders.heartRate

        private val scoringDayContextResolver =
            ScoringDayContextResolver(
                scoringConfigFactory,
                useCases.resolveDailyBaselines,
                scoringHistoryRepository,
            )
        private val dailyTrimpComputer =
            DailyTrimpComputer(
                dataLoader,
                heartRateDataLoader,
                useCases.computeDailyTrimp,
                useCases.assembleEverydayLoadInput,
            )
        private val baseSummaryAssembler = BaseSummaryAssembler(bodyMetricsDataLoader)
        private val calibrationGate = CalibrationGate(scoringHistoryRepository)
        private val rasTotalsComputer = RasTotalsComputer(seriesLoader)
        private val residualFatigueComputer =
            ResidualFatigueComputer(dataLoader, useCases.computeResidualFatigue)
        private val finalSummaryAssembler =
            FinalSummaryAssembler(
                baseSummaryAssembler,
                calibrationGate,
                baselineComputer,
                bodyMetricsDataLoader,
                readinessSummaryCoordinator,
                residualFatigueComputer,
                useCases.computeTrainingReadiness,
                Vo2MaxScoringDependencies(
                    useCases.uthVo2MaxCalculator,
                    useCases.materkoAdaptedVo2MaxCalculator,
                    useCases.vo2MaxSourceResolver,
                ),
            )

        // Reuses this repository's own `residualFatigueComputer`/`calibrationGate` rather than
        // separate instances: both are stateless aside from the caller-passed walk-forward context
        // (see `ResidualFatigueComputer`), so sharing them is equivalent to Hilt providing fresh ones.
        private val morningRecommendationAssembler =
            MorningRecommendationAssembler(
                recommendationDependencies.sleepSessionRepository,
                MorningRecoveryLoader(
                    recommendationDependencies.sleepSessionRepository,
                    recommendationDependencies.computeSleepMetricsUseCase,
                    recommendationDependencies.hrvResolver,
                    residualFatigueComputer,
                    scoringConfigFactory,
                    baselineComputer,
                    calibrationGate,
                ),
                WorkoutExampleLoader(
                    recommendationDependencies.workoutRepository,
                    recommendationDependencies.dailySummaryRepository,
                    recommendationDependencies.getWorkoutDisplayMetricsUseCase,
                ),
            )

        private val dayAssembler =
            DayAssembler(
                readinessSummaryCoordinator,
                dataLoader,
                dailyTrimpComputer,
                rasTotalsComputer,
                finalSummaryAssembler,
                morningRecommendationAssembler,
            )

        override suspend fun computeAndPersistDailySummary(
            targetDate: LocalDate,
            steps: Long?,
            prefs: UserPreferences?,
            contexts: WalkForwardContexts,
        ) = calculationMutex.withLock {
            val resolvedPrefs = prefs ?: settingsRepo.userPreferences.first()
            val zoneId = resolvedPrefs.scoringZone()
            val computed = computeDay(targetDate, resolvedPrefs, contexts)
            val assembly = computed.assembly.withStepCount(steps)
            val persisted =
                dataLoader.persistDayAssembly(assembly, zoneId, computed.workouts, computed.workoutUpdates)
            if (!persisted) {
                // C3 (WP-13): Unavailable is a deliberate no-op -- nothing was written, so the
                // walk-forward context below must not be committed either (see
                // ComputedDay.commitWalkForwardContexts).
                throw DayAssemblyUnavailableException(assembly.unavailableReasonOrDefault())
            }
            // Walk-forward context mutations are only ever applied after the write above commits
            // -- never speculatively before or during it.
            computed.commitWalkForwardContexts()
        }

        override suspend fun fetchWalkForwardTrimpContext(
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
        ): WalkForwardTrimpContext {
            val fromMs =
                startDate.minusDays(ScoringConstants.CHRONIC_DAYS * 2)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val toMs = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            return WalkForwardTrimpContext(
                dailyTrimpByDate =
                    TreeMap(
                        TrimpDateBucketer.bucket(
                            seriesLoader.loadWorkoutTrimpPoints(fromMs, toMs),
                            zoneId,
                        ),
                    ),
                everydayTrimpByDate =
                    TreeMap(
                        TrimpDateBucketer.bucket(
                            seriesLoader.loadEverydayTrimpPoints(fromMs, toMs),
                            zoneId,
                        ),
                    ),
            )
        }

        override suspend fun fetchWalkForwardBaselineContext(
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
        ): WalkForwardBaselineContext =
            WalkForwardBaselineContext(baselineComputer.prefetchWalkForwardSessions(startDate, endDate, zoneId))

        override suspend fun fetchWalkForwardFatigueContext(
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
        ): WalkForwardFatigueContext =
            residualFatigueComputer.fetchWalkForwardContext(
                startDate = startDate,
                zoneId = zoneId,
                prefs = settingsRepo.userPreferences.first(),
            )

        override suspend fun fetchWalkForwardVo2MaxContext(
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
        ): WalkForwardVo2MaxContext {
            val fromMs =
                startDate.minusDays(VO2_MAX_LOOKBACK_DAYS)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val toMs = endDate.plusDays(2).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val vo2MaxByTimestampMs = TreeMap<Long, Float>()
            bodyMetricsDataLoader.loadVo2MaxRange(fromMs, toMs).forEach {
                vo2MaxByTimestampMs[it.timestampMs] = it.vo2Max
            }
            return WalkForwardVo2MaxContext(vo2MaxByTimestampMs)
        }

        override suspend fun computeDailySummary(targetDate: LocalDate): DailySummary {
            val prefs = settingsRepo.userPreferences.first()
            return calculationMutex.withLock {
                val computed = computeDay(targetDate, prefs, WalkForwardContexts())
                val summary =
                    computed.assembly.summaryOrNull()
                        ?: throw DayAssemblyUnavailableException(computed.assembly.unavailableReasonOrDefault())
                computed.commitWalkForwardContexts()
                summary
            }
        }

        // Deliberately does not take calculationMutex, unlike its siblings: holding it would block
        // the dashboard card for the whole duration of a full historical resync. This path is
        // read-only and non-persisting, so the worst case is reading mid-resync state, which the
        // next minute's recomputation corrects.
        override suspend fun computeCurrentResidualFatigue(nowMs: Long): Float? =
            withContext(defaultDispatcher) {
                residualFatigueComputer.computeLive(nowMs, settingsRepo.userPreferences.first())
            }

        override suspend fun persist(summary: DailySummary) {
            dataLoader.persistDailySummary(summary, settingsRepo.userPreferences.first().scoringZone())
        }

        override suspend fun toReadinessResult(summary: DailySummary): ReadinessResult = summary.readinessResult

        private suspend fun computeDay(
            targetDate: LocalDate,
            prefs: UserPreferences,
            contexts: WalkForwardContexts,
        ): ComputedDay =
            withContext(defaultDispatcher) {
                val context =
                    scoringDayContextResolver.resolveScoringDayContext(targetDate, prefs, contexts.baseline)
                logD("ScoringRepository") { "RAS CALC START [$targetDate]" }
                val processed = dailyTrimpComputer.processWorkouts(context)
                // NOTE: registerCanonicalImpulses cannot be deferred to after a successful commit
                // like publishTrimpToContext below -- ResidualFatigueComputer.compute's walk-forward
                // path (advanceAccumulator) consumes THIS day's own impulses out of contexts.fatigue
                // to compute THIS day's residual-fatigue value, so the mutation must happen before
                // assembly runs. A failed/Unavailable assembly on this day therefore still leaves the
                // fatigue accumulator advanced; recovering that would require reworking
                // WalkForwardFatigueContext's API, out of scope for this task (see task report).
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
                    dayAssembler.assemble(context, contexts, processed, dailyTrimpRaw)
                }
            }
    }

private class DayAssembler(
    private val readinessSummaryCoordinator: ReadinessSummaryCoordinator,
    private val dataLoader: ScoringDayDataLoader,
    private val dailyTrimpComputer: DailyTrimpComputer,
    private val rasTotalsComputer: RasTotalsComputer,
    private val finalSummaryAssembler: FinalSummaryAssembler,
    private val morningRecommendationAssembler: MorningRecommendationAssembler,
) {
    suspend fun assemble(
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
                dailyTrimpRaw,
                everydayResult.totalEverydayTrimp,
                scalingFactor,
                context.targetDate,
                context.zoneId,
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
            // C3 (WP-13): unlike registerCanonicalImpulses above, publishTrimpToContext's
            // write into the shared contexts.trimp map is safely deferrable -- this day's OWN
            // computation above already read whatever trimp series it needed independently
            // (resolveTrimpSeries re-puts this day's own raw value into its local copy). The
            // caller invokes this only after a successful persist, so a failed/Unavailable day
            // never speculatively pollutes the shared walk-forward series for later days.
            commitWalkForwardContexts = {
                dailyTrimpComputer.publishTrimpToContext(
                    contexts.trimp,
                    context.targetDate,
                    everydayResult.totalEverydayTrimp,
                    dailyTrimpRaw,
                    processed.workouts.isNotEmpty(),
                )
            },
        )
    }
}

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
        morningRecommendationAssembler.applyRecommendation(inputs.context, summary)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logE("ScoringRepository", e) { "Recommendation assembly failed for ${inputs.context.targetDate}" }
        null
    }

private data class ComputedDay(
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

/** Thrown when a [DayAssembly.Unavailable] reaches a caller that requires a genuine candidate. */
internal class DayAssemblyUnavailableException(reason: String) :
    IllegalStateException("Day assembly unavailable: $reason")

private fun DayAssembly.unavailableReasonOrDefault(): String =
    (this as? DayAssembly.Unavailable)?.reason ?: DayAssemblyUnavailableReason.FINAL_ASSEMBLY_FAILED

private fun DayAssembly.withStepCount(steps: Long?): DayAssembly =
    when (this) {
        is DayAssembly.Computed -> copy(summary = summary.withStepCount(steps))
        is DayAssembly.Absent -> copy(summary = summary.withStepCount(steps))
        is DayAssembly.Unavailable -> this
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
): DailySummary {
    val previous = context.dailySummary?.workoutRecommendation
    val recommendation =
        when (val fresh = assemble(context, previous = previous)) {
            // Unavailable this pass (recovery inputs couldn't be computed): preserve whatever
            // guidance a previous run already computed for this day, rather than erasing it.
            null -> previous
            // Computed: a fresh snapshot, which may itself explicitly encode "no sleep this
            // morning" -- see MorningRecommendationAssembler.noSleepInput.
            else -> fresh
        }
    return finalSummary.copy(workoutRecommendation = recommendation)
}

/** A null [steps] means no fresh count for the day; the stored value is preserved. */
private fun DailySummary.withStepCount(steps: Long?): DailySummary =
    if (steps == null) {
        this
    } else {
        copy(stepCount = steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
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
