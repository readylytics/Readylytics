package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.ReadinessResult
import app.readylytics.health.core.model.domain.model.RecoveryFlag
import app.readylytics.health.core.model.domain.model.SleepSession
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.security.EncryptionManager
import app.readylytics.health.core.scoring.domain.scoring.sleep.CoreRecoveryInput
import app.readylytics.health.core.scoring.domain.scoring.sleep.CurrentNightHrvResolver
import app.readylytics.health.core.scoring.domain.scoring.sleep.HrCoverageValidator
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepModifierResolver
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepModifiers
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepNadirAnalyzer
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepPercentileRhrCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

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
        val baselineZScoreComputer: BaselineZScoreComputer = BaselineZScoreComputer(scoringCalculator),
        val restorationScoreAssembler: RestorationScoreAssembler = RestorationScoreAssembler(scoringCalculator),
    )

/**
 * One sleep-scoring pass.
 *
 * [dayEndMs], [currentSessionIds] and [prefetchedSessions] together define how far into the day the
 * pass is allowed to look, and ordinary daily scoring passes the whole day (next-day midnight, the
 * aggregated core cluster, and the walk-forward session prefetch).
 *
 * A *morning-anchored* caller — the workout recommendation — narrows the same three fields instead
 * of taking a second code path: [dayEndMs] becomes the selected session's wake time, so the RHR and
 * HRV baseline windows in `resolveBaselineWindow` stop there; [currentSessionIds] is the single
 * selected record, so nightly HRV and nocturnal RHR come from that record alone; and
 * [prefetchedSessions] is pre-truncated at the same wake time, so the regularity modifier's
 * circadian score cannot see a nap recorded later the same day. A sleep record appended after the
 * anchor therefore cannot move the resulting `zLnHrv`, sleep score, or illness flag.
 *
 * [forceLiveBaselines] completes that bounding. Ordinary scoring reads a day's *frozen* baseline
 * snapshot once `baselineCalculatedAtDate` is stamped, and that snapshot was itself computed with
 * `dayEndMs = next-day midnight`. A morning-anchored caller that honoured the freeze would silently
 * switch bounding regimes the moment a day froze, so the same day would score differently before
 * and after. Setting this flag keeps the pass on the live, [dayEndMs]-bounded windows in both
 * cases. It is only safe for callers that do **not** persist the resulting summary — the
 * recommendation path does not.
 */
data class SleepMetricsRequest(
    val session: SleepSession,
    /**
     * WP-14/C4: the core (overnight) cluster's own IDs/window/offset evidence, distinct from
     * [session]'s whole-day, nap-inclusive duration/architecture. Recovery-timing/fragmentation
     * scoring must derive exclusively from this, never from [session].
     */
    val core: CoreRecoveryInput,
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
    val forceLiveBaselines: Boolean = false,
)

internal data class NocturnalScoringInput(
    val session: SleepSession,
    val core: CoreRecoveryInput,
    val minHrTimestamp: Long?,
    val sessionHrvSamples: List<Float>,
    val currentHrvMean: Float,
    val muHrvHistory: List<Float>,
    val effectiveSigmaHistory: List<Float>,
    val sigmaPrior: Float,
    val frozenHrvMu: Float?,
    val frozenHrvSigma: Float?,
    val currentNocturnalRhr: Int,
    val rhrValues: List<Int>,
    val frozenRhr: Float?,
    val effectiveRhrSigma: Float?,
    val baselineRhrValue: Int,
    val prefs: UserPreferences,
    val scoringConfig: ScoringConfig,
    val stagesSuspicious: Boolean,
    val sleepModifiers: SleepModifiers,
    val frozenBaseline: Boolean,
    val isCalibrating: Boolean,
    val yesterdaySummary: DailySummary?,
    val loadScore: Float,
    val loadScoreEverydayHr: Float?,
    val summary: DailySummary,
    val hrvSigma: Float?,
)

internal data class NocturnalScoringResult(
    val sleepScore: Float?,
    val readinessScore: Float?,
    val readinessEverydayHr: Float?,
    val persistedZLnHrv: Float?,
    val persistedZRhr: Float?,
    val persistedFlags: String?,
    val sRest: Float?,
    val readinessResult: ReadinessResult,
)

internal data class ReadinessContext(
    val restorationResult: RestorationScoreAssembler.RestorationScoreResult,
    val recoveryFlags: Set<RecoveryFlag>,
    val zHrv: Float?,
    val zRhr: Float?,
    val rhrDeltaBpm: Float,
    val isLateNadir: Boolean,
    val isTimezoneJump: Boolean,
)

internal data class BaselineWindowResult(
    val rhrValues: List<Int>,
    val muHrvHistory: List<Float>,
    val sigmaHrvHistory: List<Float>,
    val validHistoricalSessionIds: List<String>,
    val validHistoricalDayCount: Int,
    val frozenHrvMu: Float?,
    val frozenHrvSigma: Float?,
    val frozenRhr: Float?,
    val frozenRhrSigma: Float?,
)

internal data class DebugScoringSnapshot(
    val targetDate: LocalDate,
    val dayMidnight: Instant,
    val dayEndMs: Long,
    val frozenBaseline: Boolean,
    val isCalibrating: Boolean,
    val hrvMuHistorySize: Int,
    val rhrValuesSize: Int,
    val sessionId: String,
    val currentHrvMean: Float?,
    val currentNocturnalRhr: Int?,
    val durationMinutes: Int,
    val loadScore: Float,
    val frozenHrvMu: Float?,
    val frozenHrvSigma: Float?,
    val activeHrvMu: Float?,
    val activeHrvSigma: Float?,
    val frozenRhr: Float?,
    val effectiveRhrSigma: Float?,
    val zLnHrv: Float?,
    val zRhr: Float?,
    val sRest: Float?,
    val sleepScore: Float?,
    val readinessScore: Float?,
    val recoveryFlags: String?,
)
