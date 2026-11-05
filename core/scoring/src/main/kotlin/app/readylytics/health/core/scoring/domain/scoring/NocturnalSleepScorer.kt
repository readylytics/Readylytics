package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.Contributors
import app.readylytics.health.core.model.domain.model.Diagnostics
import app.readylytics.health.core.model.domain.model.ReadinessResult
import app.readylytics.health.core.model.domain.model.RecoveryFlag
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import kotlin.math.exp
import kotlin.math.ln

/**
 * Nocturnal (sleep-session) scoring slice of [ComputeSleepMetricsUseCase]: z-scores, restoration,
 * sleep score, recovery flags and readiness. A structural slice of the same [SleepMetricsCollaborators]
 * bundle -- not an independently injectable seam.
 */
internal class NocturnalSleepScorer(
    private val collaborators: SleepMetricsCollaborators,
) {
    suspend fun score(input: NocturnalScoringInput): NocturnalScoringResult {
        val nadirCtx =
            collaborators.nadirAnalyzer.analyze(
                input.core,
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
            durationMinutes = input.session.durationMinutes,
            efficiency = input.session.efficiency,
            deepSleepMinutes = input.session.deepSleepMinutes,
            remSleepMinutes = input.session.remSleepMinutes,
            goalSleepHours = input.prefs.goalSleepHours,
            sRest = sRest,
            userAge = input.prefs.age,
            stagesSuspicious = input.stagesSuspicious,
            sleepTargets = input.scoringConfig.sleepTargets,
            fragmentation = input.sleepModifiers.fragmentation,
            weightProfile = input.scoringConfig.sleepWeightProfile,
            regularityScore = input.sleepModifiers.regularityScore,
            hypersomniaOnsetRatio = input.scoringConfig.hypersomniaOnsetRatio,
            architectureDurationMinutes = input.architectureDurationMinutes,
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
                    liveBaselineRhr = input.liveBaselineRhr,
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
                input.scoringConfig.hypersomniaOnsetRatio,
            )
        val archSubScore =
            collaborators.scoringCalculator.computeArchSubScore(
                input.session.deepSleepMinutes,
                input.session.remSleepMinutes,
                input.architectureDurationMinutes,
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
}
