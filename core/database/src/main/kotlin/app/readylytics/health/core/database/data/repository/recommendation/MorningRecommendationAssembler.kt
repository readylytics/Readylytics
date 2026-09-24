package app.readylytics.health.core.database.data.repository.recommendation

import app.readylytics.health.core.database.data.repository.ScoringDayContext
import app.readylytics.health.core.model.domain.model.SleepSession
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationSnapshot
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationState
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.model.domain.repository.SleepSessionRepository
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.scoring.domain.recommendation.ComputeWorkoutRecommendationUseCase
import app.readylytics.health.core.scoring.domain.recommendation.SelectMorningSleepSession
import app.readylytics.health.core.scoring.domain.recommendation.SelectWorkoutRecommendationExamples
import app.readylytics.health.core.scoring.domain.recommendation.WorkoutRecommendationInput
import app.readylytics.health.core.scoring.domain.scoring.CircadianWakeBaseline
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rule version stamped onto every snapshot this assembler produces.
 *
 * `internal` (not `private`) so [app.readylytics.health.core.database.data.mapper.WorkoutRecommendationCodec]
 * can validate a decoded payload against the *same* constant on read-back, rather than a
 * hand-duplicated copy that could silently drift out of sync with a future rule-version bump here.
 */
internal const val RULE_VERSION = 1

/** How far back examples are drawn from, relative to the wake time. */
private const val EXAMPLE_WINDOW_DAYS = 30L

/** States in which the evaluator actually formed a guidance opinion. */
private val AVAILABLE_STATES =
    setOf(
        WorkoutRecommendationState.REST,
        WorkoutRecommendationState.EASY,
        WorkoutRecommendationState.HARDER,
    )

/**
 * States that can be illustrated with past workouts; every other state ships no examples.
 *
 * `internal` for the same reason as [RULE_VERSION]: [app.readylytics.health.core.database.data.mapper.WorkoutRecommendationCodec]
 * validates a decoded snapshot's examples against this same set rather than a duplicated copy.
 */
internal val EXAMPLE_STATES =
    setOf(WorkoutRecommendationState.EASY, WorkoutRecommendationState.HARDER)

/**
 * Composes the morning workout guidance for one local day.
 *
 * Every step is anchored to the end of the sleep record chosen for the morning, so the snapshot for
 * a day is the same whether it is computed that morning or replayed months later during a resync:
 * the recovery inputs are bounded at the wake time, and the example window is the 30 days ending
 * there. Nothing recorded later that day — a nap, an evening workout, a fresh HRV sample — can move
 * the result.
 *
 * The decision itself is [ComputeWorkoutRecommendationUseCase]'s alone; this class only feeds it and
 * decides whether examples are worth loading.
 */
// Hilt-annotated for a future direct binding, but currently constructed by hand in
// `ScoringRepositoryImpl` (from `MorningRecommendationDependencies`) rather than injected --
// the graph has no binding for this type today.
@Singleton
class MorningRecommendationAssembler
    @Inject
    constructor(
        private val sleepSessionRepository: SleepSessionRepository,
        private val recoveryLoader: MorningRecoveryLoader,
        private val exampleLoader: WorkoutExampleLoader,
    ) {
        // Stateless pure helpers, constructed rather than injected so the graph stays free of
        // bindings for types that carry no dependencies of their own.
        private val evaluator = ComputeWorkoutRecommendationUseCase()
        private val sessionSelector = SelectMorningSleepSession()
        private val exampleSelector = SelectWorkoutRecommendationExamples()

        /**
         * [previous] is the snapshot already stored for this day, when there is one. Its source
         * session is kept across ordinary daytime appends — re-read from Room, so a corrected
         * timestamp or stage breakdown is picked up. A source that no longer exists, or that backed
         * an unavailable decision, triggers a fresh selection instead.
         *
         * Returns `null` when the morning recovery inputs could not be computed for this day (see
         * [MorningRecoveryLoader.load]). `null` is the existing "no snapshot for this day" value —
         * the day still scores and persists normally, it just carries no guidance. A computation
         * failure must never be dressed up as one of the unavailable
         * [WorkoutRecommendationState] values, which are user-facing explanations of *missing data*.
         */
        suspend fun assemble(
            context: ScoringDayContext,
            previous: WorkoutRecommendationSnapshot? = null,
            fatigueContext: WalkForwardFatigueContext? = null,
        ): WorkoutRecommendationSnapshot? {
            val history = sleepSessionRepository.loadCircadianHistory(context)
            val session = resolveMorningSession(context, history, previous)
            return if (session == null) {
                WorkoutRecommendationSnapshot(
                    ruleVersion = RULE_VERSION,
                    wakeSessionId = null,
                    wakeTimeMs = null,
                    decision = evaluator.compute(noSleepInput(context)),
                )
            } else {
                assembleForSession(context, session, history, fatigueContext)
            }
        }

        /** `null` when the recovery inputs for [session]'s morning could not be computed. */
        private suspend fun assembleForSession(
            context: ScoringDayContext,
            session: SleepSession,
            history: List<SleepSessionData>,
            fatigueContext: WalkForwardFatigueContext? = null,
        ): WorkoutRecommendationSnapshot? {
            val wakeTimeMs = session.endTime
            val recovery = recoveryLoader.load(context, session, history, fatigueContext) ?: return null

            val decision = evaluator.compute(recovery)
            val examples =
                if (decision.state in EXAMPLE_STATES) {
                    val fromMs =
                        Instant
                            .ofEpochMilli(wakeTimeMs)
                            .atZone(context.zoneId)
                            .minusDays(EXAMPLE_WINDOW_DAYS)
                            .toInstant()
                            .toEpochMilli()
                    exampleSelector.select(
                        decision.state,
                        exampleLoader.load(fromMs, wakeTimeMs, context.prefs),
                        fromMs,
                        wakeTimeMs,
                    )
                } else {
                    emptyList()
                }

            return WorkoutRecommendationSnapshot(
                ruleVersion = RULE_VERSION,
                wakeSessionId = session.id,
                wakeTimeMs = wakeTimeMs,
                decision = decision,
                examples = examples,
            )
        }

        private fun resolveMorningSession(
            context: ScoringDayContext,
            history: List<SleepSessionData>,
            previous: WorkoutRecommendationSnapshot?,
        ): SleepSession? {
            val candidates =
                history
                    .filter {
                        Instant.ofEpochMilli(it.endTime).atZone(context.zoneId).toLocalDate() == context.targetDate
                    }.map { it.toDomainSession() }
            // Only a source that backed an *available* decision is worth keeping. A snapshot in an
            // unavailable state may have named a degenerate fallback (picked with no circadian
            // baseline to measure against); pinning that would survive into a later assembly whose
            // baseline has since become resolvable, anchoring an available decision to the wrong
            // record. Re-running selection is always correct there, and cheap.
            val retained =
                previous
                    ?.takeIf { it.decision.state in AVAILABLE_STATES }
                    ?.wakeSessionId
                    ?.let { storedId -> candidates.firstOrNull { it.id == storedId } }
            return retained ?: selectByHabitualWake(context, history, candidates)
        }

        private fun selectByHabitualWake(
            context: ScoringDayContext,
            history: List<SleepSessionData>,
            candidates: List<SleepSession>,
        ): SleepSession? {
            val usualWakeMinutes =
                CircadianWakeBaseline
                    .resolve(
                        sessions = history.filter { it.endTime < context.dayMidnightMs },
                        baselineCount = context.prefs.consistencyBaselineDays,
                        zone = context.zoneId,
                    )?.medianWakeMinutes
            // Without a habitual wake time there is nothing to measure distance against, and
            // substituting a default clock time is forbidden. The snapshot still names a source so
            // the unavailable state can be explained, chosen by the same deterministic tiebreak the
            // distance ordering falls back on.
            return if (usualWakeMinutes == null) {
                candidates.minWithOrNull(compareBy({ it.endTime }, { it.id }))
            } else {
                sessionSelector.select(candidates, context.targetDate, context.zoneId, usualWakeMinutes)
            }
        }

        private fun noSleepInput(context: ScoringDayContext): WorkoutRecommendationInput =
            WorkoutRecommendationInput(
                hasSleep = false,
                nightlyHrv = null,
                isCalibrating = false,
                hasCircadianBaseline = false,
                zLnHrv = null,
                lowHrvBound = null,
                highHrvBound = null,
                sleepScore = null,
                residualFatigue = null,
                fatigueGain = context.prefs.residualFatigueGain,
                recoveryFlags = emptySet(),
            )
    }
