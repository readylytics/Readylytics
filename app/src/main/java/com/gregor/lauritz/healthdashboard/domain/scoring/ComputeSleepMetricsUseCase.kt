package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.data.security.EncryptionManager
import com.gregor.lauritz.healthdashboard.domain.model.ReadinessResult
import com.gregor.lauritz.healthdashboard.domain.scoring.sleep.CurrentNightHrvResolver
import com.gregor.lauritz.healthdashboard.domain.scoring.sleep.HrCoverageValidator
import com.gregor.lauritz.healthdashboard.domain.scoring.sleep.SleepNadirAnalyzer
import com.gregor.lauritz.healthdashboard.domain.scoring.sleep.WakeWindowHrCollector
import com.gregor.lauritz.healthdashboard.domain.util.logD
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
        private val wakeHrCollector: WakeWindowHrCollector,
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
        ): DailySummaryEntity {
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
                    runCatching { encryptionManager.decrypt(encrypted).toInt() }.getOrNull()
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

            val rhrValues = baselineComputer.rhrHistory(dayMidnight)
            val yesterdayMidnightMs =
                targetDate
                    .minusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val yesterdaySummary = dailySummaryDao.getByDate(yesterdayMidnightMs)

            val hrvWindows =
                baselineComputer.computeHrvWindows(
                    dayMidnight = dayMidnight,
                    excludeSessionId = session.id,
                )
            val historicalSessions = hrvWindows.historicalSessions
            val validHistoricalSessionIds = hrvWindows.validHistoricalSessionIds
            val sigmaHrvHistory = hrvWindows.sigmaHistory
            val muHrvHistory = hrvWindows.muHistory

            val hrvResult = hrvResolver.resolve(session, dayMidnight)
            val sessionHrvSamples = hrvResult.samples
            val currentHrvMean = hrvResult.mean
            logD("ComputeSleepMetrics") { "HRV resolved: samples=${sessionHrvSamples.size}, mean=$currentHrvMean" }

            val currentNocturnalRhr = heartRateDao.getAvgSleepHr(session.id)
            val baselineRhrValue = baselineComputer.resolveBaselineRhrRounded(rhrValues, prefs.rhrBaselineOverride)

            val beforeMs = prefs.restingHrBeforeMinutes * 60 * 1000L
            val afterMs = prefs.restingHrAfterMinutes * 60 * 1000L
            val wakeHrResult = wakeHrCollector.collect(session, dayMidnight, beforeMs, afterMs)
            val currentRestingHr = wakeHrResult.currentRestingHr
            val restingHrBaseline = wakeHrResult.restingHrBaseline
            val restingHrRatio = wakeHrResult.restingHrRatio

            val allWakeHrRecords =
                heartRateDao.getByTimeRange(
                    (historicalSessions.minOfOrNull { it.endTime } ?: session.endTime) - beforeMs,
                    (historicalSessions.maxOfOrNull { it.endTime } ?: session.endTime) + afterMs,
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

            var rhrRatio: Float? = null
            var sleepScore: Float? = null
            var readinessScore: Float? = null
            var persistedZLnHrv: Float? = null
            var persistedZRhr: Float? = null
            var persistedFlags: String? = null
            var readinessResult: ReadinessResult = ReadinessResult.EMPTY

            val sigmaPrior = prefs.physiologyProfile.lnSigmaPrior
            val lnSigmaHistory = sigmaHrvHistory.map { ln(it.coerceAtLeast(0.001f)) }
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

            if (currentNocturnalRhr != null) {
                rhrRatio = currentNocturnalRhr.toFloat() / (baselineRhrValue + 0.001f)
                val nadirCtx = nadirAnalyzer.analyze(session, historicalSessions)

                val zHrv =
                    if (sessionHrvSamples.isNotEmpty()) {
                        scoringCalculator.computeHrvZScore(
                            currentHrvMean,
                            muHrvHistory,
                            sigmaHrvHistory,
                            sigmaPrior,
                            prefs.hrvBaselineOverride,
                        )
                    } else {
                        null
                    }
                val zRhr =
                    scoringCalculator.computeRhrZScore(
                        currentNocturnalRhr.toFloat(),
                        rhrValues,
                        prefs.rhrBaselineOverride,
                    )
                val rhrDeltaBpm = currentNocturnalRhr.toFloat() - baselineRhrValue.toFloat()

                var sRest =
                    scoringCalculator.computeRestorationSubScore(
                        currentHrvMean,
                        muHrvHistory,
                        sigmaHrvHistory,
                        sigmaPrior,
                        currentNocturnalRhr.toFloat(),
                        rhrValues,
                        prefs.rhrBaselineOverride,
                        prefs.hrvBaselineOverride,
                        scoringConfig.restoration,
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

                val totalValidHrvNights =
                    validHistoricalSessionIds.size + (if (validation.canContributeToBaseline) 1 else 0)
                val isCalibrating = totalValidHrvNights < ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION

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

                readinessScore = scoringCalculator.computeReadinessScore(sRest, sleepScore, loadScore, recoveryFlags)
                persistedZLnHrv = zHrv
                persistedZRhr = zRhr
                persistedFlags = if (recoveryFlags.isNotEmpty()) recoveryFlags.joinToString(",") { it.name } else null

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
                                hrvScore = zHrv?.let { scoringCalculator.computeHrvScore(it) },
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

            return summary.copy(
                sleepScore = sleepScore,
                readinessScore = readinessScore,
                nocturnalRhr = currentNocturnalRhr,
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
                rhrRatio = rhrRatio,
                restingHeartRate = currentRestingHr,
                restingHrRatio = restingHrRatio,
                restingHrBaseline = restingHrBaseline,
                zLnHrv = persistedZLnHrv,
                zRhr = persistedZRhr,
                recoveryFlags = persistedFlags,
                hrvSigma = hrvSigma,
                diagnostics = readinessResult.diagnostics,
                contributors = readinessResult.contributors,
                sRest = readinessResult.sRest,
            )
        }
    }
