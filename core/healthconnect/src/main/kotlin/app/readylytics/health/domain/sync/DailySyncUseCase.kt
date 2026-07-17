package app.readylytics.health.domain.sync

import app.readylytics.health.di.IoDispatcher
import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.scoringZone
import app.readylytics.health.domain.scoring.RasSourceModeBootstrapUseCase
import app.readylytics.health.domain.sync.link.SessionLinkReconciler
import app.readylytics.health.domain.util.logD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private companion object {
            // How far back a foreground sync will widen its walk-forward recompute to absorb
            // recent out-of-window Health Connect changes (e.g. last night's sleep dated
            // yesterday, HR/HRV backfilled for the prior day) inline instead of escalating to a
            // full historical resync. This is a foreground-cost guard, not a correctness bound:
            // changes older than this still recompute correctly via the durable resync worker.
            const val MAX_INLINE_RECOMPUTE_DAYS = 7
        }

        /**
         * @param onProgress optional reactive hook invoked as the walk-forward recompute advances,
         *   reporting (phase, completedDays, totalDays) so the UI can surface determinate progress
         *   instead of a silent spinner. Invoked off the main thread. Daily sync has no chunked
         *   ingest/prune phase of its own (single non-chunked window, always fast), so it always
         *   reports [ResyncPhase.RECOMPUTE].
         */
        suspend fun run(
            windowDays: Int,
            onProgress: ((phase: ResyncPhase, current: Int, total: Int) -> Unit)?,
        ): Result<Unit> =
            withContext(ioDispatcher) {
                try {
                    logD("DailySyncUseCase") { "Starting sync (window=$windowDays days)..." }
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
                    val today = java.time.LocalDate.now(zoneId)

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

                    // Overnight sleep sessions cross midnight: a session ending inside the
                    // recompute range may begin the previous evening. Reach the raw-sample fetch
                    // back one extra day from the earliest target day so pre-midnight HR/HRV
                    // samples of the earliest in-range night are captured.
                    val ingestStart = oldestTargetDay.minusDays(1).atStartOfDay(zoneId).toInstant()

                    ingestionCoordinator.ingestWindow(ingestStart, windowEnd, prefs)
                    sessionLinkReconciler.reconcile(
                        startMs = ingestStart.toEpochMilli(),
                        endMs = windowEnd.toEpochMilli() - 1,
                        zoneThresholds =
                            app.readylytics.health.data.healthconnect.WorkoutMapper.zoneThresholds(
                                prefs.zone1MinBpm,
                                prefs.zone1MaxBpm,
                                prefs.zone2MaxBpm,
                                prefs.zone3MaxBpm,
                                prefs.zone4MaxBpm,
                            ),
                    )

                    val stepsDevice =
                        prefs.deviceByDataType[HealthDataType.STEPS.name]?.takeIf { it.isNotBlank() }
                    val totalDays = ChronoUnit.DAYS.between(oldestTargetDay, today).toInt() + 1
                    val stepsMap = stepCountFetcher.fetchWindow(today, totalDays, zoneId, stepsDevice)

                    var processedDays = 0
                    onProgress?.invoke(ResyncPhase.RECOMPUTE, processedDays, totalDays)

                    var successCount = 0
                    var failureCount = 0

                    healthIngestionStore.clearFrozenBaselines(oldestTargetDay, today.plusDays(1), zoneId)

                    var dayToScore = oldestTargetDay
                    while (!dayToScore.isAfter(today)) {
                        ensureActive()
                        val steps = stepsMap[dayToScore]
                        val result = recomputeSupport.recomputeDay(dayToScore, steps, prefs)

                        when (result) {
                            is Result.Success -> {
                                successCount++
                                logD("DailySyncUseCase") { "Day $dayToScore: SUCCESS" }
                            }
                            is Result.Failure -> {
                                failureCount++
                                logD("DailySyncUseCase") { "Day $dayToScore: FAILED - ${result.reason}" }
                            }
                        }
                        processedDays++
                        onProgress?.invoke(ResyncPhase.RECOMPUTE, processedDays, totalDays)
                        dayToScore = dayToScore.plusDays(1)
                        yield()
                    }

                    logD("DailySyncUseCase") {
                        "Sync complete: $successCount succeeded, $failureCount failed"
                    }
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
                } catch (e: Exception) {
                    Result.failure("Sync failed", "SYNC_ERROR")
                }
            }
    }
