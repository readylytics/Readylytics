package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.HealthConnectWindowTimeoutException
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.model.domain.sync.ScanIdentity
import app.readylytics.health.core.model.domain.sync.ScanStagingStore
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Orchestrates one Health Connect window ingest, shared by the recent-window [DailySyncUseCase]
 * and the chunked [ResyncRangeUseCase] so both flows ingest through identical mapping/filtering.
 *
 * Phase order inside a single `withTimeout(windowBudgetMs)`:
 * 1. bulk fetch + persist of sessions and low-cardinality vitals ([BulkWindowIngestPhase]);
 * 2. heart-rate, then HRV, then steps streamed page-by-page ([DenseWindowStreamPhase], HC-001);
 * 3. staging of the bulk-read scan id sets, then the optional deletion reconcile
 *    ([WindowScanReconciler], HC-005).
 *
 * A timeout is rethrown as [HealthConnectWindowTimeoutException] (HC-002). Workouts are persisted
 * with zero metrics here; `SessionLinkReconciler.recomputeWorkouts` fills them in afterwards.
 *
 * [staging] is deliberately required, with no default: a heap-backed store would silently
 * reintroduce PERF-001 and stop `StagedDeletionReconciler` from ever seeing a scan reach
 * `COMPLETE`, so losing the `RoomScanStagingStore` Hilt binding must fail wiring, not degrade.
 * The public three-argument constructor builds the phases directly for tests and benchmarks.
 */
@Singleton
class HealthIngestionCoordinator
    @Inject
    internal constructor(
        private val bulkPhase: BulkWindowIngestPhase,
        private val densePhase: DenseWindowStreamPhase,
        private val reconciler: WindowScanReconciler,
        private val staging: ScanStagingStore,
    ) {
        constructor(
            hcRepo: HealthConnectRepository,
            healthIngestionStore: HealthIngestionStore,
            staging: ScanStagingStore,
        ) : this(
            BulkWindowIngestPhase(hcRepo, healthIngestionStore),
            DenseWindowStreamPhase(hcRepo, healthIngestionStore, staging),
            WindowScanReconciler(healthIngestionStore, staging),
            staging,
        )

        suspend fun ingestWindow(
            windowStart: Instant,
            windowEnd: Instant,
            prefs: UserPreferences,
            scanIdentity: ScanIdentity = ScanIdentities.daily(windowStart),
            resumeHrScan: Boolean = false,
            resumeHrvScan: Boolean = false,
            windowBudgetMs: Long = 3 * 60_000L,
            onProgress: ((phase: ResyncPhase, current: Int, total: Int) -> Unit)? = null,
            hrStartPageToken: String? = null,
            hrvStartPageToken: String? = null,
            onTokenUpdated: (suspend (hrToken: String?, hrvToken: String?) -> Unit)? = null,
            reconcileDeletions: Boolean = RECONCILE_DELETIONS,
        ): IngestionWindowResult {
            return try {
                ingestWindowWithinBudget(
                    IngestWindowParams(
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        prefs = prefs,
                        scanIdentity = scanIdentity,
                        resumeHrScan = resumeHrScan,
                        resumeHrvScan = resumeHrvScan,
                        windowBudgetMs = windowBudgetMs,
                        onProgress = onProgress,
                        hrStartPageToken = hrStartPageToken,
                        hrvStartPageToken = hrvStartPageToken,
                        onTokenUpdated = onTokenUpdated,
                        reconcileDeletions = reconcileDeletions,
                    ),
                )
            } catch (e: TimeoutCancellationException) {
                // Not a CancellationException from here on -- HC-002: callers (ResyncRangeUseCase)
                // must be able to tell "this window is too dense for its budget" apart from
                // cooperative cancellation, which they must never swallow.
                throw HealthConnectWindowTimeoutException(windowStart, windowEnd, e)
            }
        }

        /**
         * Fix for Task 4 review Finding 1: the daily-sync flow reuses [ScanIdentities.DAILY_RUN_ID]
         * every day with a new chunk id per invocation and -- unlike a historical run, which purges
         * its own chunk's staging right after that chunk completes -- never otherwise clears it, so
         * without this call the staging tables would grow one row-set per day, per bulk type,
         * forever. [DailySyncUseCase] calls this once its own chunk id(s) for the current run are
         * known, so every other `DAILY_SYNC` chunk -- i.e. every past day's leftovers -- is dropped.
         */
        suspend fun clearStaleDailyStaging(keepChunkIds: Set<String>) {
            staging.clearChunksOtherThan(ScanIdentities.DAILY_RUN_ID, keepChunkIds)
        }

        private suspend fun ingestWindowWithinBudget(params: IngestWindowParams): IngestionWindowResult {
            return withTimeout(params.windowBudgetMs) {
                val (rawRecords, sessionContext) =
                    bulkPhase.fetchAndPersist(
                        params.windowStart,
                        params.windowEnd,
                        params.prefs,
                        params.retryBudget,
                    )
                densePhase.streamAll(params, sessionContext)
                val scans = reconciler.stageBulkScans(params, rawRecords)
                val affectedRange =
                    if (params.reconcileDeletions) {
                        reconciler.reconcileDeletions(params, scans)
                    } else {
                        null
                    }
                IngestionWindowResult(
                    affectedRange = affectedRange,
                    completedTypes = scans.mapTo(HashSet()) { it.type },
                    completedIntervalTypes = rawRecords.completedIntervalTypes,
                )
            }
        }

        companion object {
            const val RECONCILE_DELETIONS = true
        }
    }

data class IngestionWindowResult(
    val affectedRange: ScoreInvalidation.AffectedRange?,
    val completedTypes: Set<HealthDataType> = emptySet(),
    val completedIntervalTypes: Set<String> = emptySet(),
)

internal data class IngestWindowParams(
    val windowStart: Instant,
    val windowEnd: Instant,
    val prefs: UserPreferences,
    val scanIdentity: ScanIdentity,
    val resumeHrScan: Boolean,
    val resumeHrvScan: Boolean,
    val windowBudgetMs: Long,
    val onProgress: ((phase: ResyncPhase, current: Int, total: Int) -> Unit)?,
    val hrStartPageToken: String?,
    val hrvStartPageToken: String?,
    val onTokenUpdated: (suspend (hrToken: String?, hrvToken: String?) -> Unit)?,
    val reconcileDeletions: Boolean,
    // HC-005/PERF-001: one bounded retry budget shared by every read of this window (the 8 bulk
    // BulkWindowIngestPhase.fetchBulkRecords reads plus the HR/HRV/steps paged reads), replacing the old
    // per-read + outer-window nested retryWithBackoff wrapping. Always fresh per ingestWindow call.
    val retryBudget: ReadRetryBudget = ReadRetryBudget(),
)
