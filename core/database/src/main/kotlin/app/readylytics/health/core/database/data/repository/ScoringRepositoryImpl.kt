package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
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
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.EverydayHrLoadResult
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory
import app.readylytics.health.core.scoring.domain.scoring.TrimpDateBucketer
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
    ) : ScoringRepository {
        private val calculationMutex = Mutex()

        private val dataLoader = loaders.day
        private val bodyMetricsDataLoader = loaders.bodyMetrics
        private val seriesLoader = loaders.series

        private val scoringDayContextResolver =
            ScoringDayContextResolver(
                scoringConfigFactory,
                useCases.resolveDailyBaselines,
                scoringHistoryRepository,
            )
        private val dailyTrimpComputer =
            DailyTrimpComputer(dataLoader, useCases.computeDailyTrimp, useCases.assembleEverydayLoadInput)
        private val baseSummaryAssembler = BaseSummaryAssembler(bodyMetricsDataLoader)
        private val calibrationGate = CalibrationGate(baselineComputer)
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

        override suspend fun computeAndPersistDailySummary(
            targetDate: LocalDate,
            steps: Long?,
            prefs: UserPreferences?,
            contexts: WalkForwardContexts,
        ) = calculationMutex.withLock {
            val resolvedPrefs = prefs ?: settingsRepo.userPreferences.first()
            val zoneId = resolvedPrefs.scoringZone()
            val computed = computeDailySummary(targetDate, resolvedPrefs, contexts)
            dataLoader.persistDailySummary(computed.withStepCount(steps), zoneId)
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
            return calculationMutex.withLock { computeDailySummary(targetDate, prefs, WalkForwardContexts()) }
        }

        // Deliberately does not take calculationMutex, unlike its siblings: holding it would block
        // the dashboard card for the whole duration of a full historical resync. This path is
        // read-only and non-persisting, so the worst case is reading mid-resync state, which the
        // next minute's recomputation corrects.
        override suspend fun computeCurrentResidualFatigue(nowMs: Long): Float? =
            withContext(defaultDispatcher) {
                residualFatigueComputer.computeLive(nowMs, settingsRepo.userPreferences.first())
            }

        private suspend fun computeDailySummary(
            targetDate: LocalDate,
            prefs: UserPreferences,
            contexts: WalkForwardContexts,
        ): DailySummary =
            withContext(defaultDispatcher) {
                val context =
                    scoringDayContextResolver.resolveScoringDayContext(targetDate, prefs, contexts.baseline)
                logD("ScoringRepository") { "RAS CALC START [$targetDate]" }
                val processed = dailyTrimpComputer.processWorkouts(context)
                contexts.fatigue?.registerCanonicalImpulses(processed.fatigueInputs)
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
                dailyTrimpComputer.publishTrimpToContext(
                    contexts.trimp,
                    context.targetDate,
                    everydayResult.totalEverydayTrimp,
                    processed.dailyTrimpRaw,
                    processed.workouts.isNotEmpty(),
                )
                val scalingFactor =
                    context.initialBaselines.frozenRasScalingFactor ?: context.scoringConfig.rasScalingFactor
                val rasTotals =
                    rasTotalsComputer.compute(
                        processed.dailyTrimpRaw,
                        everydayResult.totalEverydayTrimp,
                        scalingFactor,
                        context.targetDate,
                        context.zoneId,
                    )
                val finalSummary =
                    finalSummaryAssembler.assemble(
                        contexts.buildFinalSummaryInputs(
                            context,
                            session,
                            currentSessionIds,
                            processed,
                            everydayResult,
                            aggregatedSleep,
                            rasTotals,
                        ),
                    )
                ScoringTelemetry.logTelemetry(
                    context.scoringConfig,
                    context.prefs,
                    rasTotals.last6DaysRasWorkoutOnly,
                    finalSummary.totalRasWorkoutOnly,
                )
                finalSummary
            }

        override suspend fun persist(summary: DailySummary) {
            dataLoader.persistDailySummary(summary, settingsRepo.userPreferences.first().scoringZone())
        }

        override suspend fun toReadinessResult(summary: DailySummary): ReadinessResult = summary.readinessResult
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
        dailyTrimpRaw = processed.dailyTrimpRaw,
        trimpEverydayHr = everydayResult.totalEverydayTrimp,
        rasTotals = rasTotals,
        everydayResult = everydayResult,
        aggregatedSleep = aggregatedSleep,
        trimpContext = trimp,
        baselineContext = baseline,
        fatigueContext = fatigue,
        vo2MaxContext = vo2Max,
    )
