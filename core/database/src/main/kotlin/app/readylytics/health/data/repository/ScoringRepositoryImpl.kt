package app.readylytics.health.data.repository

import app.readylytics.health.data.local.entity.DailySummaryEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.data.mapper.SleepSessionMapper
import app.readylytics.health.data.preferences.scoringZone
import app.readylytics.health.di.DefaultDispatcher
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.ReadinessResult
import app.readylytics.health.domain.model.SleepSession
import app.readylytics.health.domain.model.getOrNull
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.repository.ScoringHistoryRepository
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.domain.scoring.AssembleDailySummaryUseCase
import app.readylytics.health.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.domain.scoring.BaselineComputer
import app.readylytics.health.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.domain.scoring.EverydayHrLoadResult
import app.readylytics.health.domain.scoring.LongInterval
import app.readylytics.health.domain.scoring.RasCalculator
import app.readylytics.health.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.domain.scoring.ScoringConfig
import app.readylytics.health.domain.scoring.ScoringConfigFactory
import app.readylytics.health.domain.scoring.ScoringConstants
import app.readylytics.health.domain.scoring.TrimpDateBucketer
import app.readylytics.health.domain.scoring.sleep.SleepDayAggregate
import app.readylytics.health.domain.scoring.sleep.SleepDayAggregator
import app.readylytics.health.domain.scoring.sleep.SleepDayPolicy
import app.readylytics.health.domain.scoring.sleep.SleepDaySegment
import app.readylytics.health.domain.util.logD
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
import kotlin.math.round

@Singleton
class ScoringRepositoryImpl
    @Inject
    constructor(
        private val dataLoader: ScoringDayDataLoader,
        private val settingsRepo: SettingsRepository,
        private val baselineComputer: BaselineComputer,
        private val buildLoadSeriesUseCase: BuildLoadSeriesUseCase,
        private val assembleEverydayLoadInputUseCase: AssembleEverydayLoadInputUseCase,
        private val computeSleepMetricsUseCase: ComputeSleepMetricsUseCase,
        private val scoringConfigFactory: ScoringConfigFactory,
        private val computeDailyTrimpUseCase: ComputeDailyTrimpUseCase,
        private val resolveDailyBaselinesUseCase: ResolveDailyBaselinesUseCase,
        private val assembleDailySummaryUseCase: AssembleDailySummaryUseCase,
        private val scoringHistoryRepository: ScoringHistoryRepository,
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

                val aggregatedSleep = resolveSleepAggregation(targetDate, zoneId, prefs)
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
                        computeUncalibratedSummary(
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
                        computeCalibratedSummary(
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
                                java.time.Instant.ofEpochMilli(sample.timestampMs),
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

        private suspend fun computeUncalibratedSummary(
            session: SleepSessionEntity?,
            currentSessionIds: Set<String>,
            baseSummary: DailySummary,
            avgSpo2: Float?,
            avgBodyTemp: Float?,
            calibHrvBaseline: Int?,
            rhrBaselineValue: Float,
            prefs: UserPreferences,
        ): DailySummary {
            if (session == null) {
                return assembleDailySummaryUseCase.assembleUncalibrated(
                    baseSummary = baseSummary,
                    hasSession = false,
                    avgSpo2 = avgSpo2,
                    avgBodyTemp = avgBodyTemp,
                    calibHrvBaseline = calibHrvBaseline,
                    rhrBaselineValue = rhrBaselineValue,
                )
            }
            val hrvValues = if (currentSessionIds.size <= 1) {
                scoringHistoryRepository.getSleepRmssdForSession(session.id)
            } else {
                scoringHistoryRepository.getSleepRmssdForSessionsMap(currentSessionIds.toList()).values.flatten()
            }
            val avgHrv = if (hrvValues.isNotEmpty()) (hrvValues.sum() / hrvValues.size).toInt() else null
            val sleepHrSamples = if (currentSessionIds.size <= 1) {
                scoringHistoryRepository.getSleepHrSamplesForSession(session.id)
            } else {
                scoringHistoryRepository.getSleepHrProjectionForSessions(currentSessionIds.toList()).map { it.beatsPerMinute }.sorted()
            }
            val avgRhr = if (sleepHrSamples.isNotEmpty()) {
                val idx = Math.round((prefs.restingHrPercentile / 100.0) * (sleepHrSamples.size - 1)).toInt().coerceIn(0, sleepHrSamples.size - 1)
                sleepHrSamples[idx]
            } else {
                null
            }
            val deepSleepPercent = if (session.durationMinutes > 0) session.deepSleepMinutes / session.durationMinutes.toFloat() * 100f else null
            val remSleepPercent = if (session.durationMinutes > 0) session.remSleepMinutes / session.durationMinutes.toFloat() * 100f else null

            return assembleDailySummaryUseCase.assembleUncalibrated(
                baseSummary = baseSummary,
                hasSession = true,
                avgSpo2 = avgSpo2,
                avgBodyTemp = avgBodyTemp,
                calibHrvBaseline = calibHrvBaseline,
                rhrBaselineValue = rhrBaselineValue,
                nocturnalHrv = avgHrv,
                restingHeartRate = avgRhr,
                sleepDurationMinutes = session.durationMinutes,
                deepSleepPercent = deepSleepPercent,
                remSleepPercent = remSleepPercent,
            )
        }

        private suspend fun computeCalibratedSummary(
            targetDate: LocalDate,
            zoneId: ZoneId,
            nextDayMidnightMs: Long,
            session: SleepSessionEntity?,
            currentSessionIds: Set<String>,
            baseSummary: DailySummary,
            dailyTrimpRaw: Float,
            trimpEverydayHr: Float,
            avgSpo2: Float?,
            avgBodyTemp: Float?,
            initialBaselines: ResolveDailyBaselinesUseCase.InitialBaselines,
            scoringConfig: ScoringConfig,
            prefs: UserPreferences,
            sleepDayPolicy: SleepDayPolicy,
            trimpContext: WalkForwardTrimpContext?,
            baselineContext: WalkForwardBaselineContext?,
        ): DailySummary {
            val fromDate = targetDate.minusDays(ScoringConstants.CHRONIC_DAYS * 2)
            val dailyTrimpByDate = (
                trimpContext?.dailyTrimpByDate?.subMap(fromDate, true, targetDate, true)
                    ?: TrimpDateBucketer.bucket(dataLoader.loadWorkoutTrimpPoints(fromDate.atStartOfDay(zoneId).toInstant().toEpochMilli(), nextDayMidnightMs), zoneId)
            ).toMutableMap().apply { put(targetDate, dailyTrimpRaw) }

            val everydayTrimpByDate = (
                trimpContext?.everydayTrimpByDate?.subMap(fromDate, true, targetDate, true)
                    ?: TrimpDateBucketer.bucket(dataLoader.loadEverydayTrimpPoints(fromDate.atStartOfDay(zoneId).toInstant().toEpochMilli(), nextDayMidnightMs), zoneId)
            ).toMutableMap().apply { put(targetDate, trimpEverydayHr) }

            val loadSeries = buildLoadSeriesUseCase.execute(targetDate, dailyTrimpByDate, everydayTrimpByDate)
            val withLoadSummary = baseSummary.copy(
                atlWorkoutOnly = loadSeries.atl,
                ctlWorkoutOnly = loadSeries.ctl,
                strainRatioWorkoutOnly = loadSeries.strainRatio,
                loadScoreWorkoutOnly = loadSeries.loadScore,
                atlEverydayHr = loadSeries.atlEverydayHr,
                ctlEverydayHr = loadSeries.ctlEverydayHr,
                strainRatioEverydayHr = loadSeries.strainRatioEverydayHr,
                loadScoreEverydayHr = loadSeries.loadScoreEverydayHr,
            )

            val computedHrvBaseline = baselineComputer.computeHrvBaselineBetween(
                fromMs = targetDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                toMs = nextDayMidnightMs,
                hrvBaselineOverride = prefs.hrvBaselineOverride,
                sleepDayPolicy = sleepDayPolicy,
                prefetchedSessions = baselineContext?.sessions,
            )
            val withHrvBaseline = withLoadSummary.copy(hrvBaseline = computedHrvBaseline)

            val withSleepMetrics = if (session != null) {
                computeSleepMetricsUseCase(
                    session = SleepSessionMapper.toDomain(session),
                    dayMidnight = targetDate.atStartOfDay(zoneId).toInstant(),
                    targetDate = targetDate,
                    prefs = prefs,
                    summary = withHrvBaseline,
                    loadScore = loadSeries.loadScore,
                    loadScoreEverydayHr = loadSeries.loadScoreEverydayHr,
                    zoneId = zoneId,
                    rhrBaselineValue = initialBaselines.rhrBaselineValue,
                    dayEndMs = nextDayMidnightMs,
                    currentSessionIds = currentSessionIds,
                ).getOrNull() ?: withHrvBaseline
            } else {
                withHrvBaseline
            }

            val finalBaselines = resolveDailyBaselinesUseCase.resolveFinalBaselines(
                frozenSnapshot = initialBaselines.frozenSnapshot,
                summaryHrvMuMssd = withSleepMetrics.hrvMuMssd,
                summaryHrvSigmaMssd = withSleepMetrics.hrvSigmaMssd,
                summaryRhrSigma = withSleepMetrics.rhrSigma,
                rhrBaselineValue = initialBaselines.rhrBaselineValue,
            )

            return assembleDailySummaryUseCase.assembleCalibrated(
                baseSummary = withSleepMetrics,
                targetDate = targetDate,
                computedHrvBaseline = computedHrvBaseline,
                finalBaselines = finalBaselines,
                avgSpo2 = avgSpo2,
                avgBodyTemp = avgBodyTemp,
                resolvedHrMax = initialBaselines.hrMax,
                scoringConfigRasScalingFactor = scoringConfig.rasScalingFactor,
                prefs = prefs,
            )
        }

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

        private suspend fun resolveSleepAggregation(
            targetDate: LocalDate,
            zoneId: ZoneId,
            prefs: UserPreferences,
        ): SleepAggregationContext? {
            val fetchStartMs = targetDate.minusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val fetchEndMs = targetDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val sessions = dataLoader.loadOverlappingSessions(fetchStartMs, fetchEndMs)
            if (sessions.isEmpty()) return null

            val policy = SleepDayPolicy(
                coreMergeGapMinutes = prefs.coreMergeGapMinutes,
                supplementalCutoffMinutesOfDay = prefs.supplementalCutoffMinutesOfDay,
                minimumCountedSleepSegmentMinutes = prefs.minimumCountedSleepSegmentMinutes,
                supplementalArchitectureCoveragePercent = prefs.supplementalArchitectureCoveragePercent,
                scoringZoneId = zoneId,
            )
            val aggregate = SleepDayAggregator.aggregateForScoreDay(
                scoreDay = targetDate,
                segments = sessions.map(::toSleepDaySegment),
                policy = policy,
            ) ?: return null

            val coreSessionIds = aggregate.coreCluster.segments.map { it.stableId }.toSet()
            val coreSessions = sessions.filter { it.id in coreSessionIds }
            val baseSession = coreSessions.minByOrNull { it.endTime } ?: return null
            val stageTotals = aggregate.architectureTotals
            val scoringSession =
                baseSession.copy(
                    startTime = aggregate.recoveryWindow.startTimeMs,
                    endTime = aggregate.recoveryWindow.endTimeMs,
                    durationMinutes = aggregate.totalDurationMinutes,
                    efficiency = aggregateEfficiency(coreSessions),
                    deepSleepMinutes = stageTotals.deepMinutes,
                    remSleepMinutes = stageTotals.remMinutes,
                    lightSleepMinutes = stageTotals.lightMinutes,
                    awakeMinutes = stageTotals.awakeMinutes,
                )
            val allSleepIntervals = buildList {
                aggregate.coreCluster.segments.forEach { add(LongInterval(it.startTimeMs, it.endTimeMs)) }
                aggregate.supplementalBlocks.forEach { add(LongInterval(it.segment.startTimeMs, it.segment.endTimeMs)) }
            }

            return SleepAggregationContext(
                aggregate = aggregate,
                scoringSession = scoringSession,
                coreSessionIds = coreSessionIds,
                allSleepIntervals = allSleepIntervals,
            )
        }

        private fun toSleepDaySegment(session: SleepSessionEntity): SleepDaySegment {
            val durationMinutes = if (session.durationMinutes > 0) session.durationMinutes else ((session.endTime - session.startTime) / 60_000L).toInt()
            return SleepDaySegment(
                stableId = session.id,
                startTimeMs = session.startTime,
                endTimeMs = session.endTime,
                durationMinutes = durationMinutes,
                lightSleepMinutes = session.lightSleepMinutes,
                deepSleepMinutes = session.deepSleepMinutes,
                remSleepMinutes = session.remSleepMinutes,
                awakeMinutes = session.awakeMinutes,
                efficiency = session.efficiency,
                startZoneOffsetSeconds = session.startZoneOffsetSeconds,
                endZoneOffsetSeconds = session.endZoneOffsetSeconds,
                sourcePackageName = session.deviceName,
            )
        }

        private fun aggregateEfficiency(coreSessions: List<SleepSessionEntity>): Float {
            val weightedSessions = coreSessions.filter { it.durationMinutes > 0 }
            if (weightedSessions.isEmpty()) return 0f
            val numerator = weightedSessions.sumOf { it.efficiency.toDouble() * it.durationMinutes.toDouble() }
            val denominator = weightedSessions.sumOf { it.durationMinutes }.toDouble()
            return if (denominator > 0.0) (numerator / denominator).toFloat() else weightedSessions.first().efficiency
        }

        private data class SleepAggregationContext(
            val aggregate: SleepDayAggregate,
            val scoringSession: SleepSessionEntity,
            val coreSessionIds: Set<String>,
            val allSleepIntervals: List<LongInterval>,
        )

        private suspend fun sumRasLastSixDays(
            targetDate: LocalDate,
            zoneId: ZoneId,
            selector: (DailySummaryEntity) -> Float?,
        ): Float {
            val previousDaysMs = (1..6).map { i -> targetDate.minusDays(i.toLong()).atStartOfDay(zoneId).toInstant().toEpochMilli() }
            return dataLoader.loadPreviousDaysSummaries(previousDaysMs).mapNotNull(selector).sum()
        }
    }
