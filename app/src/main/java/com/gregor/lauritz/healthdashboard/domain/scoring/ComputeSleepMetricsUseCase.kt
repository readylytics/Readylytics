package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.data.security.EncryptionManager
import com.gregor.lauritz.healthdashboard.domain.model.ReadinessResult
import com.gregor.lauritz.healthdashboard.domain.model.Result
import com.gregor.lauritz.healthdashboard.domain.scoring.sleep.CurrentNightHrvResolver
import com.gregor.lauritz.healthdashboard.domain.scoring.sleep.HrCoverageValidator
import com.gregor.lauritz.healthdashboard.domain.scoring.sleep.SleepNadirAnalyzer
import com.gregor.lauritz.healthdashboard.domain.scoring.sleep.SleepPercentileRhrCalculator
import com.gregor.lauritz.healthdashboard.domain.util.logD
import com.gregor.lauritz.healthdashboard.domain.util.HeartRateFormulas
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
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
                        baselineComputer.computeHrvWindows(
                            dayMidnight = dayMidnight,
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
                }

                val yesterdayMidnightMs =
                    targetDate
                        .minusDays(1)
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli()
                val yesterdaySummary = dailySummaryDao.getByDate(yesterdayMidnightMs)

                val hrvResult = hrvResolver.resolve(session, dayMidnight)
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
                var persistedZLnHrv: Float? = null
                var persistedZRhr: Float? = null
                var persistedFlags: String? = null
                var readinessResult: ReadinessResult = ReadinessResult.EMPTY

                val sigmaPrior = prefs.physiologyProfile.lnSigmaPrior
                // When baselines are frozen, use the stored sigma directly; otherwise use live history
                val effectiveSigmaHistory =
                    if (frozenBaseline && frozenHrvSigma != null) {
                        listOf(frozenHrvSigma)
                    } else {
                        sigmaHrvHistory
                    }
                val lnSigmaHistory = effectiveSigmaHistory.map { ln(it.coerceAtLeast(0.001f)) }
                val hrvSigma =
                    if (sessionHrvSamples.isNotEmpty()) {
                        scoringCalculator.hrvSigma(
                            lnSigmaHistory,
                            sigmaPrior,
                        )
                    } else {
                        null
                    }
                val stagesSuspicious = !validation.stagesValid || validation.stagesSuspicious

                // Compute calibration status early for freeze gate (HIGH-1)
                val totalValidHrvNights =
                    validHistoricalSessionIds.size + (if (validation.canContributeToBaseline) 1 else 0)
                val isCalibrating = totalValidHrvNights < ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION

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
                        )
                    val rhrDeltaBpm = currentNocturnalRhr.toFloat() - baselineRhrValue.toFloat()

                    var sRest =
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

                    val recoveryFlags =
                        scoringCalculator.computeRecoveryFlags(
                            zHrv,
                            zRhr,
                            rhrDeltaBpm,
                            yesterdaySummary?.zLnHrv,
                            yesterdaySummary?.zRhr,
                            sessionHrvSamples.isEmpty(),
                            stagesSuspicious,
                            nadirCtx.isLateNadir,
                            isCalibrating,
                            scoringConfig.emergencyFlags,
                        )

                    readinessScore =
                        scoringCalculator.computeReadinessScore(sRest, sleepScore, loadScore, recoveryFlags)
                    persistedZLnHrv = zHrv
                    persistedZRhr = zRhr
                    persistedFlags =
                        if (recoveryFlags.isNotEmpty()) recoveryFlags.joinToString(",") { it.name } else null

                    val rollingMu =
                        if (muHrvHistory.isNotEmpty()) {
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
                            readinessScore = readinessScore,
                            sleepScore = sleepScore,
                            loadScore = loadScore,
                            sRest = sRest,
                            recoveryFlags = recoveryFlags,
                            contributors =
                                ReadinessResult.Contributors(
                                    hrvScore = zHrv?.let { scoringCalculator.computeHrvScore(it, scoringConfig.hrvSaturationZ) },
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

                Result.success(
                    summary.copy(
                        sleepScore = sleepScore,
                        readinessScore = readinessScore,
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
                        baselineCalculatedAtDate =
                            if (frozenBaseline) {
                                summary.baselineCalculatedAtDate
                            } else if (!isCalibrating) {
                                targetDate
                            } else {
                                null
                            },
                        hrMax = if (frozenBaseline) {
                            summary.hrMax
                        } else if (!isCalibrating) {
                            HeartRateFormulas.resolveMaxHeartRate(prefs)
                        } else {
                            null
                        },
                        paiScalingFactor = if (frozenBaseline) {
                            summary.paiScalingFactor
                        } else if (!isCalibrating) {
                            scoringConfig.paiScalingFactor
                        } else {
                            null
                        },
                        snapshotProfile = if (frozenBaseline) {
                            summary.snapshotProfile
                        } else if (!isCalibrating) {
                            prefs.physiologyProfile.name
                        } else {
                            null
                        },
                        hrvSigmaPrior = if (frozenBaseline) {
                            summary.hrvSigmaPrior
                        } else if (!isCalibrating) {
                            prefs.physiologyProfile.lnSigmaPrior
                        } else {
                            null
                        },
                        baselineObservationCount = if (frozenBaseline) {
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
                        diagnostics = readinessResult.diagnostics,
                        contributors = readinessResult.contributors,
                        sRest = readinessResult.sRest,
                    ),
                )
            } catch (e: Exception) {
                Result.failure("Failed to compute sleep metrics", "SLEEP_METRICS_ERROR")
            }
    }
