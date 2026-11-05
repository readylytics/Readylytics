package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.HeartRateRecord
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.scoring.domain.scoring.components.Phase
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayPolicy
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepModifiers
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

@Singleton
class ComputeSleepMetricsUseCase
    @Inject
    constructor(
        private val collaborators: SleepMetricsCollaborators,
    ) {
        private val nocturnalScorer = NocturnalSleepScorer(collaborators)
        private val baselineWindowResolver = SleepBaselineWindowResolver(collaborators.baselineComputer)

        suspend operator fun invoke(request: SleepMetricsRequest): Result<DailySummary> =
            try {
                val scoringConfig = resolveScoringConfig(request)
                val frozenBaseline =
                    !request.forceLiveBaselines && request.summary.baselineCalculatedAtDate != null
                val baselineWindow =
                    baselineWindowResolver.resolve(
                        request = request,
                        frozenBaseline = frozenBaseline,
                        sleepDayPolicy = sleepDayPolicyFor(request),
                    )
                val yesterdaySummary = lookupYesterdaySummary(request)
                val evidence = collectNightEvidence(request, frozenBaseline, baselineWindow.frozenRhr)
                val baselineMetrics = computeNightBaselineMetrics(request, frozenBaseline, baselineWindow, evidence)
                val sleepModifiers = resolveSleepModifiers(request, baselineMetrics.stagesSuspicious)
                val calibrationState = resolveCalibration(request, evidence.validation)
                val ctx =
                    SleepNightScoringContext(
                        request = request,
                        scoringConfig = scoringConfig,
                        frozenBaseline = frozenBaseline,
                        baselineWindow = baselineWindow,
                        yesterdaySummary = yesterdaySummary,
                        evidence = evidence,
                        baselineMetrics = baselineMetrics,
                        sleepModifiers = sleepModifiers,
                        calibrationState = calibrationState,
                    )
                val currentNocturnalRhr = evidence.wakeHr.currentRestingHr
                val nocturnalScoring =
                    if (currentNocturnalRhr != null) {
                        nocturnalScorer.score(ctx.toNocturnalScoringInput(currentNocturnalRhr))
                    } else {
                        NocturnalScoringResult.EMPTY
                    }
                logDebugScoringMetrics(ctx.toDebugScoringSnapshot(nocturnalScoring))
                Result.success(assembleDailySummary(ctx.toSummaryAssemblyContext(nocturnalScoring)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logE("ComputeSleepMetrics", e) { "Sleep metrics failed for ${request.targetDate}" }
                Result.failure("Failed to compute sleep metrics", "SLEEP_METRICS_ERROR")
            }

        private fun resolveScoringConfig(request: SleepMetricsRequest): ScoringConfig {
            val prefs = request.prefs
            val targetDate = request.targetDate
            val installDate =
                if (prefs.installDate > 0) {
                    LocalDate.ofEpochDay(
                        java.util.concurrent.TimeUnit.MILLISECONDS
                            .toDays(prefs.installDate),
                    )
                } else {
                    targetDate
                }
            val decryptedOverride =
                prefs.circadianThresholdOverride?.let { encrypted ->
                    runCatching { collaborators.encryptionManager.decrypt(encrypted)?.toInt() }.getOrNull()
                }
            val scoringConfig =
                collaborators.scoringConfigFactory.build(
                    userPreferences = prefs,
                    installDate = installDate,
                    currentDate = targetDate,
                    circadianOverride = decryptedOverride,
                )
            logD("ComputeSleepMetrics") {
                "Config applied: hash=${scoringConfig.auditTrail.configHashCode}, " +
                    "phase=${scoringConfig.auditTrail.phaseName}, " +
                    "threshold=${scoringConfig.circadianConsistency.thresholdMinutes}"
            }
            return scoringConfig
        }

        private fun sleepDayPolicyFor(request: SleepMetricsRequest): SleepDayPolicy {
            val prefs = request.prefs
            return SleepDayPolicy(
                coreMergeGapMinutes = prefs.coreMergeGapMinutes,
                supplementalCutoffMinutesOfDay = prefs.supplementalCutoffMinutesOfDay,
                minimumCountedSleepSegmentMinutes = prefs.minimumCountedSleepSegmentMinutes,
                supplementalArchitectureCoveragePercent = prefs.supplementalArchitectureCoveragePercent,
                scoringZoneId = request.zoneId,
            )
        }

        private suspend fun lookupYesterdaySummary(request: SleepMetricsRequest): DailySummary? {
            val yesterdayMidnightMs =
                request.targetDate
                    .minusDays(1)
                    .atStartOfDay(request.zoneId)
                    .toInstant()
                    .toEpochMilli()
            return collaborators.scoringHistoryRepository.getDailySummaryByDate(yesterdayMidnightMs, request.zoneId)
        }

        private suspend fun collectNightEvidence(
            request: SleepMetricsRequest,
            frozenBaseline: Boolean,
            frozenRhr: Float?,
        ): NightEvidence {
            val session = request.session
            val currentSessionIds = request.currentSessionIds
            val hrvResult = collaborators.hrvResolver.resolve(session, currentSessionIds)
            val sessionHrvSamples = hrvResult.samples
            val currentHrvMean = hrvResult.mean
            logD("ComputeSleepMetrics") { "HRV resolved: samples=${sessionHrvSamples.size}" }

            val wakeHrResult =
                collaborators.sleepPercentileRhrCalculator.collect(
                    session = session,
                    dayMidnight = request.dayMidnight,
                    percentile = request.prefs.restingHrPercentile,
                    currentSessionIds = currentSessionIds,
                )
            val currentNocturnalRhr = wakeHrResult.currentRestingHr
            val baselineRhrValue = resolveBaselineRhrValue(request, frozenBaseline, frozenRhr)

            val allWakeHrRecords =
                collaborators.scoringHistoryRepository.getHeartRateRecordsByTimeRange(
                    session.startTime,
                    session.endTime,
                )
            val minHrTimestamp = minSleepHrTimestamp(allWakeHrRecords, currentSessionIds)
            val currentHrCoverage =
                collaborators.coverageValidator.isValid(
                    session.startTime,
                    session.endTime,
                    allWakeHrRecords,
                )
            val validation =
                collaborators.scoringCalculator.validateNight(
                    rmssdMs = if (sessionHrvSamples.isNotEmpty()) currentHrvMean else null,
                    rhrBpm = currentNocturnalRhr?.toFloat(),
                    // Deep/REM are architecture-eligible totals, so the stage fractions must use
                    // the matching architecture-eligible duration, not the nap-inclusive one.
                    durationMinutes = request.architectureDurationMinutes,
                    deepMinutes = session.deepSleepMinutes,
                    remMinutes = session.remSleepMinutes,
                    hrCoverageValid = currentHrCoverage,
                )
            return NightEvidence(hrvResult, wakeHrResult, baselineRhrValue, minHrTimestamp, validation)
        }

        private fun resolveBaselineRhrValue(
            request: SleepMetricsRequest,
            frozenBaseline: Boolean,
            frozenRhr: Float?,
        ): Int =
            if (frozenBaseline && frozenRhr != null) {
                frozenRhr.roundToInt()
            } else if (frozenBaseline && frozenRhr == null) {
                (request.prefs.rhrBaselineOverride ?: ScoringConstants.DEFAULT_RHR_BPM).roundToInt()
            } else {
                request.rhrBaselineValue.roundToInt()
            }

        private fun minSleepHrTimestamp(
            allWakeHrRecords: List<HeartRateRecord>,
            currentSessionIds: Set<String>,
        ): Long? =
            allWakeHrRecords
                .asSequence()
                .filter { record ->
                    record.recordType == RecordType.SLEEP.name &&
                        (currentSessionIds.isEmpty() || record.sessionId in currentSessionIds)
                }.minWithOrNull(
                    compareBy({ it.beatsPerMinute }, { it.timestampMs }, { it.id }),
                )?.timestampMs

        private fun computeNightBaselineMetrics(
            request: SleepMetricsRequest,
            frozenBaseline: Boolean,
            baselineWindow: BaselineWindowResult,
            evidence: NightEvidence,
        ): BaselineMetricsResult =
            computeBaselineMetrics(
                BaselineMetricsInput(
                    frozenBaseline = frozenBaseline,
                    frozenHrvSigma = baselineWindow.frozenHrvSigma,
                    sigmaHrvHistory = baselineWindow.sigmaHrvHistory,
                    sigmaPrior = request.prefs.physiologyProfile.lnSigmaPrior,
                    sessionHrvSamples = evidence.hrv.samples,
                    rhrValues = baselineWindow.rhrValues,
                    frozenRhrSigma = baselineWindow.frozenRhrSigma,
                    session = request.session,
                    validation = evidence.validation,
                ),
                collaborators.scoringCalculator,
            )

        private suspend fun resolveSleepModifiers(
            request: SleepMetricsRequest,
            stagesSuspicious: Boolean,
        ): SleepModifiers =
            collaborators.sleepModifierResolver.resolve(
                coreSessionIds = request.core.sessionIds,
                targetDate = request.targetDate,
                prefs = request.prefs,
                stagesSuspicious = stagesSuspicious,
                prefetchedSessions = request.prefetchedSessions?.map { it.toSleepSessionData() },
            )

        private suspend fun resolveCalibration(
            request: SleepMetricsRequest,
            validation: ScoringCalculator.NightValidationResult,
        ): CalibrationState {
            val summary = request.summary
            // Task C2 (WP-12, OD-2 gate): maturity/calibration phase is resolved from the
            // cumulative, unbounded countEligibleSleepDaysThrough counter -- NOT
            // validHistoricalDayCount, which stays scoped to the HRV mu/sigma statistical
            // window (HRV_SIGMA_WINDOW_DAYS) and must not be reused for maturity. A validated
            // frozen count/phase (persisted on `summary`) always wins over a live recompute;
            // the live scan only runs when there is no frozen calibration metadata to trust.
            val frozenObservationCount = summary.baselineObservationCount
            val frozenPhase =
                summary.snapshotCalibrationPhase?.let { name -> runCatching { Phase.valueOf(name) }.getOrNull() }
            val liveEligibleCount =
                if (frozenObservationCount == null && frozenPhase == null) {
                    collaborators.scoringHistoryRepository
                        .countEligibleSleepDaysThrough(request.targetDate.minusDays(1), request.zoneId)
                        ?.plus(if (validation.isEligibleIgnoringHrCoverage) 1 else 0)
                } else {
                    null
                }
            return resolveCalibrationState(
                liveCount = liveEligibleCount,
                frozenCount = frozenObservationCount,
                frozenPhase = frozenPhase,
            )
        }
    }
