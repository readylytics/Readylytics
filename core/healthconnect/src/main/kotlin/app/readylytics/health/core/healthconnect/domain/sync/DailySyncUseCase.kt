package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.scoring.domain.scoring.components.Phase

import app.readylytics.health.core.database.domain.sync.DailyRecomputeSupport
import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.core.model.domain.repository.HealthConnectWindowTimeoutException
import app.readylytics.health.core.model.domain.repository.WalDiagnostics
import app.readylytics.health.core.scoring.domain.scoring.RasSourceModeBootstrapUseCase
import app.readylytics.health.core.model.domain.sync.*
import app.readylytics.health.core.model.domain.sync.link.SessionLinkReconciler
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.domain.util.logI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Foreground daily sync / recalculation over a recent window. Re-reads the recent Health Connect
 * window, reconciles HR/HRV session linkage, then walk-forward recomputes each day's scores via the
 * unchanged scoring-engine formulas. Serialized against the historical resync by the shared
 * `syncMutex` owned by [HealthSyncUseCase] — callers must invoke this under that lock.
 */
@Singleton
class DailySyncUseCase
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val sessionLinkReconciler: SessionLinkReconciler,
        private val rasSourceModeBootstrapUseCase: RasSourceModeBootstrapUseCase,
        private val changeSynchronizer: HealthChangeSynchronizer,
        private val healthIngestionStore: HealthIngestionStore,
        private val ingestionCoordinator: HealthIngestionCoordinator,
        private val stepCountFetcher: StepCountFetcher,
        private val recomputeSupport: DailyRecomputeSupport,
        private val walDiagnostics: WalDiagnostics,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        private val clock: Clock,
    ) {
        private suspend fun ingestSegment(
            startMs: Instant,
            endMs: Instant,
            prefs: UserPreferences,
            windowBudgetMs: Long,
            onProgress: ((phase: ResyncPhase, current: Int, total: Int) -> Unit)?,
        ) {
            try {
                ingestionCoordinator.ingestWindow(
                    startMs,
                    endMs,
                    prefs,
                    windowBudgetMs = windowBudgetMs,
                    onProgress = onProgress,
                )
            } catch (e: HealthConnectWindowTimeoutException) {
                logE("DailySyncUseCase") {
                    "Ingest segment $startMs..$endMs timed out; retrying with extended budget"
                }
                ingestionCoordinator.ingestWindow(
                    startMs,
                    endMs,
                    prefs,
                    windowBudgetMs = EXTENDED_DAILY_INGEST_BUDGET_MS,
                    onProgress = onProgress,
                )
            }
        }

        /**
         * @param onProgress optional reactive hook invoked as the walk-forward recompute advances,
         *   reporting (phase, current, total) so the UI can surface progress instead of a silent
         *   spinner. Invoked off the main thread. Unlike the historical resync, daily sync's HR/HRV
         *   page ingest has no real page count up front, so it reports [ResyncPhase.INGEST] with an
         *   indeterminate `total = 0`; it also reports [ResyncPhase.RECONCILE] (indeterminate, once)
         *   before finally reporting determinate [ResyncPhase.RECOMPUTE] progress across the
         *   walk-forward.
         */
        suspend fun run(
            windowDays: Int,
            onProgress: ((phase: ResyncPhase, current: Int, total: Int) -> Unit)?,
        ): Result<Unit> =
            withContext(ioDispatcher) {
                try {
                    logI("DailySyncUseCase") { "Starting sync (window=$windowDays days)..." }
                    // Migrate any legacy global "primary device" into the per-data-type map.
                    settingsRepo.migrateDeviceSelectionIfNeeded()
                    // One-time bootstrap of rasSourceMode for existing users (no-op after first run).
                    rasSourceModeBootstrapUseCase()
                    val initialPrefs = settingsRepo.userPreferences.first()

                    recomputeSupport.refreshAutoMaxHr(initialPrefs)
                    // Re-fetch preferences in case they were updated by refreshAutoMaxHr
                    val prefs = settingsRepo.userPreferences.first()

                    // Resolve day boundaries via the stored scoring timezone (falls back to the
                    // device zone when un-seeded) so the recompute window stays aligned with the
                    // scoring engine even if the device timezone changes.
                    val zoneId = prefs.scoringZone()
                    val today = java.time.LocalDate.now(clock.withZone(zoneId))

                    val outcome = changeSynchronizer.applyPendingChanges()
                    if (outcome.requiresFullResync) {
                        return@withContext Result.failure(
                            "Requires historical resync",
                            "REQUIRES_HISTORICAL_RESYNC",
                        )
                    }

                    val standardDays = (0 until windowDays).map { today.minusDays(it.toLong()) }.toSet()
                    val standardOldest = standardDays.minOrNull() ?: today

                    // HC changes can legitimately touch recent past days (last night's sleep is
                    // dated yesterday; HR/HRV backfilled for the prior day). Absorb those inline by
                    // widening the walk-forward down to the earliest recent affected day - contiguous
                    // to today so frozen baselines and acute/chronic averages propagate correctly.
                    // Only changes older than the inline bound (which would make one foreground HC
                    // read + recompute too large) escalate to the durable historical resync.
                    val inlineFloor = today.minusDays(MAX_INLINE_RECOMPUTE_DAYS.toLong())
                    val outOfWindowAffected = outcome.affectedDates.filter { it.isBefore(standardOldest) }
                    val requiresHistoricalResync = outOfWindowAffected.any { it.isBefore(inlineFloor) }
                    val oldestTargetDay =
                        if (requiresHistoricalResync) {
                            standardOldest
                        } else {
                            outOfWindowAffected.minOrNull() ?: standardOldest
                        }

                    val windowEnd = today.plusDays(1).atStartOfDay(zoneId).toInstant()
                    val todayMidnight = today.atStartOfDay(zoneId).toInstant()

                    // Overnight sleep sessions cross midnight: a session ending inside the
                    // recompute range may begin the previous evening. Reach the raw-sample fetch
                    // back one extra day from the earliest target day so pre-midnight HR/HRV
                    // samples of the earliest in-range night are captured.
                    val ingestStart = oldestTargetDay.minusDays(1).atStartOfDay(zoneId).toInstant()

                    val ingestStartedAt = System.currentTimeMillis()
                    // B′: split the recent-window ingest into today's segment and the overnight
                    // back-day reach-back so each gets its own read budget and the user-facing day
                    // completes (and scores) before the denser back-day. Both segments stay inside
                    // the original [ingestStart, windowEnd) range, so the current-day-only contract
                    // is unchanged; the full-range reconcile below re-derives session links across
                    // the segment boundary (chunk-independent determinism).
                    try {
                        ingestSegment(
                            todayMidnight,
                            windowEnd,
                            prefs,
                            windowBudgetMs = DEFAULT_DAILY_INGEST_BUDGET_MS,
                            onProgress = onProgress,
                        )
                    } catch (e: HealthConnectWindowTimeoutException) {
                        logE("DailySyncUseCase", e) { "Today's ingest deferred: window too dense" }
                        return@withContext Result.failure(
                            "Today's Health Connect data too dense for foreground sync",
                            "DEFERRED_DAILY_SYNC",
                        )
                    }
                    try {
                        ingestSegment(
                            ingestStart,
                            todayMidnight,
                            prefs,
                            windowBudgetMs = BACK_DAY_INGEST_BUDGET_MS,
                            onProgress = onProgress,
                        )
                    } catch (e: HealthConnectWindowTimeoutException) {
                        logE("DailySyncUseCase", e) {
                            "Back-day ingest deferred; continuing with today's data"
                        }
                    }
                    logD("HealthSync.Phase") {
                        "INGEST completed in ${System.currentTimeMillis() - ingestStartedAt}ms"
                    }

                    onProgress?.invoke(ResyncPhase.RECONCILE, 0, 0)
                    val reconcileStartedAt = System.currentTimeMillis()
                    sessionLinkReconciler.reconcile(
                        startMs = ingestStart.toEpochMilli(),
                        endMs = windowEnd.toEpochMilli() - 1,
                        zoneThresholds =
                            app.readylytics.health.core.model.domain.heartrate.ZoneThresholds.zoneThresholds(
                                prefs.zone1MinBpm,
                                prefs.zone1MaxBpm,
                                prefs.zone2MaxBpm,
                                prefs.zone3MaxBpm,
                                prefs.zone4MaxBpm,
                            ),
                    )
                    logD("HealthSync.Phase") {
                        "RECONCILE completed in ${System.currentTimeMillis() - reconcileStartedAt}ms"
                    }

                    val stepsDevice =
                        prefs.deviceByDataType[HealthDataType.STEPS.name]?.takeIf { it.isNotBlank() }
                    val totalDays = ChronoUnit.DAYS.between(oldestTargetDay, today).toInt() + 1
                    val stepsMap = stepCountFetcher.fetchWindow(today, totalDays, zoneId, stepsDevice)

                    // PERF-002/WP-20/WP-22 on the daily path: fetch the workout-only/everyday-HR
                    // TRIMP series and the RHR/HRV baseline sleep-session window ONCE for the whole
                    // walk-forward, instead of every recomputed day independently re-querying its
                    // own 84-/56-day lookback. Same batched-once shape as stepsMap above, and the
                    // same contexts ResyncRangeUseCase already builds. Built over the *widened*
                    // [oldestTargetDay, today] range so a day absorbed from outcome.affectedDates
                    // sees a complete series.
                    val trimpContext =
                        recomputeSupport.buildWalkForwardTrimpContext(oldestTargetDay, today, zoneId)
                    val baselineContext =
                        recomputeSupport.buildWalkForwardBaselineContext(oldestTargetDay, today, zoneId)

                    var processedDays = 0
                    onProgress?.invoke(ResyncPhase.RECOMPUTE, processedDays, totalDays)

                    var successCount = 0
                    var failureCount = 0

                    // F7: one transaction for the frozen-baseline clear plus the whole walk-forward,
                    // so a routine sync produces a single daily_summaries/workout_records
                    // invalidation round instead of one per synced day. Everything that touches
                    // Health Connect (ingestWindow, reconcile, fetchWindow) has already completed
                    // above -- keep it that way. A per-day Result.Failure does not abort the
                    // transaction: recomputeDay catches and returns rather than rethrowing, so the
                    // existing log-and-continue + SYNC_PARTIAL_FAILURE semantics are unchanged.
                    // Cancellation does roll the window back, which is fine: the next sync redoes
                    // the same idempotent range.
                    val recomputeStartedAt = System.currentTimeMillis()
                    recomputeSupport.inRecomputeTransaction {
                        healthIngestionStore.clearFrozenBaselines(oldestTargetDay, today.plusDays(1), zoneId)

                        var dayToScore = oldestTargetDay
                        while (!dayToScore.isAfter(today)) {
                            ensureActive()
                            val steps = stepsMap[dayToScore]
                            val result =
                                recomputeSupport.recomputeDay(
                                    dayToScore,
                                    steps,
                                    prefs,
                                    trimpContext,
                                    baselineContext,
                                )

                            when (result) {
                                is Result.Success -> {
                                    successCount++
                                    logD("DailySyncUseCase") { "Day $dayToScore: SUCCESS" }
                                }
                                is Result.Failure -> {
                                    failureCount++
                                    logI("DailySyncUseCase") { "Day $dayToScore: FAILED - ${result.reason}" }
                                }
                            }
                            processedDays++
                            onProgress?.invoke(ResyncPhase.RECOMPUTE, processedDays, totalDays)
                            dayToScore = dayToScore.plusDays(1)
                            yield()
                        }
                    }
                    logD("HealthSync.Phase") {
                        "RECOMPUTE completed in ${System.currentTimeMillis() - recomputeStartedAt}ms"
                    }

                    logI("DailySyncUseCase") {
                        "Sync complete: $successCount succeeded, $failureCount failed"
                    }
                    logD("HealthSync.Wal") { "WAL file size: ${walDiagnostics.walFileSizeInfo()}" }
                    if (failureCount > 0) {
                        return@withContext Result.failure(
                            "One or more daily summaries failed",
                            "SYNC_PARTIAL_FAILURE",
                        )
                    }
                    if (!requiresHistoricalResync) {
                        changeSynchronizer.commitTokens(outcome.nextTokens)
                    }
                    settingsRepo.updateLastSyncTimestamp(System.currentTimeMillis())
                    if (requiresHistoricalResync) {
                        Result.failure(
                            "Requires historical resync",
                            "REQUIRES_HISTORICAL_RESYNC",
                        )
                    } else {
                        Result.success(Unit)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HealthConnectPermissionRevokedException) {
                    // Rethrow (rather than flattening to SYNC_ERROR below) so ForegroundSyncController
                    // can route the user to the permission-recovery flow instead of a generic failure.
                    logE("DailySyncUseCase") { "Sync stopped by Health Connect permission failure: ${e.message}" }
                    throw e
                } catch (e: Exception) {
                    logE("DailySyncUseCase", e) { "Sync failed" }
                    Result.failure("Sync failed", "SYNC_ERROR")
                }
            }
    }
