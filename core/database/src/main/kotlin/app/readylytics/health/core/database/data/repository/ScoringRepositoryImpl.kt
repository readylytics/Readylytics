package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.model.data.preferences.scoringZone
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.ReadinessResult
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.ScoringRepository
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.model.domain.repository.WalkForwardContexts
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.repository.WalkForwardRasWindow
import app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.model.domain.repository.WalkForwardVo2MaxContext
import app.readylytics.health.core.model.domain.scoring.DayAssembly
import app.readylytics.health.core.model.domain.scoring.DayAssemblyUnavailableReason
import app.readylytics.health.core.model.domain.scoring.summaryOrNull
import app.readylytics.health.core.model.domain.sync.ScoringRunContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates per-day scoring: serializes calculations, delegates day assembly to [DayAssembler]
 * and walk-forward prefetching to [WalkForwardContextLoader], and owns the persist-then-commit
 * ordering. Every collaborator is Hilt-injected.
 */
@Singleton
class ScoringRepositoryImpl
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val dayAssembler: DayAssembler,
        private val dataLoader: ScoringDayDataLoader,
        private val residualFatigueComputer: ResidualFatigueComputer,
        private val walkForwardContextLoader: WalkForwardContextLoader,
        runtime: ScoringRuntime,
    ) : ScoringRepository {
        private val defaultDispatcher = runtime.defaultDispatcher
        private val clock = runtime.clock

        // Every multi-day caller supplies one immutable ScoringRunContext. Single-day callers are
        // anchored here with the injected clock before any day-scoped loader is invoked.
        private val calculationMutex = Mutex()

        override suspend fun computeAndPersistDailySummary(
            targetDate: LocalDate,
            steps: Long?,
            prefs: UserPreferences?,
            contexts: WalkForwardContexts,
            runContext: ScoringRunContext?,
        ) = calculationMutex.withLock {
            val resolvedPrefs = prefs ?: settingsRepo.userPreferences.first()
            val resolvedRunContext = runContext ?: ScoringRunContext.capture(resolvedPrefs, clock.instant())
            require(resolvedRunContext.zoneId == resolvedPrefs.scoringZone()) {
                "Scoring run context zone must match the preferences snapshot"
            }
            val zoneId = resolvedRunContext.zoneId
            val publication = dataLoader.captureDayPublication(targetDate)
            val computed = dayAssembler.computeDay(targetDate, resolvedPrefs, contexts, resolvedRunContext)
            val assembly = computed.assembly.withStepCount(steps)
            val persisted =
                dataLoader.persistDayAssembly(assembly, zoneId, computed.workouts, computed.workoutUpdates, publication)
            if (!persisted) {
                // C3 (WP-13): Unavailable is a deliberate no-op -- nothing was written, so the
                // walk-forward context below must not be committed either (see
                // ComputedDay.commitWalkForwardContexts).
                throw DayAssemblyUnavailableException(assembly.unavailableReasonOrDefault())
            }
            // Walk-forward context mutations are only ever applied after the write above commits
            // -- never speculatively before or during it.
            computed.commitWalkForwardContexts()
        }

        override suspend fun fetchWalkForwardTrimpContext(
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
        ): WalkForwardTrimpContext = walkForwardContextLoader.trimp(startDate, endDate, zoneId)

        override suspend fun fetchWalkForwardBaselineContext(
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
        ): WalkForwardBaselineContext = walkForwardContextLoader.baseline(startDate, endDate, zoneId)

        override suspend fun fetchWalkForwardFatigueContext(
            startDate: LocalDate,
            endDate: LocalDate,
            prefs: UserPreferences,
            runContext: ScoringRunContext,
        ): WalkForwardFatigueContext {
            require(runContext.zoneId == prefs.scoringZone()) {
                "Scoring run context zone must match the preferences snapshot"
            }
            return walkForwardContextLoader.fatigue(startDate, prefs, runContext)
        }

        override suspend fun fetchWalkForwardRasContext(
            startDate: LocalDate,
            zoneId: ZoneId,
        ): WalkForwardRasWindow = walkForwardContextLoader.ras(startDate, zoneId)

        override suspend fun fetchWalkForwardVo2MaxContext(
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
        ): WalkForwardVo2MaxContext = walkForwardContextLoader.vo2Max(startDate, endDate, zoneId)

        override suspend fun computeDailySummary(targetDate: LocalDate): DailySummary {
            val prefs = settingsRepo.userPreferences.first()
            val runContext = ScoringRunContext.capture(prefs, clock.instant())
            return calculationMutex.withLock {
                val computed = dayAssembler.computeDay(targetDate, prefs, WalkForwardContexts(), runContext)
                val summary =
                    computed.assembly.summaryOrNull()
                        ?: throw DayAssemblyUnavailableException(computed.assembly.unavailableReasonOrDefault())
                computed.commitWalkForwardContexts()
                summary
            }
        }

        // Deliberately does not take calculationMutex, unlike its siblings: holding it would block
        // the dashboard card for the whole duration of a full historical resync. This path is
        // read-only and non-persisting, so the worst case is reading mid-resync state, which the
        // next minute's recomputation corrects.
        override suspend fun computeCurrentResidualFatigue(nowMs: Long): Float? =
            withContext(defaultDispatcher) {
                residualFatigueComputer.computeLive(nowMs, settingsRepo.userPreferences.first())
            }

        override suspend fun persist(summary: DailySummary) {
            dataLoader.persistDailySummary(summary, settingsRepo.userPreferences.first().scoringZone())
        }

        override suspend fun toReadinessResult(summary: DailySummary): ReadinessResult = summary.readinessResult
    }

/** Thrown when a [DayAssembly.Unavailable] reaches a caller that requires a genuine candidate. */
internal class DayAssemblyUnavailableException(
    reason: String,
) : IllegalStateException("Day assembly unavailable: $reason")

private fun DayAssembly.unavailableReasonOrDefault(): String =
    (this as? DayAssembly.Unavailable)?.reason ?: DayAssemblyUnavailableReason.FINAL_ASSEMBLY_FAILED

private fun DayAssembly.withStepCount(steps: Long?): DayAssembly =
    when (this) {
        is DayAssembly.Computed -> copy(summary = summary.withStepCount(steps))
        is DayAssembly.Absent -> copy(summary = summary.withStepCount(steps))
        is DayAssembly.Unavailable -> this
    }

/** A null [steps] means no fresh count for the day; the stored value is preserved. */
private fun DailySummary.withStepCount(steps: Long?): DailySummary =
    if (steps == null) {
        this
    } else {
        copy(stepCount = steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

