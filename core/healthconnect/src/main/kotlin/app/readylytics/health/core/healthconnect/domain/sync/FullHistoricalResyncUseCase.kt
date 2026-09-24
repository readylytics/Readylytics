package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.database.domain.scoring.TrainingReadinessProjectionRecomputeUseCase
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
import app.readylytics.health.core.model.domain.sync.*
import app.readylytics.health.core.model.domain.util.RetentionBounds
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

internal fun resolveScoringToday(
    prefs: UserPreferences,
    now: Instant,
): LocalDate = RetentionBounds.resolveHistoricalWindow(prefs, now).endDate

/**
 * Historical resync triggered from [app.readylytics.health.workers.HealthResyncWorker], covering
 * both durable entry points that share that worker's single unique WorkManager slot: the Settings
 * "Resync Health Connect data" button (full resync) and a historical-scope settings change
 * (SCORE-007's recompute-only pass, e.g. a TRIMP model/parameter or HR-zone change). Resolves how
 * far back to go from the user's data-retention setting ([RetentionBounds]) either way.
 *
 * The full-resync path delegates the heavy lifting — chunked Health Connect re-fetch +
 * walk-forward recompute — to [HealthSyncUseCase.resyncRange]; the recompute-only path skips
 * re-ingestion via [HealthSyncUseCase.recomputeRange]. Both own durable phase checkpoints so worker
 * retries can resume instead of restarting. No scoring math is altered here.
 */
@Singleton
class FullHistoricalResyncUseCase
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val healthSyncUseCase: HealthSyncUseCase,
        private val trainingReadinessProjectionRecomputeUseCase: TrainingReadinessProjectionRecomputeUseCase,
        private val clock: Clock,
        private val healthMutationCoordinator: HealthMutationCoordinator,
    ) {
        suspend fun execute(
            recomputeOnly: Boolean = false,
            rangeOverride: ScoreInvalidation.AffectedRange? = null,
            runId: String? = null,
            onProgress: ((phase: ResyncPhase, current: Int, total: Int) -> Unit)? = null,
        ): Result<Unit> {
            val range = resolveExecutionRange(recomputeOnly, rangeOverride) ?: return Result.success(Unit)
            return if (recomputeOnly) {
                healthSyncUseCase.recomputeRange(
                    startDate = range.start,
                    endDate = range.endInclusive,
                    onProgress = onProgress,
                    requestedRunId = runId,
                )
            } else {
                healthSyncUseCase.resyncRange(
                    startDate = range.start,
                    endDate = range.endInclusive,
                    onProgress = onProgress,
                    requestedRunId = runId,
                )
            }
        }

        /**
         * The inclusive range [execute] would process right now, or null when it would be a no-op.
         * [rangeOverride] only narrows a recompute-only pass -- a full resync always covers the whole
         * retention window regardless. The override is clamped to the retention window in case
         * retention shrank between when the range was computed (worker enqueue time) and now
         * (worker run time).
         */
        suspend fun resolveExecutionRange(
            recomputeOnly: Boolean,
            rangeOverride: ScoreInvalidation.AffectedRange?,
        ): ScoreInvalidation.AffectedRange? {
            val prefs = settingsRepo.userPreferences.first()
            val historicalWindow = RetentionBounds.resolveHistoricalWindow(prefs, clock.instant())
            val override = rangeOverride?.takeIf { recomputeOnly }
            val startDate = override?.start?.coerceAtLeast(historicalWindow.startDate) ?: historicalWindow.startDate
            val endDate = override?.endInclusive?.coerceAtMost(historicalWindow.endDate) ?: historicalWindow.endDate
            return if (startDate.isAfter(endDate)) null else ScoreInvalidation.AffectedRange(startDate, endDate)
        }

        /**
         * Task 4: durable, parameter-only Training Readiness recompute triggered by the Settings
         * explicit "Recalculate" action (task 5) after S/w change -- never by a normal sync/resync
         * pass. Retention-bounded like [execute], and delegates its actual read-modify-write work to
         * [TrainingReadinessProjectionRecomputeUseCase]: no Health Connect I/O, no raw ingestion, no
         * TRIMP/residual-fatigue reconstruction. It still serializes through the application
         * mutation coordinator because it reads and rewrites full
         * `daily_summaries` rows the same way [app.readylytics.health.DatabaseReadyStartupInitializer]'s
         * baseline backfill does -- a concurrent daily sync/resync updating those same rows mid-write
         * could otherwise be clobbered by (or clobber) this stale full-row projection.
         */
        suspend fun executeTrainingReadinessProjection(
            config: TrainingReadinessConfig,
            onProgress: ((current: Int, total: Int) -> Unit)? = null,
        ): Result<Unit> {
            val prefs = settingsRepo.userPreferences.first()
            val historicalWindow = RetentionBounds.resolveHistoricalWindow(prefs, clock.instant())
            return healthMutationCoordinator.withMutation {
                trainingReadinessProjectionRecomputeUseCase.execute(
                    startDate = historicalWindow.startDate,
                    endDate = historicalWindow.endDate,
                    zoneId = historicalWindow.zoneId,
                    config = config,
                    onProgress = onProgress,
                )
            }
        }
    }
