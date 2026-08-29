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
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.scoring.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import app.readylytics.health.core.scoring.domain.scoring.EverydayHrLoadResult
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
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

@Singleton
class ScoringRepositoryImpl
    @Inject
    constructor(
        private val dataLoader: ScoringDayDataLoader,
        private val bodyMetricsDataLoader: BodyMetricsDataLoader,
        private val seriesLoader: ScoringSeriesLoader,
        private val settingsRepo: SettingsRepository,
        private val baselineComputer: BaselineComputer,
        private val scoringConfigFactory: ScoringConfigFactory,
        private val computeDailyTrimpUseCase: ComputeDailyTrimpUseCase,
        private val resolveDailyBaselinesUseCase: ResolveDailyBaselinesUseCase,
        private val assembleEverydayLoadInputUseCase: AssembleEverydayLoadInputUseCase,
        private val scoringHistoryRepository: ScoringHistoryRepository,
        private val readinessSummaryCoordinator: ReadinessSummaryCoordinator,
        @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    ) : ScoringRepository {
        private val calculationMutex = Mutex()

        private val scoringDayContextResolver =
            ScoringDayContextResolver(scoringConfigFactory, resolveDailyBaselinesUseCase, scoringHistoryRepository)
        private val dailyTrimpComputer =
            DailyTrimpComputer(dataLoader, computeDailyTrimpUseCase, assembleEverydayLoadInputUseCase)
        private val baseSummaryAssembler = BaseSummaryAssembler(bodyMetricsDataLoader)
        private val calibrationGate = CalibrationGate(baselineComputer)
        private val rasTotalsComputer = RasTotalsComputer(seriesLoader)
        private val residualFatigueComputer =
            ResidualFatigueComputer(dataLoader, ComputeResidualFatigueUseCase())
        private val finalSummaryAssembler =
            FinalSummaryAssembler(
                baseSummaryAssembler,
                calibrationGate,
                baselineComputer,
                bodyMetricsDataLoader,
                readinessSummaryCoordinator,
                residualFatigueComputer,
            )

        override suspend fun computeAndPersistDailySummary(
            targetDate: LocalDate,
            steps: Long?,
            prefs: UserPreferences?,
        ) = calculationMutex.withLock {
            val resolvedPrefs = prefs ?: settingsRepo.userPreferences.first()
            val zoneId = resolvedPrefs.scoringZone()
            val computed = computeDailySummary(targetDate, resolvedPrefs)
            val summary =
                if (steps != null) {
                    computed.copy(stepCount = steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                } else {
                    computed
                }
            dataLoader.persistDailySummary(summary, zoneId)
        }

        override suspend fun computeAndPersistDailySummary(
            targetDate: LocalDate,
            steps: Long?,
            prefs: UserPreferences,
            trimpContext: WalkForwardTrimpContext,
            baselineContext: WalkForwardBaselineContext,
        ) = calculationMutex.withLock {
            val zoneId = prefs.scoringZone()
            val computed = computeDailySummary(targetDate, prefs, trimpContext, baselineContext)
            val summary =
                if (steps != null) {
                    computed.copy(stepCount = steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                } else {
                    computed
                }
            dataLoader.persistDailySummary(summary, zoneId)
        }

        override suspend fun computeAndPersistDailySummary(
            targetDate: LocalDate,
            steps: Long?,
            prefs: UserPreferences,
            trimpContext: WalkForwardTrimpContext,
            baselineContext: WalkForwardBaselineContext,
            fatigueContext: WalkForwardFatigueContext,
        ) = calculationMutex.withLock {
            val zoneId = prefs.scoringZone()
            val computed = computeDailySummary(targetDate, prefs, trimpContext, baselineContext, fatigueContext)
            val summary =
                if (steps != null) {
                    computed.copy(stepCount = steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                } else {
                    computed
                }
            dataLoader.persistDailySummary(summary, zoneId)
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
        ): WalkForwardFatigueContext = residualFatigueComputer.fetchWalkForwardContext(startDate, zoneId)

        override suspend fun computeDailySummary(targetDate: LocalDate): DailySummary {
            val prefs = settingsRepo.userPreferences.first()
            return calculationMutex.withLock { computeDailySummary(targetDate, prefs) }
        }

        private suspend fun computeDailySummary(
            targetDate: LocalDate,
            prefs: UserPreferences,
            trimpContext: WalkForwardTrimpContext? = null,
            baselineContext: WalkForwardBaselineContext? = null,
            fatigueContext: WalkForwardFatigueContext? = null,
        ): DailySummary =
            withContext(defaultDispatcher) {
                val context = scoringDayContextResolver.resolveScoringDayContext(targetDate, prefs, baselineContext)
                logD("ScoringRepository") { "RAS CALC START [$targetDate]" }
                val processed = dailyTrimpComputer.processWorkouts(context)
                fatigueContext?.registerCanonicalImpulses(processed.fatigueInputs)
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
                    trimpContext,
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
                        FinalSummaryAssembler.Inputs(
                            context = context,
                            session = session,
                            currentSessionIds = currentSessionIds,
                            dailyTrimpRaw = processed.dailyTrimpRaw,
                            trimpEverydayHr = everydayResult.totalEverydayTrimp,
                            rasTotals = rasTotals,
                            everydayResult = everydayResult,
                            aggregatedSleep = aggregatedSleep,
                            trimpContext = trimpContext,
                            baselineContext = baselineContext,
                            fatigueContext = fatigueContext,
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
