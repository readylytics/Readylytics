package app.readylytics.health.core.database.data.repository.recommendation

import app.readylytics.health.core.database.data.repository.CalibrationGate
import app.readylytics.health.core.database.data.repository.ResidualFatigueComputer
import app.readylytics.health.core.database.data.repository.ScoringDayContext
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.model.SleepSession
import app.readylytics.health.core.model.domain.preferences.PhysiologyProfile
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.model.domain.repository.SleepSessionRepository
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.model.domain.util.logW
import app.readylytics.health.core.scoring.domain.recommendation.WorkoutRecommendationInput
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.CircadianWakeBaseline
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory
import app.readylytics.health.core.scoring.domain.scoring.SleepMetricsRequest
import app.readylytics.health.core.scoring.domain.scoring.components.EmergencyFlagThresholds
import app.readylytics.health.core.scoring.domain.scoring.sleep.CoreRecoveryInput
import app.readylytics.health.core.scoring.domain.scoring.sleep.CurrentNightHrvResolver
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Sleep history the circadian baseline needs, expressed in milliseconds. */
internal const val CIRCADIAN_HISTORY_WINDOW_MS =
    ScoringConstants.CIRCADIAN_CONSISTENCY_WINDOW_DAYS.toLong() * 24L * 60L * 60L * 1000L

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * The sleep history the morning assembly needs for one day: everything starting within
 * [CIRCADIAN_HISTORY_WINDOW_MS] of the day's end, and ending by that same instant.
 *
 * Bounded on purpose. `getSince` has no upper bound, so replaying a day from years ago during a
 * historical backfill would materialize every sleep row from the window start through *today* --
 * quadratic in retained history for a window that is only ever ~60 days wide. Every consumer
 * downstream already discards anything ending after the day (`endTime <= wakeTimeMs`, or an
 * end-of-day local-date match), so the upper bound removes only rows that were being thrown away.
 */
internal suspend fun SleepSessionRepository.loadCircadianHistory(context: ScoringDayContext): List<SleepSessionData> =
    getInRange(
        fromMs = context.nextDayMidnightMs - CIRCADIAN_HISTORY_WINDOW_MS,
        toMs = context.nextDayMidnightMs,
    )

/** Domain view of a stored sleep row, for the scoring pass that only speaks [SleepSession]. */
internal fun SleepSessionData.toDomainSession(): SleepSession =
    SleepSession(
        id = id,
        startTime = startTime,
        endTime = endTime,
        durationMinutes = durationMinutes,
        efficiency = efficiency,
        deepSleepMinutes = deepSleepMinutes,
        remSleepMinutes = remSleepMinutes,
        lightSleepMinutes = lightSleepMinutes,
        awakeMinutes = awakeMinutes,
        sleepScore = sleepScore,
        startZoneOffsetSeconds = startZoneOffsetSeconds,
        endZoneOffsetSeconds = endZoneOffsetSeconds,
        deviceName = deviceName,
    )

/**
 * Resolves every recovery input the workout-recommendation evaluator needs, as of the moment the
 * user woke up.
 *
 * This is the impure edge: it reads Room and re-runs the existing sleep-scoring pass rather than
 * copying the completed day's stored `zLnHrv`, sleep score, and flags, because those are computed
 * at next-day midnight and a nap recorded later the same day can still move them. The re-run is the
 * *same* [ComputeSleepMetricsUseCase] with the same formulas — only its request bounds differ (see
 * [SleepMetricsRequest]).
 */
// Hilt-annotated for a future direct binding, but currently constructed by hand in
// `ScoringRepositoryImpl` (from `MorningRecommendationDependencies`) rather than injected --
// the graph has no binding for this type today.
@Singleton
class MorningRecoveryLoader
    @Inject
    constructor(
        private val sleepSessionRepository: SleepSessionRepository,
        private val computeSleepMetricsUseCase: ComputeSleepMetricsUseCase,
        private val hrvResolver: CurrentNightHrvResolver,
        private val residualFatigueComputer: ResidualFatigueComputer,
        private val scoringConfigFactory: ScoringConfigFactory,
        private val baselineComputer: BaselineComputer,
        private val calibrationGate: CalibrationGate,
    ) {
        /**
         * [sessions] is the caller's already-loaded sleep history covering at least
         * [CIRCADIAN_HISTORY_WINDOW_MS] before the day; it is re-derived from Room when absent.
         *
         * Returns `null` when the morning sleep-metrics pass could not be computed for this day —
         * see [computeMorningSleepMetrics]. The caller must degrade rather than substitute a
         * fabricated input: an unavailable *state* would be a false explanation for what is really
         * a computation failure.
         */
        suspend fun load(
            context: ScoringDayContext,
            session: SleepSession,
            sessions: List<SleepSessionData>? = null,
        ): WorkoutRecommendationInput? {
            val prefs = context.prefs
            val wakeTimeMs = session.endTime
            val history =
                sessions ?: sleepSessionRepository.loadCircadianHistory(context)

            // The habitual wake time must come from days that are already over, so the day being
            // scored cannot define its own "usual".
            val priorHistory = history.filter { it.endTime < context.dayMidnightMs }
            val hasCircadianBaseline =
                CircadianWakeBaseline.resolve(priorHistory, prefs.consistencyBaselineDays, context.zoneId) != null

            val boundedSessions = history.filter { it.endTime <= wakeTimeMs }.map { it.toDomainSession() }
            val morningSummary = computeMorningSleepMetrics(context, session, boundedSessions) ?: return null
            val hrv = hrvResolver.resolve(session, setOf(session.id))
            val thresholds = emergencyThresholds(context)
            // `ComputeSleepMetricsUseCase` never stamps `isCalibrating` on the summary it returns
            // (it passes the caller's value through), so it has to be resolved here — through the
            // same gate the daily pipeline uses. The gate's cumulative prior-days count is
            // day-granular (through yesterday), so it needs no wake-time bound; today's own
            // eligibility is `hasSession` below, not `boundedSessions`.
            val isCalibrating =
                !calibrationGate.isCalibrated(
                    context = context,
                    hasSession = true,
                )

            return WorkoutRecommendationInput(
                hasSleep = true,
                nightlyHrv = hrv.mean,
                isCalibrating = isCalibrating,
                hasCircadianBaseline = hasCircadianBaseline,
                zLnHrv = morningSummary.zLnHrv,
                lowHrvBound = thresholds.illnessZHrvThreshold,
                highHrvBound = thresholds.strongRecoveryZHrvThreshold,
                sleepScore = morningSummary.sleepScore,
                residualFatigue = residualFatigueComputer.computeAt(wakeTimeMs, prefs),
                fatigueGain = prefs.residualFatigueGain,
                recoveryFlags = morningSummary.recoveryFlags,
            )
        }

        /**
         * `null` when the pass failed.
         *
         * A failure here is an operational failure of *this day's recommendation*, never a reason to
         * abandon the day's scoring: the recommendation is assembled after the readiness pipeline has
         * already produced a complete [DailySummary], and `ScoringRepositoryImpl` persists that
         * summary. Throwing instead would abort `computeAndPersistDailySummary`, which during the
         * scoring-version 4->5 backfill means one deterministically-failing historical day fails the
         * whole retained-history recompute pass -- `Result.retry()`, no version bump, and the startup
         * gate re-enqueues the same doomed pass on every launch forever.
         *
         * [ComputeSleepMetricsUseCase] already rethrows `CancellationException` and logs the cause at
         * ERROR before returning a failure, so cancellation still propagates and the underlying stack
         * trace is not lost; this logs the degradation itself at WARN.
         */
        private suspend fun computeMorningSleepMetrics(
            context: ScoringDayContext,
            session: SleepSession,
            boundedSessions: List<SleepSession>,
        ): DailySummary? {
            val wakeTimeMs = session.endTime
            val baseSummary = context.dailySummary ?: DailySummary(date = context.targetDate)
            // This morning-anchored path has no core/nap cluster context of its own -- [session] is
            // already the single, wake-time-bounded record the caller resolved, so it is treated as
            // its own (single-segment) core. [boundedSessions] mirrors the pre-C4 "most recent prior
            // session" lookup this path always used (it is already excluded via `it.id != session.id`
            // rather than being scoped to a canonical core cluster, unlike the daily pipeline's
            // `ReadinessSummaryCoordinator.findPreviousCoreEndZoneOffsetSeconds`).
            val previousCoreEndZoneOffsetSeconds =
                boundedSessions
                    .filter { it.id != session.id }
                    .maxByOrNull { it.endTime }
                    ?.endZoneOffsetSeconds
            val core =
                CoreRecoveryInput.fromSingleSession(
                    sessionId = session.id,
                    startTimeMs = session.startTime,
                    endTimeMs = session.endTime,
                    coreSleepDurationMinutes = session.durationMinutes,
                    endZoneOffsetSeconds = session.endZoneOffsetSeconds,
                    previousCoreEndZoneOffsetSeconds = previousCoreEndZoneOffsetSeconds,
                )
            val request =
                SleepMetricsRequest(
                    session = session,
                    core = core,
                    dayMidnight = Instant.ofEpochMilli(context.dayMidnightMs),
                    targetDate = context.targetDate,
                    prefs = context.prefs,
                    summary = baseSummary,
                    loadScore = baseSummary.loadScoreWorkoutOnly ?: 0f,
                    loadScoreEverydayHr = baseSummary.loadScoreEverydayHr,
                    zoneId = context.zoneId,
                    rhrBaselineValue = morningRhrBaseline(context, wakeTimeMs, boundedSessions),
                    dayEndMs = wakeTimeMs,
                    currentSessionIds = setOf(session.id),
                    prefetchedSessions = boundedSessions,
                    forceLiveBaselines = true,
                    // Both the frozen snapshot and `context.initialBaselines` were resolved with a
                    // next-day-midnight window; honouring them would make a day score differently
                    // once it froze. See `SleepMetricsRequest.forceLiveBaselines`.
                )
            // A failed pass is an operational failure, not "no recovery data" — so the day gets no
            // snapshot at all rather than a fabricated unavailable state.
            return when (val result = computeSleepMetricsUseCase(request)) {
                is Result.Success -> result.data
                is Result.Failure -> {
                    logW(TAG) {
                        "Morning sleep metrics failed for ${context.targetDate} " +
                            "(${result.code} ${result.reason}); day scores without a recommendation"
                    }
                    null
                }
            }
        }

        /**
         * The day's RHR baseline re-derived with the wake time as its upper bound.
         *
         * `ScoringDayContext.initialBaselines.rhrBaselineValue` cannot be reused: for an unfrozen
         * day it comes from `computeAdaptiveBaselineRhrBpmBetween(dayMidnight, nextDayMidnight)`,
         * a window that explicitly includes the day's own later sessions — so an afternoon nap
         * would move `baselineRhrValue`, `zRhr`, and with it `ILLNESS_ONSET`. Precedence mirrors
         * `ResolveDailyBaselinesUseCase`: an explicit user override wins, then the computed
         * baseline, then the shipped default.
         */
        private suspend fun morningRhrBaseline(
            context: ScoringDayContext,
            wakeTimeMs: Long,
            boundedSessions: List<SleepSession>,
        ): Float =
            context.prefs.rhrBaselineOverride
                ?: baselineComputer
                    .computeAdaptiveBaselineRhrBpmBetween(
                        fromMs = context.dayMidnightMs,
                        toMs = wakeTimeMs,
                        percentile = context.prefs.restingHrPercentile,
                        zoneId = context.zoneId,
                        sleepDayPolicy = context.sleepDayPolicy,
                        prefetchedSessions = boundedSessions,
                        ignoreFrozenSnapshot = true,
                    )?.takeIf { it > 0f }
                ?: ScoringConstants.DEFAULT_RHR_BPM

        /**
         * HRV deviation bounds for the day, taken from the profile the day was *scored* with. A user
         * who switches profile later must not retroactively change an already-frozen day's bounds.
         */
        private fun emergencyThresholds(context: ScoringDayContext): EmergencyFlagThresholds {
            val frozenProfile =
                context.dailySummary?.snapshotProfile?.let { name ->
                    runCatching { enumValueOf<PhysiologyProfile>(name.uppercase()) }.getOrNull()
                }
            if (frozenProfile == null || frozenProfile == context.prefs.physiologyProfile) {
                return context.scoringConfig.emergencyFlags
            }
            return scoringConfigFactory
                .build(
                    userPreferences = context.prefs.copy(physiologyProfile = frozenProfile),
                    installDate = LocalDate.ofEpochDay(context.prefs.installDate / MILLIS_PER_DAY),
                    currentDate = context.targetDate,
                ).emergencyFlags
        }

        private companion object {
            const val TAG = "MorningRecoveryLoader"
        }
    }
