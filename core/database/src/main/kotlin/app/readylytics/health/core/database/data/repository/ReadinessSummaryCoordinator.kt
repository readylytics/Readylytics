package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.database.data.mapper.SleepSessionMapper
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.RecoveryFlag
import app.readylytics.health.core.model.domain.model.getOrNull
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.scoring.domain.scoring.AssembleDailySummaryUseCase
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.SleepMetricsRequest
import app.readylytics.health.core.scoring.domain.scoring.LongInterval
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfig
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.scoring.domain.scoring.TrimpDateBucketer
import app.readylytics.health.core.scoring.domain.scoring.sleep.CoreRecoveryInput
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayAggregate
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayAggregator
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayPolicy
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDaySegment
import app.readylytics.health.core.scoring.domain.scoring.sleep.findPreviousCoreEndZoneOffsetSeconds
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReadinessSummaryCoordinator
    @Inject
    constructor(
        private val dataLoader: ScoringDayDataLoader,
        private val seriesLoader: ScoringSeriesLoader,
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
            // Aggregated once over the whole fetched window (not per-day via aggregateForScoreDay)
            // so the same pass also yields yesterday's/earlier days' core clusters, needed below to
            // resolve the previous-core offset independently of any baseline-window statistics.
            val aggregationResult =
                SleepDayAggregator.aggregate(
                    segments = sessions.map(::toSleepDaySegment),
                    policy = policy,
                )
            val aggregate = aggregationResult.aggregates.firstOrNull { it.scoreDay == targetDate } ?: return null

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
            val previousCoreEndZoneOffsetSeconds =
                findPreviousCoreEndZoneOffsetSeconds(
                    aggregationResult.aggregates,
                    aggregate.scoreDay,
                    aggregate.coreCluster.startTimeMs,
                )
            val coreRecoveryInput = CoreRecoveryInput.from(aggregate, previousCoreEndZoneOffsetSeconds)

            return SleepAggregationContext(
                aggregate = aggregate,
                scoringSession = scoringSession,
                coreSessionIds = coreSessionIds,
                allSleepIntervals = allSleepIntervals,
                coreRecoveryInput = coreRecoveryInput,
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
                ).withAbsentSleepDiagnostics()
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
                        seriesLoader.loadWorkoutTrimpPoints(
                            fromDate.atStartOfDay(context.zoneId).toInstant().toEpochMilli(),
                            context.nextDayMidnightMs,
                        ),
                        context.zoneId,
                    )
            ).toMutableMap().apply { put(context.targetDate, context.dailyTrimpRaw) }

            val everydayTrimpByDate = (
                context.trimpContext?.everydayTrimpByDate?.subMap(fromDate, true, context.targetDate, true)
                    ?: TrimpDateBucketer.bucket(
                        seriesLoader.loadEverydayTrimpPoints(
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
                zoneId = context.zoneId,
                sleepDayPolicy = context.sleepDayPolicy,
                prefetchedSessions = context.baselineContext?.sessions,
            )
            val withHrvAndSleep =
                assembleHrvAndSleepMetrics(base, context, withLoadSummary, loadSeries, computedHrvBaseline)

            val finalBaselines = resolveDailyBaselinesUseCase.resolveFinalBaselines(
                frozenSnapshot = context.initialBaselines.frozenSnapshot,
                summaryHrvMuMssd = withHrvAndSleep.hrvMuMssd,
                summaryHrvSigmaMssd = withHrvAndSleep.hrvSigmaMssd,
                summaryRhrSigma = withHrvAndSleep.rhrSigma,
                rhrBaselineValue = context.initialBaselines.rhrBaselineValue,
            )

            return assembleDailySummaryUseCase.assembleCalibrated(
                baseSummary = withHrvAndSleep,
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

        private suspend fun assembleHrvAndSleepMetrics(
            base: ReadinessBaseInputs,
            context: CalibratedScoringContext,
            withLoadSummary: DailySummary,
            loadSeries: BuildLoadSeriesUseCase.LoadSeriesResult,
            computedHrvBaseline: Int?,
        ): DailySummary {
            val withHrvBaseline = withLoadSummary.copy(hrvBaseline = computedHrvBaseline)
            return if (base.session != null) {
                computeSleepMetricsUseCase(
                    SleepMetricsRequest(
                        session = SleepSessionMapper.toDomain(base.session),
                        core = base.coreRecoveryInput ?: fallbackCoreRecoveryInput(base.session),
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
                    ),
                ).getOrNull() ?: withHrvBaseline
            } else {
                // C3 (WP-13): the day's only sleep session is confirmed absent -- explicitly flag
                // it as no-data rather than silently leaving withHrvBaseline's sleep fields at
                // whatever freshDaySummary left them (null). Independent inputs already folded
                // into withHrvBaseline (load, steps, vitals) are untouched.
                withHrvBaseline.withAbsentSleepDiagnostics()
            }
        }

        /**
         * Explicitly flags a day whose required source input (its sleep session) was confirmed
         * absent, reusing the existing [RecoveryFlag.HRV_MISSING] vocabulary (already surfaced to
         * the user via the RECOVERY_HRV_MISSING insight and AI-recommendation glossary) rather than
         * leaving sleep-related diagnostics silently null with no explanation.
         */
        private fun DailySummary.withAbsentSleepDiagnostics(): DailySummary =
            copy(
                readinessResult =
                    readinessResult.copy(
                        recoveryFlags = readinessResult.recoveryFlags + RecoveryFlag.HRV_MISSING,
                        diagnostics = readinessResult.diagnostics.copy(hrvMissing = true),
                    ),
            )
    }

/**
 * WP-14/C4: [ReadinessBaseInputs.coreRecoveryInput] is only ever null when
 * [ReadinessSummaryCoordinator.resolveSleepAggregation] itself returned null and the caller fell
 * back to a single raw session ([ScoringDayDataLoader.loadSessionEndingInRange]) with no
 * aggregation context at all -- there is no core/nap distinction to make in that case, so this
 * treats the raw session as its own (single-segment) core, with no previous-core offset evidence
 * available. Kept as a file-level function (rather than a member) to keep
 * [ReadinessSummaryCoordinator]'s own method count under detekt's `TooManyFunctions` threshold.
 */
private fun fallbackCoreRecoveryInput(session: SleepSessionEntity): CoreRecoveryInput =
    CoreRecoveryInput.fromSingleSession(
        sessionId = session.id,
        startTimeMs = session.startTime,
        endTimeMs = session.endTime,
        coreSleepDurationMinutes = session.durationMinutes,
        endZoneOffsetSeconds = session.endZoneOffsetSeconds,
        previousCoreEndZoneOffsetSeconds = null,
    )

data class ReadinessBaseInputs(
    val session: SleepSessionEntity?,
    val currentSessionIds: Set<String>,
    val baseSummary: DailySummary,
    val avgSpo2: Float?,
    val avgBodyTemp: Float?,
    val coreRecoveryInput: CoreRecoveryInput? = null,
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
    val coreRecoveryInput: CoreRecoveryInput,
)
