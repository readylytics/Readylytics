package app.readylytics.health.data.backup

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.mapper.WorkoutRecommendationCodec
import app.readylytics.health.core.model.domain.util.RetentionBounds
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.workers.WorkerScheduler
import app.readylytics.health.data.preferences.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 5: after a [LocalRestoreManager] restore commits (and, when preferences restore itself
 * succeeds, after that too -- see the caller), decides whether the restored `daily_summaries` rows
 * need a local workout-recommendation backfill.
 *
 * Deliberately checked against the restored rows themselves via [WorkoutRecommendationCodec] --
 * never against the restored `scoringVersion` preference, which a backup can carry incorrectly
 * (e.g. it predates a rule-version bump the restored `scoringVersion` doesn't reflect). This is a
 * genuine "is the data there" check, not a "does the label claim it's there" check.
 *
 * The test is *no* retained row carries a payload, i.e. the restored database predates the feature
 * entirely -- deliberately not "some row is missing one". A per-row test cannot distinguish "never
 * computed" from "computed and legitimately produced nothing" (a day whose morning sleep-metrics
 * pass fails always will, for the same stored data), so it would make every restore of such a
 * database schedule another full recompute that can never change the outcome. The narrower test
 * costs only the partially-covered-restore case, and that one still converges: a partially covered
 * database was left behind by an interrupted backfill, so its `scoringVersion` is still stale and
 * [app.readylytics.health.DatabaseReadyStartupInitializer]'s version gate re-enqueues the same
 * recompute on the next launch.
 *
 * Bounded to *retained* rows (per the brief's "retained-summary coverage checks"): a row outside
 * `RetentionBounds.resolveRetentionCutoffMs` can never be repaired by the retention-bounded
 * recompute this schedules anyway, so it must never be the reason a restore triggers one. Reads
 * preferences fresh at call time so it reflects whatever this restore actually left behind --
 * newly restored preferences on the success path, or the untouched pre-restore preferences on the
 * path where preferences restore itself failed. An empty (or fully outside-retention) backup has
 * nothing to backfill.
 *
 * Best-effort and never fails the restore that already succeeded: worst case, the next launch's
 * startup gate or a later manual resync converges it through the same
 * [app.readylytics.health.core.model.data.preferences.SettingsDefaults.CURRENT_SCORING_VERSION]
 * marker as the normal upgrade path.
 */
@Singleton
class RestoreRecommendationCoverageChecker
    @Inject
    constructor(
        private val healthDatabase: HealthDatabase,
        private val settingsRepository: SettingsRepository,
        private val workerScheduler: WorkerScheduler,
        private val clock: Clock,
    ) {
        suspend fun scheduleRecomputeIfIncomplete() {
            try {
                val prefs = settingsRepository.userPreferences.first()
                val retentionCutoffMs = RetentionBounds.resolveRetentionCutoffMs(prefs, Instant.now(clock))
                val summaries =
                    retentionCutoffMs?.let { healthDatabase.dailySummaryDao().getSince(it) }
                        ?: healthDatabase.dailySummaryDao().getAllSummaries()
                // "None of the retained rows carry a payload", not "any row is missing one". A
                // single missing day is not evidence the backup predates the feature, and it is not
                // repairable: `MorningRecommendationAssembler` legitimately yields no snapshot for a
                // day whose morning sleep-metrics pass fails (see `MorningRecoveryLoader`), and that
                // failure is deterministic for the same stored data. Under an "any" test such a day
                // would make every future restore of this database schedule another full
                // recompute-only pass that can never change the answer.
                val uncovered =
                    summaries.isNotEmpty() &&
                        summaries.none { WorkoutRecommendationCodec.decode(it.workoutRecommendationJson) != null }
                if (uncovered) {
                    workerScheduler.scheduleResyncWorker(recomputeOnly = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logE(TAG, e) {
                    "Failed to check restored recommendation coverage; a later resync still converges"
                }
            }
        }

        private companion object {
            const val TAG = "RestoreRecommendationCoverageChecker"
        }
    }
