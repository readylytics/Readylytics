package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.HistoricalRunIdentity
import app.readylytics.health.core.model.domain.sync.ResyncCheckpoint
import app.readylytics.health.core.model.domain.sync.ResyncCheckpointStore
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.model.domain.sync.SelectedSourcePruner
import app.readylytics.health.core.model.domain.util.logD
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class PrunePhaseContext(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val zoneId: ZoneId,
    val prefs: UserPreferences,
    val reconcileStartMs: Long,
    val reconcileEndMs: Long,
    val selectionHash: String,
    val baselineChangeTokens: Map<HealthDataType, String>,
    val runCompletedTypes: Set<HealthDataType>,
    val runIdentity: HistoricalRunIdentity,
    val runIngestion: Boolean,
    val initialCounts: PruneCounts,
)

data class PruneCounts(
    val hr: Int,
    val hrv: Int,
    val sleep: Int,
    val workout: Int,
)

@Singleton
class HistoricalPrunePhase
    @Inject
    constructor(
        private val selectedSourcePruner: SelectedSourcePruner,
        private val healthIngestionStore: HealthIngestionStore,
        private val checkpointStore: ResyncCheckpointStore,
        private val clock: Clock = Clock.systemDefaultZone(),
    ) {
        suspend fun execute(
            context: PrunePhaseContext,
            onProgress: ((phase: ResyncPhase, current: Int, total: Int) -> Unit)?,
        ) {
            onProgress?.invoke(ResyncPhase.PRUNE, 0, 0)
            val hrBefore =
                if (context.runIngestion) {
                    context.initialCounts.hr
                } else {
                    healthIngestionStore.countHeartRateInRange(context.reconcileStartMs, context.reconcileEndMs)
                }
            val hrvBefore =
                if (context.runIngestion) {
                    context.initialCounts.hrv
                } else {
                    healthIngestionStore.countHrvInRange(context.reconcileStartMs, context.reconcileEndMs)
                }
            val sleepBefore =
                if (context.runIngestion) {
                    context.initialCounts.sleep
                } else {
                    healthIngestionStore.countSleepSessionsInRange(context.reconcileStartMs, context.reconcileEndMs)
                }
            val workoutBefore =
                if (context.runIngestion) {
                    context.initialCounts.workout
                } else {
                    healthIngestionStore.countWorkoutsInRange(context.reconcileStartMs, context.reconcileEndMs)
                }

            val prunerSelections =
                HealthDataType.entries.associateWith { type ->
                    context.prefs.deviceByDataType[type.name]
                }
            val pruneStart = clock.millis()
            selectedSourcePruner.prune(
                start = context.startDate,
                endInclusive = context.endDate,
                selections = prunerSelections,
                zoneId = context.zoneId,
            )
            checkpointStore.save(
                ResyncCheckpoint(
                    startDate = context.startDate,
                    endDate = context.endDate,
                    phase = ResyncPhase.RECONCILE,
                    nextDate = context.startDate,
                    selectionHash = context.selectionHash,
                    baselineChangeTokens = context.baselineChangeTokens,
                    completedTypes = context.runCompletedTypes,
                    runIdentity = context.runIdentity,
                ),
            )
            val pruneEnd = clock.millis()
            logTelemetry(
                pruneStart = pruneStart,
                pruneEnd = pruneEnd,
                reconcileStartMs = context.reconcileStartMs,
                reconcileEndMs = context.reconcileEndMs,
                before = PruneCounts(hrBefore, hrvBefore, sleepBefore, workoutBefore),
            )
        }

        private suspend fun logTelemetry(
            pruneStart: Long,
            pruneEnd: Long,
            reconcileStartMs: Long,
            reconcileEndMs: Long,
            before: PruneCounts,
        ) {
            val hrAfter = healthIngestionStore.countHeartRateInRange(reconcileStartMs, reconcileEndMs)
            val hrvAfter = healthIngestionStore.countHrvInRange(reconcileStartMs, reconcileEndMs)
            val sleepAfter = healthIngestionStore.countSleepSessionsInRange(reconcileStartMs, reconcileEndMs)
            val workoutAfter = healthIngestionStore.countWorkoutsInRange(reconcileStartMs, reconcileEndMs)

            logD(TELEMETRY_TAG) {
                "[PRUNING] Completed in ${pruneEnd - pruneStart}ms. " +
                    "HeartRate: ${before.hr} -> $hrAfter (pruned: ${before.hr - hrAfter}), " +
                    "HRV: ${before.hrv} -> $hrvAfter (pruned: ${before.hrv - hrvAfter}), " +
                    "Sleep: ${before.sleep} -> $sleepAfter (pruned: ${before.sleep - sleepAfter}), " +
                    "Workout: ${before.workout} -> $workoutAfter (pruned: ${before.workout - workoutAfter})"
            }
        }

        companion object {
            private const val TELEMETRY_TAG = "ResyncTelemetry"
        }
    }
