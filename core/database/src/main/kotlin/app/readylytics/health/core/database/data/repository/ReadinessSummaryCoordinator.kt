package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.database.data.mapper.SleepSessionMapper
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.getOrNull
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.scoring.domain.scoring.AssembleDailySummaryUseCase
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.LongInterval
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfig
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.scoring.domain.scoring.TrimpDateBucketer
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayAggregate
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayAggregator
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayPolicy
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDaySegment
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReadinessSummaryCoordinator
    @Inject
    constructor(
        private val dataLoader: ScoringDayDataLoader,
        private val scoringHistoryRepository: ScoringHistoryRepository,
        private val baselineComputer: BaselineComputer,
        private val buildLoadSeriesUseCase: BuildLoadSeriesUseCase,
        private val computeSleepMetricsUseCase: ComputeSleepMetricsUseCase,
        private val resolveDailyBaselinesUseCase: ResolveDailyBaselinesUseCase,
        private val assembleDailySummaryUseCase: AssembleDailySummaryUseCase,
    ) {
        suspend fun resolveSleepAggregation(
            targetDate: LocalDate,
            zoneId: ZoneId,
            prefs: UserPreferences,
        ): SleepAggregationContext? {
            val fetchStartMs = targetDate.minusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val fetchEndMs = targetDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val sessions = dataLoader.loadOverlappingSessions(fetchStartMs, fetchEndMs)
            if (sessions.isEmpty()) return null

            val policy =
                SleepDayPolicy(
                    coreMergeGapMinutes = prefs.coreMergeGapMinutes,
                    supplementalCutoffMinutesOfDay = prefs.supplementalCutoffMinutesOfDay,
                    minimumCountedSleepSegmentMinutes = prefs.minimumCountedSleepSegmentMinutes,
                    supplementalArchitectureCoveragePercent = prefs.supplementalArchitectureCoveragePercent,
                    scoringZoneId = zoneId,
                )
            val aggregate =
                SleepDayAggregator.aggregateForScoreDay(
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
            val allSleepIntervals =
                buildList {
                    aggregate.coreCluster.segments.forEach {
                        add(LongInterval(it.startTimeMs, it.endTimeMs))
                    }
                    aggregate.supplementalBlocks.forEach {
                        add(LongInterval(it.segment.startTimeMs, it.segment.endTimeMs))
                    }
                }

            return SleepAggregationContext(
                aggregate = aggregate,
                scoringSession = scoringSession,
                coreSessionIds = coreSessionIds,
                allSleepIntervals = allSleepIntervals,
            )
        }

        private fun toSleepDaySegment(session: SleepSessionEntity): SleepDaySegment {
            val durationMinutes =
                if (session.durationMinutes > 0) {
                    session.durationMinutes
                } else {
                    ((session.endTime - session.startTime) / 60_000L).toInt()
                }
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

        suspend fun computeUncalibratedSummary(
            base: ReadinessBaseInputs,
            calibHrvBaseline: Int?,
            rhrBaselineValue: Float,
            prefs: UserPreferences,
        ): DailySummary {
            if (base.session == null) {
                return assembleDailySummaryUseCase.assembleUncalibrated(
                    baseSummary = base.baseSummary,
                    hasSession = false,
                    avgSpo2 = base.avgSpo2,
                    avgBodyTemp = base.avgBodyTemp,
                    calibHrvBaseline = calibHrvBaseline,
                    rhrBaselineValue = rhrBaselineValue,
                )
            }
            val hrvValues = if (base.currentSessionIds.size <= 1) {
                scoringHistoryRepository.getSleepRmssdForSession(base.session.id)
            } else {
                scoringHistoryRepository.getSleepRmssdForSessionsMap(base.currentSessionIds.toList()).values.flatten()
            }
            val avgHrv = if (hrvValues.isNotEmpty()) (hrvValues.sum() / hrvValues.size).toInt() else null
            val sleepHrSamples = if (base.currentSessionIds.size <= 1) {
                scoringHistoryRepository.getSleepHrSamplesForSession(base.session.id)
            } else {
                scoringHistoryRepository.getSleepHrProjectionForSessions(base.currentSessionIds.toList())
                    .map { it.beatsPerMinute }
                    .sorted()
            }
            val avgRhr = if (sleepHrSamples.isNotEmpty()) {
                val idx =
                    Math.round((prefs.restingHrPercentile / 100.0) * (sleepHrSamples.size - 1))
                        .toInt()
                        .coerceIn(0, sleepHrSamples.size - 1)
                sleepHrSamples[idx]
            } else {
                null
            }
            val deepSleepPercent =
                if (base.session.durationMinutes > 0) {
                    base.session.deepSleepMinutes / base.session.durationMinutes.toFloat() * 100f
                } else {
                    null
                }
            val remSleepPercent =
                if (base.session.durationMinutes > 0) {
                    base.session.remSleepMinutes / base.session.durationMinutes.toFloat() * 100f
                } else {
                    null
                }

            return assembleDailySummaryUseCase.assembleUncalibrated(
                baseSummary = base.baseSummary,
                hasSession = true,
                avgSpo2 = base.avgSpo2,
                avgBodyTemp = base.avgBodyTemp,
                calibHrvBaseline = calibHrvBaseline,
                rhrBaselineValue = rhrBaselineValue,
                nocturnalHrv = avgHrv,
                restingHeartRate = avgRhr,
                sleepDurationMinutes = base.session.durationMinutes,
                deepSleepPercent = deepSleepPercent,
                remSleepPercent = remSleepPercent,
            )
        }

        private suspend fun resolveTrimpSeries(
            context: CalibratedScoringContext,
        ): Pair<Map<LocalDate, Float>, Map<LocalDate, Float>> {
            val fromDate = context.targetDate.minusDays(ScoringConstants.CHRONIC_DAYS * 2)
            val dailyTrimpByDate = (
                context.trimpContext?.dailyTrimpByDate?.subMap(fromDate, true, context.targetDate, true)
                    ?: TrimpDateBucketer.bucket(
                        dataLoader.loadWorkoutTrimpPoints(
                            fromDate.atStartOfDay(context.zoneId).toInstant().toEpochMilli(),
                            context.nextDayMidnightMs,
                        ),
                        context.zoneId,
                    )
            ).toMutableMap().apply { put(context.targetDate, context.dailyTrimpRaw) }

            val everydayTrimpByDate = (
                context.trimpContext?.everydayTrimpByDate?.subMap(fromDate, true, context.targetDate, true)
                    ?: TrimpDateBucketer.bucket(
                        dataLoader.loadEverydayTrimpPoints(
                            fromDate.atStartOfDay(context.zoneId).toInstant().toEpochMilli(),
                            context.nextDayMidnightMs,
                        ),
                        context.zoneId,
                    )
            ).toMutableMap().apply { put(context.targetDate, context.trimpEverydayHr) }
            return Pair(dailyTrimpByDate, everydayTrimpByDate)
        }

        suspend fun computeCalibratedSummary(
            base: ReadinessBaseInputs,
            context: CalibratedScoringContext,
        ): DailySummary {
            val (dailyTrimpByDate, everydayTrimpByDate) = resolveTrimpSeries(context)

            val loadSeries = buildLoadSeriesUseCase.execute(context.targetDate, dailyTrimpByDate, everydayTrimpByDate)
            val withLoadSummary = base.baseSummary.copy(
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
                fromMs = context.targetDate.atStartOfDay(context.zoneId).toInstant().toEpochMilli(),
                toMs = context.nextDayMidnightMs,
                hrvBaselineOverride = context.prefs.hrvBaselineOverride,
                sleepDayPolicy = context.sleepDayPolicy,
                prefetchedSessions = context.baselineContext?.sessions,
            )
            val withHrvBaseline = withLoadSummary.copy(hrvBaseline = computedHrvBaseline)

            val withSleepMetrics = if (base.session != null) {
                computeSleepMetricsUseCase(
                    session = SleepSessionMapper.toDomain(base.session),
                    dayMidnight = context.targetDate.atStartOfDay(context.zoneId).toInstant(),
                    targetDate = context.targetDate,
                    prefs = context.prefs,
                    summary = withHrvBaseline,
                    loadScore = loadSeries.loadScore,
                    loadScoreEverydayHr = loadSeries.loadScoreEverydayHr,
                    zoneId = context.zoneId,
                    rhrBaselineValue = context.initialBaselines.rhrBaselineValue,
                    dayEndMs = context.nextDayMidnightMs,
                    currentSessionIds = base.currentSessionIds,
                    prefetchedSessions = context.baselineContext?.sessions,
                ).getOrNull() ?: withHrvBaseline
            } else {
                withHrvBaseline
            }

            val finalBaselines = resolveDailyBaselinesUseCase.resolveFinalBaselines(
                frozenSnapshot = context.initialBaselines.frozenSnapshot,
                summaryHrvMuMssd = withSleepMetrics.hrvMuMssd,
                summaryHrvSigmaMssd = withSleepMetrics.hrvSigmaMssd,
                summaryRhrSigma = withSleepMetrics.rhrSigma,
                rhrBaselineValue = context.initialBaselines.rhrBaselineValue,
            )

            return assembleDailySummaryUseCase.assembleCalibrated(
                baseSummary = withSleepMetrics,
                targetDate = context.targetDate,
                computedHrvBaseline = computedHrvBaseline,
                finalBaselines = finalBaselines,
                avgSpo2 = base.avgSpo2,
                avgBodyTemp = base.avgBodyTemp,
                resolvedHrMax = context.initialBaselines.hrMax,
                scoringConfigRasScalingFactor = context.scoringConfig.rasScalingFactor,
                prefs = context.prefs,
            )
        }
    }

data class ReadinessBaseInputs(
    val session: SleepSessionEntity?,
    val currentSessionIds: Set<String>,
    val baseSummary: DailySummary,
    val avgSpo2: Float?,
    val avgBodyTemp: Float?,
)

data class CalibratedScoringContext(
    val targetDate: LocalDate,
    val zoneId: ZoneId,
    val nextDayMidnightMs: Long,
    val dailyTrimpRaw: Float,
    val trimpEverydayHr: Float,
    val initialBaselines: ResolveDailyBaselinesUseCase.InitialBaselines,
    val scoringConfig: ScoringConfig,
    val prefs: UserPreferences,
    val sleepDayPolicy: SleepDayPolicy,
    val trimpContext: WalkForwardTrimpContext?,
    val baselineContext: WalkForwardBaselineContext?,
)

data class SleepAggregationContext(
    val aggregate: SleepDayAggregate,
    val scoringSession: SleepSessionEntity,
    val coreSessionIds: Set<String>,
    val allSleepIntervals: List<LongInterval>,
)
