package app.readylytics.health.data.repository

import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.data.preferences.scoringZone
import app.readylytics.health.core.model.di.DefaultDispatcher
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.ReadinessResult
import app.readylytics.health.domain.model.SleepSession
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.repository.ScoringHistoryRepository
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.scoring.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.EverydayHrLoadResult
import app.readylytics.health.core.scoring.domain.scoring.LongInterval
import app.readylytics.health.core.scoring.domain.scoring.RasCalculator
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfig
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.scoring.domain.scoring.TrimpDateBucketer
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayPolicy
import app.readylytics.health.core.model.domain.util.logD
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.round

@Singleton
class ScoringRepositoryImpl
    @Inject
    constructor(
        private val dataLoader: ScoringDayDataLoader,
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

        override suspend fun computeAndPersistDailySummary(
            targetDate: LocalDate,
            steps: Long?,
        ) {
            computeAndPersistDailySummary(targetDate, steps, settingsRepo.userPreferences.first())
        }

        override suspend fun computeAndPersistDailySummary(
            targetDate: LocalDate,
            steps: Long?,
            prefs: UserPreferences,
        ) {
            computeAndPersist(targetDate, steps, prefs, null, null)
        }

        override suspend fun computeAndPersistDailySummary(
            targetDate: LocalDate,
            steps: Long?,
            prefs: UserPreferences,
            trimpContext: WalkForwardTrimpContext,
            baselineContext: WalkForwardBaselineContext,
        ) {
            computeAndPersist(targetDate, steps, prefs, trimpContext, baselineContext)
        }

        private suspend fun computeAndPersist(
            targetDate: LocalDate,
            steps: Long?,
            prefs: UserPreferences,
            trimpContext: WalkForwardTrimpContext? = null,
            baselineContext: WalkForwardBaselineContext? = null,
        ) = calculationMutex.withLock {
            val zoneId = prefs.scoringZone()
            val computed = computeDailySummary(targetDate, prefs, trimpContext, baselineContext)
            val summary =
                if (steps != null) {
                    computed.copy(stepCount = steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                } else {
                    computed
                }
            persist(summary, zoneId)
        }

        override suspend fun fetchWalkForwardTrimpContext(
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
        ): WalkForwardTrimpContext {
            val fromMs = startDate.minusDays(ScoringConstants.CHRONIC_DAYS * 2).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val toMs = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            return WalkForwardTrimpContext(
                dailyTrimpByDate = TreeMap(TrimpDateBucketer.bucket(dataLoader.loadWorkoutTrimpPoints(fromMs, toMs), zoneId)),
                everydayTrimpByDate = TreeMap(TrimpDateBucketer.bucket(dataLoader.loadEverydayTrimpPoints(fromMs, toMs), zoneId)),
            )
        }

        override suspend fun fetchWalkForwardBaselineContext(
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
        ): WalkForwardBaselineContext =
            WalkForwardBaselineContext(baselineComputer.prefetchWalkForwardSessions(startDate, endDate, zoneId))

        override suspend fun computeDailySummary(targetDate: LocalDate): DailySummary {
            val prefs = settingsRepo.userPreferences.first()
            return calculationMutex.withLock { computeDailySummary(targetDate, prefs) }
        }

        private suspend fun computeDailySummary(
            targetDate: LocalDate,
            prefs: UserPreferences,
            trimpContext: WalkForwardTrimpContext? = null,
            baselineContext: WalkForwardBaselineContext? = null,
        ): DailySummary =
            withContext(defaultDispatcher) {
                val zoneId = prefs.scoringZone()
                val dayMidnightMs = targetDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val nextDayMidnightMs = targetDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                val sleepDayPolicy = createSleepDayPolicy(prefs, zoneId)
                val dailySummary = scoringHistoryRepository.getDailySummaryByDate(dayMidnightMs, zoneId)

                val initialBaselines =
                    resolveDailyBaselinesUseCase.resolveInitialBaselines(
                        dayMidnightMs = dayMidnightMs,
                        nextDayMidnightMs = nextDayMidnightMs,
                        prefs = prefs,
                        dailySummary = dailySummary,
                        sleepDayPolicy = sleepDayPolicy,
                        prefetchedSessions = baselineContext?.sessions,
                    )
                val scoringConfig =
                    scoringConfigFactory.build(
                        userPreferences = prefs,
                        installDate = LocalDate.ofEpochDay(prefs.installDate / 86400000),
                        currentDate = targetDate,
                    )

                logD("ScoringRepository") { "RAS CALC START [$targetDate]" }

                val (workouts, dailyTrimpRaw) =
                    processWorkouts(dayMidnightMs, nextDayMidnightMs, initialBaselines.rhrBaselineValue, initialBaselines.frozenHrMax, prefs)

                val aggregatedSleep = readinessSummaryCoordinator.resolveSleepAggregation(targetDate, zoneId, prefs)
                val session = aggregatedSleep?.scoringSession ?: dataLoader.loadSessionEndingInRange(dayMidnightMs, nextDayMidnightMs)
                val currentSessionIds = aggregatedSleep?.coreSessionIds ?: session?.let { setOf(it.id) }.orEmpty()

                val everydayResult =
                    resolveEverydayTrimp(
                        dayMidnightMs = dayMidnightMs,
                        nextDayMidnightMs = nextDayMidnightMs,
                        workouts = workouts,
                        session = session,
                        aggregatedSleep = aggregatedSleep,
                        dailyTrimpRaw = dailyTrimpRaw,
                        rhrBaselineValue = initialBaselines.rhrBaselineValue,
                        hrMax = initialBaselines.hrMax,
                        prefs = prefs,
                    )
                val trimpEverydayHr = everydayResult.totalEverydayTrimp

                publishTrimpToContext(trimpContext, targetDate, trimpEverydayHr, dailyTrimpRaw, workouts.isNotEmpty())

                val scalingFactor = initialBaselines.frozenRasScalingFactor ?: scoringConfig.rasScalingFactor
                val rasTotals = computeRas(dailyTrimpRaw, trimpEverydayHr, scalingFactor, targetDate, zoneId)

                val baseSummary =
                    buildBaseSummary(
                        targetDate = targetDate,
                        dailySummary = dailySummary,
                        dailyTrimpRaw = dailyTrimpRaw,
                        trimpEverydayHr = trimpEverydayHr,
                        rasTotals = rasTotals,
                        everydayResult = everydayResult,
                        nextDayMidnightMs = nextDayMidnightMs,
                        aggregatedSleep = aggregatedSleep,
                    )

                val isCalibrated =
                    isCalibrated(dailySummary, dayMidnightMs, nextDayMidnightMs, sleepDayPolicy, baselineContext?.sessions, session != null)

                val avgSpo2 = dataLoader.loadAvgSpo2(session)
                val avgBodyTemp = dataLoader.loadAvgBodyTemp(session)

                val finalSummary =
                    if (!isCalibrated) {
                        val calibHrvBaseline =
                            baselineComputer.computeHrvBaselineBetween(
                                fromMs = dayMidnightMs,
                                toMs = nextDayMidnightMs,
                                hrvBaselineOverride = prefs.hrvBaselineOverride,
                                sleepDayPolicy = sleepDayPolicy,
                                prefetchedSessions = baselineContext?.sessions,
                            )
                        readinessSummaryCoordinator.computeUncalibratedSummary(
                            session = session,
                            currentSessionIds = currentSessionIds,
                            baseSummary = baseSummary,
                            avgSpo2 = avgSpo2,
                            avgBodyTemp = avgBodyTemp,
                            calibHrvBaseline = calibHrvBaseline,
                            rhrBaselineValue = initialBaselines.rhrBaselineValue,
                            prefs = prefs,
                        )
                    } else {
                        readinessSummaryCoordinator.computeCalibratedSummary(
                            targetDate = targetDate,
                            zoneId = zoneId,
                            nextDayMidnightMs = nextDayMidnightMs,
                            session = session,
                            currentSessionIds = currentSessionIds,
                            baseSummary = baseSummary,
                            dailyTrimpRaw = dailyTrimpRaw,
                            trimpEverydayHr = trimpEverydayHr,
                            avgSpo2 = avgSpo2,
                            avgBodyTemp = avgBodyTemp,
                            initialBaselines = initialBaselines,
                            scoringConfig = scoringConfig,
                            prefs = prefs,
                            sleepDayPolicy = sleepDayPolicy,
                            trimpContext = trimpContext,
                            baselineContext = baselineContext,
                        )
                    }

                logTelemetry(scoringConfig, prefs, rasTotals.last6DaysRasWorkoutOnly, finalSummary.totalRasWorkoutOnly)
                finalSummary
            }

        private fun createSleepDayPolicy(prefs: UserPreferences, zoneId: ZoneId): SleepDayPolicy =
            SleepDayPolicy(
                coreMergeGapMinutes = prefs.coreMergeGapMinutes,
                supplementalCutoffMinutesOfDay = prefs.supplementalCutoffMinutesOfDay,
                minimumCountedSleepSegmentMinutes = prefs.minimumCountedSleepSegmentMinutes,
                supplementalArchitectureCoveragePercent = prefs.supplementalArchitectureCoveragePercent,
                scoringZoneId = zoneId,
            )

        private suspend fun processWorkouts(
            dayMidnightMs: Long,
            nextDayMidnightMs: Long,
            rhrBaselineValue: Float,
            frozenHrMax: Float?,
            prefs: UserPreferences,
        ): Pair<List<WorkoutRecordEntity>, Float> {
            val workouts = dataLoader.loadWorkouts(dayMidnightMs, nextDayMidnightMs)
            val allDayExerciseHrSamples = dataLoader.loadExerciseHrSamples(workouts)
            val workoutInputs =
                workouts.map { workout ->
                    val workoutHrSamples = dataLoader.loadWorkoutSamples(workout, allDayExerciseHrSamples)
                    ComputeDailyTrimpUseCase.WorkoutInput(
                        id = workout.id,
                        startTime = workout.startTime,
                        endTime = workout.endTime,
                        storedTrimp = workout.trimp,
                        currentModelTrimp = workout.modelTrimp,
                        samples = workoutHrSamples.map { sample ->
                            ComputeWorkoutTrimpUseCase.HeartRateSample(
                                Instant.ofEpochMilli(sample.timestampMs),
                                sample.beatsPerMinute,
                            )
                        },
                    )
                }
            val dailyTrimpResult = computeDailyTrimpUseCase.execute(workoutInputs, prefs, rhrBaselineValue, frozenHrMax)
            dataLoader.persistModelTrimp(workouts, dailyTrimpResult.workoutModelTrimpUpdates)
            return workouts to dailyTrimpResult.totalDailyTrimpRaw
        }

        private suspend fun resolveEverydayTrimp(
            dayMidnightMs: Long,
            nextDayMidnightMs: Long,
            workouts: List<WorkoutRecordEntity>,
            session: SleepSessionEntity?,
            aggregatedSleep: SleepAggregationContext?,
            dailyTrimpRaw: Float,
            rhrBaselineValue: Float,
            hrMax: Float,
            prefs: UserPreferences,
        ): EverydayHrLoadResult {
            val everydayHrBuckets = dataLoader.loadMergedMinuteBuckets(dayMidnightMs, nextDayMidnightMs)
            val sleepIntervalsMs = aggregatedSleep?.allSleepIntervals
                ?: if (session != null) listOf(LongInterval(session.startTime, session.endTime)) else emptyList()
            val workoutIntervalsMs = workouts.map { LongInterval(it.startTime, it.endTime) }
            return assembleEverydayLoadInputUseCase.execute(
                dayStartMs = dayMidnightMs,
                dayEndMs = nextDayMidnightMs,
                hrBuckets = everydayHrBuckets,
                sleepIntervalsMs = sleepIntervalsMs,
                workoutIntervalsMs = workoutIntervalsMs,
                workoutOnlyTrimp = dailyTrimpRaw,
                rhrBaseline = rhrBaselineValue,
                hrMax = hrMax,
                prefs = prefs,
            )
        }

        private fun publishTrimpToContext(
            trimpContext: WalkForwardTrimpContext?,
            targetDate: LocalDate,
            trimpEverydayHr: Float,
            dailyTrimpRaw: Float,
            hasWorkouts: Boolean,
        ) {
            trimpContext?.let { ctx ->
                ctx.everydayTrimpByDate[targetDate] = trimpEverydayHr
                if (hasWorkouts) ctx.dailyTrimpByDate[targetDate] = dailyTrimpRaw
            }
        }

        private suspend fun computeRas(
            dailyTrimpRaw: Float,
            trimpEverydayHr: Float,
            scalingFactor: Float,
            targetDate: LocalDate,
            zoneId: ZoneId,
        ): RasTotals {
            val dailyRas = round(RasCalculator.calculateDailyRas(dailyTrimpRaw, scalingFactor) * 10f) / 10f
            val dailyRasEverydayHr = round(RasCalculator.calculateDailyRas(trimpEverydayHr, scalingFactor) * 10f) / 10f
            val last6DaysRasWorkoutOnly = sumRasLastSixDays(targetDate, zoneId) { it.rasWorkoutOnly }
            val last6DaysRasEverydayHr = sumRasLastSixDays(targetDate, zoneId) { it.rasEverydayHr }
            return RasTotals(
                dailyRas = dailyRas,
                dailyRasEverydayHr = dailyRasEverydayHr,
                totalRasWorkoutOnly = round(dailyRas + last6DaysRasWorkoutOnly),
                totalRasEverydayHr = round(dailyRasEverydayHr + last6DaysRasEverydayHr),
                last6DaysRasWorkoutOnly = last6DaysRasWorkoutOnly,
            )
        }

        private data class RasTotals(
            val dailyRas: Float,
            val dailyRasEverydayHr: Float,
            val totalRasWorkoutOnly: Float,
            val totalRasEverydayHr: Float,
            val last6DaysRasWorkoutOnly: Float,
        )

        private suspend fun buildBaseSummary(
            targetDate: LocalDate,
            dailySummary: DailySummary?,
            dailyTrimpRaw: Float,
            trimpEverydayHr: Float,
            rasTotals: RasTotals,
            everydayResult: EverydayHrLoadResult,
            nextDayMidnightMs: Long,
            aggregatedSleep: SleepAggregationContext?,
        ): DailySummary {
            val latest = dataLoader.loadLatestBodyMetrics(nextDayMidnightMs)

            return (dailySummary ?: DailySummary(date = targetDate)).copy(
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

        private suspend fun isCalibrated(
            dailySummary: DailySummary?,
            dayMidnightMs: Long,
            nextDayMidnightMs: Long,
            sleepDayPolicy: SleepDayPolicy,
            prefetchedSessions: List<SleepSession>?,
            hasSession: Boolean,
        ): Boolean =
            dailySummary?.baselineCalculatedAtDate != null ||
                baselineComputer
                    .computeHrvWindowsBetween(
                        fromMs = dayMidnightMs,
                        toMs = nextDayMidnightMs,
                        sleepDayPolicy = sleepDayPolicy,
                        prefetchedSessions = prefetchedSessions,
                    )?.validHistoricalDayCount
                    ?.plus(if (hasSession) 1 else 0)
                    ?.let { it >= ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION }
                    ?: false



        private fun logTelemetry(scoringConfig: ScoringConfig, prefs: UserPreferences, rasTotalPre: Float, rasTotalPost: Float?) {
            val updatedAudit = scoringConfig.auditTrail.copy(
                appliedSf = scoringConfig.rasScalingFactor,
                physiologyProfile = prefs.physiologyProfile.name,
                rasTotalPre = rasTotalPre,
                rasTotalPost = rasTotalPost,
            )
            logD("ScoringConfig") { "Telemetry: $updatedAudit" }
        }

        override suspend fun persist(summary: DailySummary) =
            persist(summary, settingsRepo.userPreferences.first().scoringZone())

        private suspend fun persist(
            summary: DailySummary,
            zoneId: ZoneId,
        ) {
            dataLoader.persistDailySummary(summary, zoneId)
        }

        override suspend fun toReadinessResult(summary: DailySummary): ReadinessResult = summary.readinessResult


        private suspend fun sumRasLastSixDays(
            targetDate: LocalDate,
            zoneId: ZoneId,
            selector: (DailySummaryEntity) -> Float?,
        ): Float {
            val previousDaysMs = (1..6).map { i -> targetDate.minusDays(i.toLong()).atStartOfDay(zoneId).toInstant().toEpochMilli() }
            return dataLoader.loadPreviousDaysSummaries(previousDaysMs).mapNotNull(selector).sum()
        }
    }
