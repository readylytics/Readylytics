package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.Contributors
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.Diagnostics
import app.readylytics.health.core.model.domain.model.ReadinessResult
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.model.RecoveryFlag
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.model.SleepSession
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.scoring.domain.scoring.components.PhaseCalculator
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

@Singleton
class ComputeSleepMetricsUseCase
    @Inject
    constructor(
        private val collaborators: SleepMetricsCollaborators,
    ) {
        suspend operator fun invoke(request: SleepMetricsRequest): Result<DailySummary> {
            val session = request.session
            val dayMidnight = request.dayMidnight
            val targetDate = request.targetDate
            val prefs = request.prefs
            val summary = request.summary
            val loadScore = request.loadScore
            val loadScoreEverydayHr = request.loadScoreEverydayHr
            val zoneId = request.zoneId
            val rhrBaselineValue = request.rhrBaselineValue
            val dayEndMs = request.dayEndMs
            val currentSessionIds = request.currentSessionIds
            val prefetchedSessions = request.prefetchedSessions
            return try {
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

                val frozenBaseline = summary.baselineCalculatedAtDate != null
                val sleepDayPolicy =
                    SleepDayPolicy(
                        coreMergeGapMinutes = prefs.coreMergeGapMinutes,
                        supplementalCutoffMinutesOfDay = prefs.supplementalCutoffMinutesOfDay,
                        minimumCountedSleepSegmentMinutes = prefs.minimumCountedSleepSegmentMinutes,
                        supplementalArchitectureCoveragePercent = prefs.supplementalArchitectureCoveragePercent,
                        scoringZoneId = zoneId,
                    )

                val baselineWindow =
                    resolveBaselineWindow(
                        frozenBaseline = frozenBaseline,
                        summary = summary,
                        dayMidnight = dayMidnight,
                        dayEndMs = dayEndMs,
                        sleepDayPolicy = sleepDayPolicy,
                        currentSessionIds = currentSessionIds,
                        prefs = prefs,
                    )

                val rhrValues = baselineWindow.rhrValues
                val muHrvHistory = baselineWindow.muHrvHistory
                val sigmaHrvHistory = baselineWindow.sigmaHrvHistory
                val historicalSessions = baselineWindow.historicalSessions
                val validHistoricalSessionIds = baselineWindow.validHistoricalSessionIds
                val validHistoricalDayCount = baselineWindow.validHistoricalDayCount
                val frozenHrvMu = baselineWindow.frozenHrvMu
                val frozenHrvSigma = baselineWindow.frozenHrvSigma
                val frozenRhr = baselineWindow.frozenRhr
                val frozenRhrSigma = baselineWindow.frozenRhrSigma

                val yesterdayMidnightMs =
                    targetDate
                        .minusDays(1)
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli()
                val yesterdaySummary =
                    collaborators.scoringHistoryRepository.getDailySummaryByDate(yesterdayMidnightMs, zoneId)

                val hrvResult = collaborators.hrvResolver.resolve(session, currentSessionIds)
                val sessionHrvSamples = hrvResult.samples
                val currentHrvMean = hrvResult.mean
                @Suppress("SENSELESS_COMPARISON")
                val hasMean = currentHrvMean != null
                logD("ComputeSleepMetrics") { "HRV resolved: samples=${sessionHrvSamples.size}, hasMean=$hasMean" }

                val wakeHrResult =
                    collaborators.sleepPercentileRhrCalculator.collect(
                        session = session,
                        dayMidnight = dayMidnight,
                        percentile = prefs.restingHrPercentile,
                        currentSessionIds = currentSessionIds,
                    )
                val currentRestingHr = wakeHrResult.currentRestingHr
                val restingHrBaseline = wakeHrResult.restingHrBaseline
                val restingHrRatio = wakeHrResult.restingHrRatio

                val currentNocturnalRhr = currentRestingHr
                val baselineRhrValue =
                    if (frozenBaseline && frozenRhr != null) {
                        frozenRhr.toInt()
                    } else if (frozenBaseline && frozenRhr == null) {
                        (prefs.rhrBaselineOverride ?: ScoringConstants.DEFAULT_RHR_BPM).toInt()
                    } else {
                        rhrBaselineValue.roundToInt()
                    }

                val allWakeHrRecords =
                    collaborators.scoringHistoryRepository.getHeartRateRecordsByTimeRange(
                        session.startTime,
                        session.endTime,
                    )
                val minHrTimestamp =
                    allWakeHrRecords
                        .asSequence()
                        .filter { record ->
                            record.recordType == RecordType.SLEEP.name &&
                                (currentSessionIds.isEmpty() || record.sessionId in currentSessionIds)
                        }.minWithOrNull(
                            compareBy({ it.beatsPerMinute }, { it.timestampMs }, { it.id }),
                        )?.timestampMs
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
                        durationMinutes = session.durationMinutes,
                        deepMinutes = session.deepSleepMinutes,
                        remMinutes = session.remSleepMinutes,
                        hrCoverageValid = currentHrCoverage,
                    )

                val sigmaPrior = prefs.physiologyProfile.lnSigmaPrior
                val baselineMetrics =
                    computeBaselineMetrics(
                        BaselineMetricsInput(
                            frozenBaseline = frozenBaseline,
                            frozenHrvSigma = frozenHrvSigma,
                            sigmaHrvHistory = sigmaHrvHistory,
                            sigmaPrior = sigmaPrior,
                            sessionHrvSamples = sessionHrvSamples,
                            rhrValues = rhrValues,
                            frozenRhrSigma = frozenRhrSigma,
                            session = session,
                            validation = validation,
                        ),
                        collaborators.scoringCalculator,
                    )
                val effectiveSigmaHistory = baselineMetrics.effectiveSigmaHistory
                val effectiveRhrSigma = baselineMetrics.effectiveRhrSigma
                val hrvSigma = baselineMetrics.hrvSigma
                val stagesSuspicious = baselineMetrics.stagesSuspicious

                val sleepModifiers =
                    collaborators.sleepModifierResolver.resolve(
                        sessionId = session.id,
                        targetDate = targetDate,
                        prefs = prefs,
                        stagesSuspicious = stagesSuspicious,
                        prefetchedSessions = prefetchedSessions?.map { it.toSleepSessionData() },
                    )

                val totalValidHrvNights =
                    validHistoricalDayCount + (if (validation.canContributeToBaseline) 1 else 0)
                val isCalibrating = totalValidHrvNights < ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION
                val sessionPhase = PhaseCalculator.calculatePhase(totalValidHrvNights)

                val nocturnalScoring =
                    if (currentNocturnalRhr != null) {
                        computeNocturnalScores(
                            NocturnalScoringInput(
                                session = session,
                                historicalSessions = historicalSessions,
                                minHrTimestamp = minHrTimestamp,
                                sessionHrvSamples = sessionHrvSamples,
                                currentHrvMean = currentHrvMean,
                                muHrvHistory = muHrvHistory,
                                effectiveSigmaHistory = effectiveSigmaHistory,
                                sigmaPrior = sigmaPrior,
                                frozenHrvMu = frozenHrvMu,
                                frozenHrvSigma = frozenHrvSigma,
                                currentNocturnalRhr = currentNocturnalRhr,
                                rhrValues = rhrValues,
                                frozenRhr = frozenRhr,
                                effectiveRhrSigma = effectiveRhrSigma,
                                baselineRhrValue = baselineRhrValue,
                                prefs = prefs,
                                scoringConfig = scoringConfig,
                                stagesSuspicious = stagesSuspicious,
                                sleepModifiers = sleepModifiers,
                                frozenBaseline = frozenBaseline,
                                isCalibrating = isCalibrating,
                                yesterdaySummary = yesterdaySummary,
                                loadScore = loadScore,
                                loadScoreEverydayHr = loadScoreEverydayHr,
                                summary = summary,
                                hrvSigma = hrvSigma,
                            ),
                        )
                    } else {
                        NocturnalScoringResult(
                            sleepScore = null,
                            readinessScore = null,
                            readinessEverydayHr = null,
                            persistedZLnHrv = null,
                            persistedZRhr = null,
                            persistedFlags = null,
                            sRest = null,
                            readinessResult = ReadinessResult.EMPTY,
                        )
                    }

                logDebugScoringMetrics(
                    targetDate,
                    dayMidnight,
                    dayEndMs,
                    frozenBaseline,
                    isCalibrating,
                    muHrvHistory.size,
                    rhrValues.size,
                    session.id,
                    currentHrvMean,
                    currentNocturnalRhr,
                    session.durationMinutes,
                    loadScore,
                    frozenHrvMu,
                    frozenHrvSigma,
                    nocturnalScoring.readinessResult.diagnostics.rollingMu,
                    hrvSigma,
                    frozenRhr,
                    effectiveRhrSigma,
                    nocturnalScoring.persistedZLnHrv,
                    nocturnalScoring.persistedZRhr,
                    nocturnalScoring.sRest,
                    nocturnalScoring.sleepScore,
                    nocturnalScoring.readinessScore,
                    nocturnalScoring.persistedFlags,
                )

                val updatedSummary =
                    assembleDailySummary(
                        SummaryAssemblyContext(
                            summary = summary,
                            session = session,
                            sessionHrvSamples = sessionHrvSamples,
                            currentHrvMean = currentHrvMean,
                            currentRestingHr = currentRestingHr,
                            restingHrRatio = restingHrRatio,
                            restingHrBaseline = restingHrBaseline,
                            frozenBaseline = frozenBaseline,
                            muHrvHistory = muHrvHistory,
                            hrvSigma = hrvSigma,
                            effectiveRhrSigma = effectiveRhrSigma,
                            isCalibrating = isCalibrating,
                            targetDate = targetDate,
                            prefs = prefs,
                            rasScalingFactor = scoringConfig.rasScalingFactor,
                            validHistoricalSessionIds = validHistoricalSessionIds,
                            persistedZLnHrv = nocturnalScoring.persistedZLnHrv,
                            persistedZRhr = nocturnalScoring.persistedZRhr,
                            sessionPhase = sessionPhase.name,
                            readinessResult = nocturnalScoring.readinessResult,
                            sRest = nocturnalScoring.sRest,
                            sleepScore = nocturnalScoring.sleepScore,
                            readinessScore = nocturnalScoring.readinessScore,
                            readinessEverydayHr = nocturnalScoring.readinessEverydayHr,
                        ),
                    )

                Result.success(updatedSummary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logE("ComputeSleepMetrics", e) { "Sleep metrics failed for $targetDate" }
                Result.failure("Failed to compute sleep metrics", "SLEEP_METRICS_ERROR")
            }
        }

        private suspend fun computeNocturnalScores(input: NocturnalScoringInput): NocturnalScoringResult {
            val nadirCtx =
                collaborators.nadirAnalyzer.analyze(
                    input.session,
                    input.historicalSessions,
                    input.minHrTimestamp,
                )
            val zScores = computeZScores(input)
            val restorationResult = assembleRestoration(input, zScores, nadirCtx.isLateNadir)
            val sRest = restorationResult.sRest
            val sleepScore = computeSleepScore(input, sRest)

            val recoveryFlags = evaluateRecoveryFlags(input, zScores, nadirCtx.isLateNadir)
            val readinessScore =
                collaborators.scoringCalculator.computeReadinessScore(
                    sRest,
                    sleepScore,
                    input.loadScore,
                    recoveryFlags,
                )
            val readinessEverydayHr =
                input.loadScoreEverydayHr?.let {
                    collaborators.scoringCalculator.computeReadinessScore(sRest, sleepScore, it, recoveryFlags)
                }
            val readinessResult =
                buildReadinessResult(
                    input = input,
                    ctx =
                        ReadinessContext(
                            restorationResult = restorationResult,
                            recoveryFlags = recoveryFlags,
                            zHrv = zScores.zHrv,
                            zRhr = zScores.zRhr,
                            rhrDeltaBpm = zScores.rhrDeltaBpm ?: 0f,
                            isLateNadir = nadirCtx.isLateNadir,
                            isTimezoneJump = nadirCtx.isTimezoneJump,
                        ),
                )

            return NocturnalScoringResult(
                sleepScore = sleepScore,
                readinessScore = readinessScore,
                readinessEverydayHr = readinessEverydayHr,
                persistedZLnHrv = zScores.zHrv,
                persistedZRhr = zScores.zRhr,
                persistedFlags = if (recoveryFlags.isNotEmpty()) recoveryFlags.joinToString(",") { it.name } else null,
                sRest = sRest,
                readinessResult = readinessResult,
            )
        }

        private fun assembleRestoration(
            input: NocturnalScoringInput,
            zScores: BaselineZScoreComputer.ZScoreResults,
            isLateNadir: Boolean,
        ): RestorationScoreAssembler.RestorationScoreResult =
            collaborators.restorationScoreAssembler.assembleRestorationScore(
                RestorationScoreAssembler.RestorationParams(
                    zHrv = zScores.zHrv,
                    zRhr = zScores.zRhr,
                    restorationWeights = input.scoringConfig.restoration,
                    saturationZ = input.scoringConfig.hrvSaturationZ,
                    isLateNadir = isLateNadir,
                ),
            )

        private fun computeSleepScore(
            input: NocturnalScoringInput,
            sRest: Float,
        ): Float =
            collaborators.scoringCalculator.computeSleepScore(
                input.session.durationMinutes,
                input.session.efficiency,
                input.session.deepSleepMinutes,
                input.session.remSleepMinutes,
                input.prefs.goalSleepHours,
                sRest,
                input.prefs.age,
                input.stagesSuspicious,
                input.scoringConfig.sleepTargets,
                input.sleepModifiers.fragmentation,
                input.scoringConfig.sleepWeightProfile,
                input.sleepModifiers.regularityScore,
                input.scoringConfig.hypersomniaOnsetRatio,
            )

        private fun computeZScores(input: NocturnalScoringInput): BaselineZScoreComputer.ZScoreResults =
            collaborators.baselineZScoreComputer.computeZScores(
                hrvParams =
                    BaselineZScoreComputer.HrvZScoreParams(
                        sessionHrvSamples = input.sessionHrvSamples,
                        currentHrvMean = input.currentHrvMean,
                        muHrvHistory = input.muHrvHistory,
                        effectiveSigmaHistory = input.effectiveSigmaHistory,
                        sigmaPrior = input.sigmaPrior,
                        frozenHrvMu = input.frozenHrvMu,
                        frozenHrvSigma = input.frozenHrvSigma,
                        hrvBaselineOverride = input.prefs.hrvBaselineOverride,
                    ),
                rhrParams =
                    BaselineZScoreComputer.RhrZScoreParams(
                        currentNocturnalRhr = input.currentNocturnalRhr,
                        rhrValues = input.rhrValues,
                        rhrBaselineOverride = input.prefs.rhrBaselineOverride,
                        frozenRhr = input.frozenRhr,
                        effectiveRhrSigma = input.effectiveRhrSigma,
                        baselineRhrValue = input.baselineRhrValue,
                    ),
            )

        private fun evaluateRecoveryFlags(
            input: NocturnalScoringInput,
            zScores: BaselineZScoreComputer.ZScoreResults,
            isLateNadir: Boolean,
        ): Set<RecoveryFlag> {
            val currentHrvBaseline =
                resolveCurrentHrvBaseline(
                    input.frozenBaseline,
                    input.frozenHrvMu,
                    input.prefs,
                    input.muHrvHistory,
                )
            val isCurrentHrvOptimal =
                isHrvOptimal(currentHrvBaseline, input.currentHrvMean, input.prefs.hrvOptimalThreshold)
            val isCurrentRhrOptimal =
                isRhrOptimal(input.baselineRhrValue, input.currentNocturnalRhr, input.prefs.rhrOptimalThreshold)
            val yesterdayHrvBaseline =
                input.prefs.hrvBaselineOverride ?: input.yesterdaySummary?.hrvMuMssd?.let { exp(it) }
            val isPreviousHrvOptimal =
                isPreviousHrvOptimal(input.yesterdaySummary, yesterdayHrvBaseline, input.prefs.hrvOptimalThreshold)

            return collaborators.scoringCalculator.computeRecoveryFlags(
                zLnHrv = zScores.zHrv,
                zRhr = zScores.zRhr,
                rhrDeltaBpm = zScores.rhrDeltaBpm ?: 0f,
                yesterdayZLnHrv = input.yesterdaySummary?.zLnHrv,
                yesterdayZRhr = input.yesterdaySummary?.zRhr,
                hrvMissing = input.sessionHrvSamples.isEmpty(),
                stagesSuspicious = input.stagesSuspicious,
                isLateNadir = isLateNadir,
                isCalibrating = input.isCalibrating,
                emergencyFlags = input.scoringConfig.emergencyFlags,
                yesterdayTrimp =
                    when (input.prefs.strainLoadSourceMode) {
                        LoadSourceMode.WORKOUT_ONLY -> input.yesterdaySummary?.trimpWorkoutOnly
                        LoadSourceMode.EVERYDAY_HEART_RATE -> input.yesterdaySummary?.trimpEverydayHr
                    },
                yesterdayHrv = input.yesterdaySummary?.nocturnalHrv?.toFloat(),
                currentHrv = input.currentHrvMean,
                hrvOptimalThreshold = input.prefs.hrvOptimalThreshold,
                isCurrentHrvOptimal = isCurrentHrvOptimal,
                isCurrentRhrOptimal = isCurrentRhrOptimal,
                isPreviousHrvOptimal = isPreviousHrvOptimal,
            )
        }

        private fun buildReadinessResult(
            input: NocturnalScoringInput,
            ctx: ReadinessContext,
        ): ReadinessResult {
            val rollingMu =
                if (input.frozenBaseline) {
                    input.summary.hrvMuMssd
                } else if (input.muHrvHistory.isNotEmpty()) {
                    input.muHrvHistory
                        .map { ln(it.coerceAtLeast(0.001f)) }
                        .average()
                        .toFloat()
                } else {
                    null
                }
            val durationSubScore =
                collaborators.scoringCalculator.computeDurationSubScore(
                    input.session.durationMinutes,
                    input.session.efficiency,
                    input.prefs.goalSleepHours,
                )
            val archSubScore =
                collaborators.scoringCalculator.computeArchSubScore(
                    input.session.deepSleepMinutes,
                    input.session.remSleepMinutes,
                    input.session.durationMinutes,
                    input.prefs.age,
                    input.scoringConfig.sleepTargets,
                )

            return ReadinessResult(
                recoveryFlags = ctx.recoveryFlags,
                contributors =
                    Contributors(
                        hrvScore = ctx.restorationResult.hrvScore,
                        rhrScore = ctx.restorationResult.rhrScore,
                        durationScore = durationSubScore,
                        architectureScore = archSubScore,
                        loadContribution = input.loadScore,
                    ),
                diagnostics =
                    Diagnostics(
                        zLnHrv = ctx.zHrv,
                        zRhr = ctx.zRhr,
                        lnSigma = input.hrvSigma,
                        rollingMu = rollingMu,
                        rhrDeltaBpm = ctx.rhrDeltaBpm,
                        isCalibrating = input.isCalibrating,
                        stagesSuspicious = input.stagesSuspicious,
                        lateNadir = ctx.isLateNadir,
                        hrvMissing = input.sessionHrvSamples.isEmpty(),
                        timezoneJump = ctx.isTimezoneJump,
                        configHashCode = input.scoringConfig.auditTrail.configHashCode,
                        phaseName = input.scoringConfig.auditTrail.phaseName,
                    ),
            )
        }

        private suspend fun resolveBaselineWindow(
            frozenBaseline: Boolean,
            summary: DailySummary,
            dayMidnight: Instant,
            dayEndMs: Long,
            sleepDayPolicy: SleepDayPolicy,
            currentSessionIds: Set<String>,
            prefs: UserPreferences,
        ): BaselineWindowResult {
            return if (frozenBaseline) {
                BaselineWindowResult(
                    rhrValues = emptyList(),
                    muHrvHistory = emptyList(),
                    sigmaHrvHistory = emptyList(),
                    historicalSessions = emptyList(),
                    validHistoricalSessionIds = emptyList(),
                    validHistoricalDayCount = 0,
                    frozenHrvMu = summary.hrvMuMssd,
                    frozenHrvSigma = summary.hrvSigmaMssd,
                    frozenRhr = summary.rhrBpm,
                    frozenRhrSigma = summary.rhrSigma,
                )
            } else {
                val rhrValues =
                    collaborators.baselineComputer.rhrHistoryBetween(
                        dayMidnight.toEpochMilli(),
                        dayEndMs,
                        prefs.restingHrPercentile,
                        sleepDayPolicy = sleepDayPolicy,
                    )
                val hrvWindows =
                    collaborators.baselineComputer.computeHrvWindowsBetween(
                        fromMs = dayMidnight.toEpochMilli(),
                        toMs = dayEndMs,
                        excludeSessionIds = currentSessionIds,
                        sleepDayPolicy = sleepDayPolicy,
                    ) ?: BaselineComputer.HrvWindows(
                        muHistory = emptyList(),
                        sigmaHistory = emptyList(),
                        historicalSessions = emptyList(),
                        validHistoricalSessionIds = emptyList(),
                    )
                BaselineWindowResult(
                    rhrValues = rhrValues,
                    muHrvHistory = hrvWindows.muHistory,
                    sigmaHrvHistory = hrvWindows.sigmaHistory,
                    historicalSessions = hrvWindows.historicalSessions,
                    validHistoricalSessionIds = hrvWindows.validHistoricalSessionIds,
                    validHistoricalDayCount = hrvWindows.validHistoricalDayCount,
                    frozenHrvMu = null,
                    frozenHrvSigma = null,
                    frozenRhr = null,
                    frozenRhrSigma = null,
                )
            }
        }

        private fun SleepSession.toSleepSessionData(): SleepSessionData =
            SleepSessionData(
                id = id,
                deviceName = deviceName,
                startTime = startTime,
                endTime = endTime,
                durationMinutes = durationMinutes,
                efficiency = efficiency,
                deepSleepMinutes = deepSleepMinutes,
                lightSleepMinutes = lightSleepMinutes,
                remSleepMinutes = remSleepMinutes,
                awakeMinutes = awakeMinutes,
                sleepScore = sleepScore,
                startZoneOffsetSeconds = startZoneOffsetSeconds,
                endZoneOffsetSeconds = endZoneOffsetSeconds,
            )
    }
