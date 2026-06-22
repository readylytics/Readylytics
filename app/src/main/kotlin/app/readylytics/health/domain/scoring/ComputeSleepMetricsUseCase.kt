package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.model.DailySummaryEntity
import app.readylytics.health.domain.model.ReadinessResult
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.model.SleepSessionEntity
import app.readylytics.health.domain.persistence.DailySummaryDao
import app.readylytics.health.domain.persistence.HeartRateDao
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.scoring.components.PhaseCalculator
import app.readylytics.health.domain.scoring.sleep.CurrentNightHrvResolver
import app.readylytics.health.domain.scoring.sleep.HrCoverageValidator
import app.readylytics.health.domain.scoring.sleep.SleepNadirAnalyzer
import app.readylytics.health.domain.scoring.sleep.SleepPercentileRhrCalculator
import app.readylytics.health.domain.security.EncryptionManager
import app.readylytics.health.domain.util.HeartRateFormulas
import app.readylytics.health.domain.util.logD
import app.readylytics.health.domain.util.stdev
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

@Singleton
class ComputeSleepMetricsUseCase
    @Inject
    constructor(
        private val baselineComputer: BaselineComputer,
        private val dailySummaryDao: DailySummaryDao,
        private val heartRateDao: HeartRateDao,
        private val scoringCalculator: ScoringCalculator,
        private val scoringConfigFactory: ScoringConfigFactory,
        private val encryptionManager: EncryptionManager,
        private val hrvResolver: CurrentNightHrvResolver,
        private val sleepPercentileRhrCalculator: SleepPercentileRhrCalculator,
        private val nadirAnalyzer: SleepNadirAnalyzer,
        private val coverageValidator: HrCoverageValidator,
    ) {
        suspend operator fun invoke(
            session: SleepSessionEntity,
            dayMidnight: Instant,
            targetDate: LocalDate,
            prefs: UserPreferences,
            summary: DailySummaryEntity,
            loadScore: Float,
            loadScoreEverydayHr: Float?,
            zoneId: ZoneId,
            rhrBaselineValue: Float,
            dayEndMs: Long,
        ): Result<DailySummaryEntity> =
            try {
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
                        runCatching { encryptionManager.decrypt(encrypted)?.toInt() }.getOrNull()
                    }
                val scoringConfig =
                    scoringConfigFactory.build(
                        userPreferences = prefs,
                        installDate = installDate,
                        currentDate = targetDate,
                        circadianOverride = decryptedOverride,
                    )
                logD("ComputeSleepMetrics") {
                    "Config applied: hash=${scoringConfig.auditTrail.configHashCode}, phase=${scoringConfig.auditTrail.phaseName}, threshold=${scoringConfig.circadianConsistency.thresholdMinutes}"
                }

                val frozenBaseline = summary.baselineCalculatedAtDate != null

                val rhrValues: List<Int>
                val muHrvHistory: List<Float>
                val sigmaHrvHistory: List<Float>
                val historicalSessions: List<SleepSessionEntity>
                val validHistoricalSessionIds: List<String>
                val frozenHrvMu: Float?
                val frozenHrvSigma: Float?
                val frozenRhr: Float?
                val frozenRhrSigma: Float?

                if (frozenBaseline) {
                    // Frozen baselines stored on the summary — skip live recompute
                    rhrValues = emptyList()
                    muHrvHistory = emptyList()
                    sigmaHrvHistory = emptyList()
                    historicalSessions = emptyList()
                    validHistoricalSessionIds = emptyList()
                    frozenHrvMu = summary.hrvMuMssd
                    frozenHrvSigma = summary.hrvSigmaMssd
                    frozenRhr = summary.rhrBpm
                    frozenRhrSigma = summary.rhrSigma
                } else {
                    // Live recompute path — no stored baselines yet.
                    // computeHrvWindows returns null only when the baseline is frozen (US-B6);
                    // the outer frozenBaseline check already routes frozen days to the other branch,
                    // so null here is an unexpected race — fall back to empty windows.
                    rhrValues =
                        baselineComputer.rhrHistoryBetween(
                            dayMidnight.toEpochMilli(),
                            dayEndMs,
                            prefs.restingHrPercentile,
                        )
                    val hrvWindows =
                        baselineComputer.computeHrvWindowsBetween(
                            fromMs = dayMidnight.toEpochMilli(),
                            toMs = dayEndMs,
                            excludeSessionId = session.id,
                        ) ?: BaselineComputer.HrvWindows(
                            muHistory = emptyList(),
                            sigmaHistory = emptyList(),
                            historicalSessions = emptyList(),
                            validHistoricalSessionIds = emptyList(),
                        )
                    historicalSessions = hrvWindows.historicalSessions
                    validHistoricalSessionIds = hrvWindows.validHistoricalSessionIds
                    sigmaHrvHistory = hrvWindows.sigmaHistory
                    muHrvHistory = hrvWindows.muHistory
                    frozenHrvMu = null
                    frozenHrvSigma = null
                    frozenRhr = null
                    frozenRhrSigma = null
                }

                val yesterdayMidnightMs =
                    targetDate
                        .minusDays(1)
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli()
                val yesterdaySummary = dailySummaryDao.getByDate(yesterdayMidnightMs)

                val hrvResult = hrvResolver.resolve(session)
                val sessionHrvSamples = hrvResult.samples
                val currentHrvMean = hrvResult.mean
                logD("ComputeSleepMetrics") { "HRV resolved: samples=${sessionHrvSamples.size}, mean=$currentHrvMean" }

                val wakeHrResult =
                    sleepPercentileRhrCalculator.collect(
                        session = session,
                        dayMidnight = dayMidnight,
                        percentile = prefs.restingHrPercentile,
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
                    heartRateDao.getByTimeRange(
                        session.startTime,
                        session.endTime,
                    )
                val currentHrCoverage =
                    coverageValidator.isValid(
                        session.startTime,
                        session.endTime,
                        session.durationMinutes,
                        allWakeHrRecords,
                    )
                val validation =
                    scoringCalculator.validateNight(
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
                // When baselines are frozen, use the stored sigma directly; otherwise use live history
                val effectiveSigmaHistory =
                    if (frozenBaseline && frozenHrvSigma != null) {
                        listOf(frozenHrvSigma)
                    } else {
                        sigmaHrvHistory
                    }

                // Determine effective sigma for RHR.
                // Note: preserving null when rhrValues.size <= 1 is intentional to ensure downstream scoring
                // (e.g. LoadScoringStrategy) uses its percentage-based fallback logic during recalculations.
                val calculatedRhrSigma =
                    if (!frozenBaseline && rhrValues.size > 1) {
                        rhrValues
                            .stdev()
                            .takeIf { it > 0f }
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
                            scoringCalculator.hrvSigma(
                                lnSigmaHistory,
                                sigmaPrior,
                            )
                        }
                    } else {
                        null
                    }
                val stagesSuspicious = !validation.stagesValid || validation.stagesSuspicious

                // Compute calibration status early for freeze gate (HIGH-1)
                val totalValidHrvNights =
                    validHistoricalSessionIds.size + (if (validation.canContributeToBaseline) 1 else 0)
                val isCalibrating = totalValidHrvNights < ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION
                val sessionPhase = PhaseCalculator.calculatePhase(totalValidHrvNights)

                if (currentNocturnalRhr != null) {
                    val nadirCtx = nadirAnalyzer.analyze(session, historicalSessions)

                    val zHrv =
                        if (sessionHrvSamples.isNotEmpty()) {
                            scoringCalculator.computeHrvZScore(
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
                        scoringCalculator.computeRhrZScore(
                            currentNocturnalRhr.toFloat(),
                            rhrValues,
                            frozenRhr ?: prefs.rhrBaselineOverride,
                            effectiveRhrSigma,
                        )
                    val rhrDeltaBpm = currentNocturnalRhr.toFloat() - baselineRhrValue.toFloat()

                    sRest =
                        scoringCalculator.computeRestorationSubScore(
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
                        scoringCalculator.computeSleepScore(
                            session.durationMinutes,
                            session.efficiency,
                            session.deepSleepMinutes,
                            session.remSleepMinutes,
                            prefs.goalSleepHours,
                            sRest,
                            prefs.age,
                            stagesSuspicious,
                            scoringConfig.sleepTargets,
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
                    val isPreviousHrvOptimal =
                        yesterdaySummary?.nocturnalHrv != null &&
                            yesterdayHrvBaseline != null &&
                            yesterdayHrvBaseline > 0f &&
                            yesterdaySummary.nocturnalHrv.toFloat() / yesterdayHrvBaseline >=
                            prefs.hrvOptimalThreshold

                    val recoveryFlags =
                        scoringCalculator.computeRecoveryFlags(
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
                        scoringCalculator.computeReadinessScore(sRest, sleepScore, loadScore, recoveryFlags)
                    readinessEverydayHr =
                        loadScoreEverydayHr?.let {
                            scoringCalculator.computeReadinessScore(sRest, sleepScore, it, recoveryFlags)
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
                        scoringCalculator.computeDurationSubScore(
                            session.durationMinutes,
                            session.efficiency,
                            prefs.goalSleepHours,
                        )
                    val archSubScore =
                        scoringCalculator.computeArchSubScore(
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
                                ReadinessResult.Contributors(
                                    hrvScore =
                                        zHrv?.let {
                                            scoringCalculator.computeHrvScore(
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
                                ReadinessResult.Diagnostics(
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
                        recoveryFlags = persistedFlags,
                        hrvSigma = hrvSigma,
                        snapshotCalibrationPhase = sessionPhase.name,
                        diagnostics = readinessResult.diagnostics,
                        contributors = readinessResult.contributors,
                        sRest = sRest,
                    ),
                )
            } catch (e: Exception) {
                Result.failure("Failed to compute sleep metrics", "SLEEP_METRICS_ERROR")
            }
    }
