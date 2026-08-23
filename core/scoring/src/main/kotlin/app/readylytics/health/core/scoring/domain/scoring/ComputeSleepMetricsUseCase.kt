package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory
import app.readylytics.health.core.scoring.domain.scoring.strategies.LoadScoringStrategy

import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.scoring.ScoringConstants

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.ReadinessResult
import app.readylytics.health.core.model.domain.model.Diagnostics
import app.readylytics.health.core.model.domain.model.Contributors
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.model.SleepSession
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.scoring.domain.scoring.components.PhaseCalculator
import app.readylytics.health.core.scoring.domain.scoring.sleep.CurrentNightHrvResolver
import app.readylytics.health.core.scoring.domain.scoring.sleep.HrCoverageValidator
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepModifierResolver
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepNadirAnalyzer
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayPolicy
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepPercentileRhrCalculator
import app.readylytics.health.core.model.domain.security.EncryptionManager
import app.readylytics.health.core.scoring.domain.util.HeartRateFormulas
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.scoring.BuildConfig
import app.readylytics.health.core.scoring.domain.util.stdev
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

data class SleepMetricsCollaborators
    @Inject
    constructor(
        val baselineComputer: BaselineComputer,
        val scoringHistoryRepository: ScoringHistoryRepository,
        val scoringCalculator: ScoringCalculator,
        val scoringConfigFactory: ScoringConfigFactory,
        val encryptionManager: EncryptionManager,
        val hrvResolver: CurrentNightHrvResolver,
        val sleepPercentileRhrCalculator: SleepPercentileRhrCalculator,
        val nadirAnalyzer: SleepNadirAnalyzer,
        val coverageValidator: HrCoverageValidator,
        val sleepModifierResolver: SleepModifierResolver,
    )

data class SleepMetricsRequest(
    val session: SleepSession,
    val dayMidnight: Instant,
    val targetDate: LocalDate,
    val prefs: UserPreferences,
    val summary: DailySummary,
    val loadScore: Float,
    val loadScoreEverydayHr: Float?,
    val zoneId: ZoneId,
    val rhrBaselineValue: Float,
    val dayEndMs: Long,
    val currentSessionIds: Set<String>,
    val prefetchedSessions: List<SleepSession>?,
)

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

                val baselineWindow = resolveBaselineWindow(
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
                val yesterdaySummary = collaborators.scoringHistoryRepository.getDailySummaryByDate(yesterdayMidnightMs, zoneId)

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
                        // Frozen baseline but stored RHR is null — use override or default
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

                var sleepScore: Float? = null
                var readinessScore: Float? = null
                var readinessEverydayHr: Float? = null
                var persistedZLnHrv: Float? = null
                var persistedZRhr: Float? = null
                var persistedFlags: String? = null
                var sRest: Float? = null
                var readinessResult: ReadinessResult = ReadinessResult.EMPTY

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
                    )
                val effectiveSigmaHistory = baselineMetrics.effectiveSigmaHistory
                val calculatedRhrSigma = baselineMetrics.calculatedRhrSigma
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

                // Compute calibration status early for freeze gate (HIGH-1)
                val totalValidHrvNights =
                    validHistoricalDayCount + (if (validation.canContributeToBaseline) 1 else 0)
                val isCalibrating = totalValidHrvNights < ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION
                val sessionPhase = PhaseCalculator.calculatePhase(totalValidHrvNights)

                if (currentNocturnalRhr != null) {
                    val nadirCtx = collaborators.nadirAnalyzer.analyze(session, historicalSessions, minHrTimestamp)

                    val zHrv =
                        if (sessionHrvSamples.isNotEmpty()) {
                            collaborators.scoringCalculator.computeHrvZScore(
                                currentHrvMean,
                                muHrvHistory,
                                effectiveSigmaHistory,
                                sigmaPrior,
                                baselineOverride = prefs.hrvBaselineOverride,
                                frozenLnMu = frozenHrvMu,
                                frozenLnSigma = frozenHrvSigma,
                            )
                        } else {
                            null
                        }
                    val zRhr =
                        collaborators.scoringCalculator.computeRhrZScore(
                            currentNocturnalRhr.toFloat(),
                            rhrValues,
                            frozenRhr ?: prefs.rhrBaselineOverride,
                            effectiveRhrSigma,
                        )
                    val rhrDeltaBpm = currentNocturnalRhr.toFloat() - baselineRhrValue.toFloat()

                    sRest =
                        collaborators.scoringCalculator.computeRestorationSubScore(
                            currentHrvMean,
                            muHrvHistory,
                            effectiveSigmaHistory,
                            sigmaPrior,
                            currentNocturnalRhr.toFloat(),
                            rhrValues,
                            frozenRhr ?: prefs.rhrBaselineOverride,
                            prefs.hrvBaselineOverride,
                            scoringConfig.restoration,
                            frozenLnMu = frozenHrvMu,
                            frozenLnSigma = frozenHrvSigma,
                            frozenRhrSigma = effectiveRhrSigma,
                            saturationZ = scoringConfig.hrvSaturationZ,
                        )
                    if (nadirCtx.isLateNadir) sRest *= ScoringConstants.Restoration.LATE_NADIR_PENALTY

                    sleepScore =
                        collaborators.scoringCalculator.computeSleepScore(
                            session.durationMinutes,
                            session.efficiency,
                            session.deepSleepMinutes,
                            session.remSleepMinutes,
                            prefs.goalSleepHours,
                            sRest,
                            prefs.age,
                            stagesSuspicious,
                            scoringConfig.sleepTargets,
                            sleepModifiers.fragmentation,
                            scoringConfig.sleepWeightProfile,
                            sleepModifiers.regularityScore,
                            scoringConfig.hypersomniaOnsetRatio,
                        )

                    val currentHrvBaseline: Float? =
                        when {
                            prefs.hrvBaselineOverride != null -> prefs.hrvBaselineOverride
                            frozenBaseline && frozenHrvMu != null -> exp(frozenHrvMu)
                            muHrvHistory.isNotEmpty() ->
                                exp(
                                    muHrvHistory
                                        .map { ln(it.coerceAtLeast(0.001f)) }
                                        .average()
                                        .toFloat(),
                                )
                            else -> null
                        }
                    val isCurrentHrvOptimal =
                        currentHrvBaseline != null &&
                            currentHrvBaseline > 0f &&
                            currentHrvMean / currentHrvBaseline >= prefs.hrvOptimalThreshold
                    val isCurrentRhrOptimal =
                        baselineRhrValue > 0f &&
                            currentNocturnalRhr.toFloat() / baselineRhrValue <= prefs.rhrOptimalThreshold
                    val yesterdayHrvBaseline = prefs.hrvBaselineOverride ?: yesterdaySummary?.hrvMuMssd?.let { exp(it) }
                    val prevHrv = yesterdaySummary?.nocturnalHrv
                    val isPreviousHrvOptimal =
                        prevHrv != null &&
                            yesterdayHrvBaseline != null &&
                            yesterdayHrvBaseline > 0f &&
                            prevHrv.toFloat() / yesterdayHrvBaseline >=
                            prefs.hrvOptimalThreshold

                    val recoveryFlags =
                        collaborators.scoringCalculator.computeRecoveryFlags(
                            zLnHrv = zHrv,
                            zRhr = zRhr,
                            rhrDeltaBpm = rhrDeltaBpm,
                            yesterdayZLnHrv = yesterdaySummary?.zLnHrv,
                            yesterdayZRhr = yesterdaySummary?.zRhr,
                            hrvMissing = sessionHrvSamples.isEmpty(),
                            stagesSuspicious = stagesSuspicious,
                            isLateNadir = nadirCtx.isLateNadir,
                            isCalibrating = isCalibrating,
                            emergencyFlags = scoringConfig.emergencyFlags,
                            yesterdayTrimp =
                                when (prefs.strainLoadSourceMode) {
                                    LoadSourceMode.WORKOUT_ONLY -> yesterdaySummary?.trimpWorkoutOnly
                                    LoadSourceMode.EVERYDAY_HEART_RATE -> yesterdaySummary?.trimpEverydayHr
                                },
                            yesterdayHrv = yesterdaySummary?.nocturnalHrv?.toFloat(),
                            currentHrv = currentHrvMean,
                            hrvOptimalThreshold = prefs.hrvOptimalThreshold,
                            isCurrentHrvOptimal = isCurrentHrvOptimal,
                            isCurrentRhrOptimal = isCurrentRhrOptimal,
                            isPreviousHrvOptimal = isPreviousHrvOptimal,
                        )

                    readinessScore =
                        collaborators.scoringCalculator.computeReadinessScore(sRest, sleepScore, loadScore, recoveryFlags)
                    readinessEverydayHr =
                        loadScoreEverydayHr?.let {
                            collaborators.scoringCalculator.computeReadinessScore(sRest, sleepScore, it, recoveryFlags)
                        }
                    persistedZLnHrv = zHrv
                    persistedZRhr = zRhr
                    persistedFlags =
                        if (recoveryFlags.isNotEmpty()) recoveryFlags.joinToString(",") { it.name } else null

                    val rollingMu =
                        if (frozenBaseline) {
                            summary.hrvMuMssd
                        } else if (muHrvHistory.isNotEmpty()) {
                            muHrvHistory
                                .map {
                                    ln(it.coerceAtLeast(0.001f))
                                }.average()
                                .toFloat()
                        } else {
                            null
                        }
                    val durationSubScore =
                        collaborators.scoringCalculator.computeDurationSubScore(
                            session.durationMinutes,
                            session.efficiency,
                            prefs.goalSleepHours,
                        )
                    val archSubScore =
                        collaborators.scoringCalculator.computeArchSubScore(
                            session.deepSleepMinutes,
                            session.remSleepMinutes,
                            session.durationMinutes,
                            prefs.age,
                            scoringConfig.sleepTargets,
                        )

                    readinessResult =
                        ReadinessResult(
                            recoveryFlags = recoveryFlags,
                            contributors =
                                Contributors(
                                    hrvScore =
                                        zHrv?.let {
                                            collaborators.scoringCalculator.computeHrvScore(
                                                it,
                                                scoringConfig.hrvSaturationZ,
                                            )
                                        },
                                    rhrScore = zRhr?.let { (50f - 25f * it).coerceIn(0f, 100f) },
                                    durationScore = durationSubScore,
                                    architectureScore = archSubScore,
                                    loadContribution = loadScore,
                                ),
                            diagnostics =
                                Diagnostics(
                                    zLnHrv = zHrv,
                                    zRhr = zRhr,
                                    lnSigma = hrvSigma,
                                    rollingMu = rollingMu,
                                    rhrDeltaBpm = rhrDeltaBpm,
                                    isCalibrating = isCalibrating,
                                    stagesSuspicious = stagesSuspicious,
                                    lateNadir = nadirCtx.isLateNadir,
                                    hrvMissing = sessionHrvSamples.isEmpty(),
                                    timezoneJump = nadirCtx.isTimezoneJump,
                                    configHashCode = scoringConfig.auditTrail.configHashCode,
                                    phaseName = scoringConfig.auditTrail.phaseName,
                                ),
                        )
                }
                if (BuildConfig.DEBUG) {
                    val debugPayload =
                        """
                        {
                            "targetDate": "$targetDate",
                            "dayMidnightMs": ${dayMidnight.toEpochMilli()},
                            "dayEndMs": $dayEndMs,
                            "frozenBaseline": $frozenBaseline,
                            "isCalibrating": $isCalibrating,
                            "windows": {
                                "hrvMuHistorySize": ${muHrvHistory.size},
                                "rhrValuesSize": ${rhrValues.size}
                            },
                            "inputs": {
                                "sessionId": "${session.id}",
                                "currentHrvMean": $currentHrvMean,
                                "currentNocturnalRhr": $currentNocturnalRhr,
                                "durationMinutes": ${session.durationMinutes},
                                "loadScore": $loadScore
                            },
                            "baselines": {
                                "frozenHrvMu": $frozenHrvMu,
                                "frozenHrvSigma": $frozenHrvSigma,
                                "activeHrvMu": ${readinessResult.diagnostics.rollingMu},
                                "activeHrvSigma": $hrvSigma,
                                "frozenRhr": $frozenRhr,
                                "effectiveRhrSigma": $effectiveRhrSigma
                            },
                            "scores": {
                                "zHrv": $persistedZLnHrv,
                                "zRhr": $persistedZRhr,
                                "sRest": $sRest,
                                "sleepScore": $sleepScore,
                                "readinessScore": $readinessScore,
                                "recoveryFlags": "$persistedFlags"
                            }
                        }
                        """.trimIndent()
                    logD("ScoringDebug") { "\n$debugPayload" }
                }

                Result.success(
                    summary.copy(
                        sleepScore = sleepScore,
                        readinessWorkoutOnly = readinessScore,
                        readinessEverydayHr = readinessEverydayHr,
                        nocturnalHrv = if (sessionHrvSamples.isNotEmpty()) currentHrvMean.roundToInt() else null,
                        sleepDurationMinutes = session.durationMinutes,
                        deepSleepPercent =
                            if (session.durationMinutes >
                                0
                            ) {
                                session.deepSleepMinutes / session.durationMinutes.toFloat() * 100f
                            } else {
                                null
                            },
                        remSleepPercent =
                            if (session.durationMinutes >
                                0
                            ) {
                                session.remSleepMinutes / session.durationMinutes.toFloat() * 100f
                            } else {
                                null
                            },
                        restingHeartRate = currentRestingHr,
                        restingHrRatio = restingHrRatio,
                        hrvMuMssd =
                            if (frozenBaseline) {
                                summary.hrvMuMssd
                            } else {
                                (
                                    if (muHrvHistory.isNotEmpty()) {
                                        muHrvHistory
                                            .map { ln(it.coerceAtLeast(0.001f)) }
                                            .average()
                                            .toFloat()
                                    } else {
                                        null
                                    }
                                )
                            },
                        hrvSigmaMssd = if (frozenBaseline) summary.hrvSigmaMssd else hrvSigma,
                        rhrBpm = if (frozenBaseline) summary.rhrBpm else restingHrBaseline?.toFloat(),
                        rhrSigma = if (frozenBaseline) summary.rhrSigma else effectiveRhrSigma,
                        baselineCalculatedAtDate =
                            if (frozenBaseline) {
                                summary.baselineCalculatedAtDate
                            } else if (!isCalibrating) {
                                targetDate
                            } else {
                                null
                            },
                        hrMax =
                            if (frozenBaseline) {
                                summary.hrMax
                            } else if (!isCalibrating) {
                                HeartRateFormulas.resolveMaxHeartRate(prefs)
                            } else {
                                null
                            },
                        rasScalingFactor =
                            if (frozenBaseline) {
                                summary.rasScalingFactor
                            } else if (!isCalibrating) {
                                scoringConfig.rasScalingFactor
                            } else {
                                null
                            },
                        snapshotProfile =
                            if (frozenBaseline) {
                                summary.snapshotProfile
                            } else if (!isCalibrating) {
                                prefs.physiologyProfile.name
                            } else {
                                null
                            },
                        hrvSigmaPrior =
                            if (frozenBaseline) {
                                summary.hrvSigmaPrior
                            } else if (!isCalibrating) {
                                prefs.physiologyProfile.lnSigmaPrior
                            } else {
                                null
                            },
                        baselineObservationCount =
                            if (frozenBaseline) {
                                summary.baselineObservationCount
                            } else if (!isCalibrating) {
                                validHistoricalSessionIds.size
                            } else {
                                null
                            },
                        zLnHrv = persistedZLnHrv,
                        zRhr = persistedZRhr,
                        hrvSigma = hrvSigma,
                        snapshotCalibrationPhase = sessionPhase.name,
                        readinessResult = readinessResult,
                        sRest = sRest,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logE("ComputeSleepMetrics", e) { "Sleep metrics failed for $targetDate" }
                Result.failure("Failed to compute sleep metrics", "SLEEP_METRICS_ERROR")
            }
        }

        private data class BaselineMetricsInput(
            val frozenBaseline: Boolean,
            val frozenHrvSigma: Float?,
            val sigmaHrvHistory: List<Float>,
            val sigmaPrior: Float,
            val sessionHrvSamples: List<Float>,
            val rhrValues: List<Int>,
            val frozenRhrSigma: Float?,
            val session: SleepSession,
            val validation: Any,
        )

        private data class BaselineMetricsResult(
            val effectiveSigmaHistory: List<Float>,
            val calculatedRhrSigma: Float?,
            val effectiveRhrSigma: Float?,
            val hrvSigma: Float?,
            val stagesSuspicious: Boolean,
        )

        private fun isStagesSuspicious(session: SleepSession, validation: Any): Boolean {
            val hasNoStageBreakdown =
                session.durationMinutes > 0 &&
                    session.deepSleepMinutes == 0 &&
                    session.remSleepMinutes == 0 &&
                    session.lightSleepMinutes == 0
            @Suppress("UNCHECKED_CAST")
            val stagesValid = (validation as? Map<String, Any>)?.get("stagesValid") as? Boolean ?: true
            @Suppress("UNCHECKED_CAST")
            val stagesSuspiciousVal = (validation as? Map<String, Any>)?.get("stagesSuspicious") as? Boolean ?: false
            return hasNoStageBreakdown || !stagesValid || stagesSuspiciousVal
        }

        private fun computeBaselineMetrics(input: BaselineMetricsInput): BaselineMetricsResult {
            val frozenBaseline = input.frozenBaseline
            val frozenHrvSigma = input.frozenHrvSigma
            val sigmaHrvHistory = input.sigmaHrvHistory
            val sigmaPrior = input.sigmaPrior
            val sessionHrvSamples = input.sessionHrvSamples
            val rhrValues = input.rhrValues
            val frozenRhrSigma = input.frozenRhrSigma
            val session = input.session
            val validation = input.validation
            val effectiveSigmaHistory =
                if (frozenBaseline && frozenHrvSigma != null) {
                    listOf(frozenHrvSigma)
                } else {
                    sigmaHrvHistory
                }

            val calculatedRhrSigma =
                if (!frozenBaseline && rhrValues.size > 1) {
                    rhrValues.stdev().takeIf { it > 0f }
                } else {
                    null
                }
            val effectiveRhrSigma = frozenRhrSigma ?: calculatedRhrSigma

            val hrvSigma =
                if (sessionHrvSamples.isNotEmpty()) {
                    if (frozenBaseline && frozenHrvSigma != null) {
                        frozenHrvSigma
                    } else {
                        val lnSigmaHistory = effectiveSigmaHistory.map { ln(it.coerceAtLeast(0.001f)) }
                        collaborators.scoringCalculator.hrvSigma(lnSigmaHistory, sigmaPrior)
                    }
                } else {
                    null
                }

            val stagesSuspicious = isStagesSuspicious(session, validation)

            return BaselineMetricsResult(
                effectiveSigmaHistory = effectiveSigmaHistory,
                calculatedRhrSigma = calculatedRhrSigma,
                effectiveRhrSigma = effectiveRhrSigma,
                hrvSigma = hrvSigma,
                stagesSuspicious = stagesSuspicious,
            )
        }

        private data class BaselineWindowResult(
            val rhrValues: List<Int>,
            val muHrvHistory: List<Float>,
            val sigmaHrvHistory: List<Float>,
            val historicalSessions: List<SleepSession>,
            val validHistoricalSessionIds: List<String>,
            val validHistoricalDayCount: Int,
            val frozenHrvMu: Float?,
            val frozenHrvSigma: Float?,
            val frozenRhr: Float?,
            val frozenRhrSigma: Float?,
        )

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
                val rhrValues = collaborators.baselineComputer.rhrHistoryBetween(
                    dayMidnight.toEpochMilli(),
                    dayEndMs,
                    prefs.restingHrPercentile,
                    sleepDayPolicy = sleepDayPolicy,
                )
                val hrvWindows = collaborators.baselineComputer.computeHrvWindowsBetween(
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
