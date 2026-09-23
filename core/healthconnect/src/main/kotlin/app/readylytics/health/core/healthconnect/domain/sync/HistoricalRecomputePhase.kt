package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.database.domain.sync.DailyRecomputeSupport
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.WalkForwardContexts
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.HistoricalRunIdentity
import app.readylytics.health.core.model.domain.sync.ResyncCheckpoint
import app.readylytics.health.core.model.domain.sync.ResyncCheckpointStore
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.model.domain.sync.StepAttribution
import app.readylytics.health.core.model.domain.sync.ScoringRunContext
import app.readylytics.health.core.model.domain.util.logD
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

data class RecomputePhaseContext(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val zoneId: ZoneId,
    val prefs: UserPreferences,
    val runContext: ScoringRunContext,
    val chunkDays: Int,
    val totalDays: Int,
    val completedDays: Int,
    val recomputeStartDate: LocalDate,
    val selectionHash: String,
    val baselineChangeTokens: Map<HealthDataType, String>,
    val runCompletedTypes: Set<HealthDataType>,
    val runIdentity: HistoricalRunIdentity,
    val checkpoint: ResyncCheckpoint?,
    val skipIngestAndPrune: Boolean,
    val stepsDevice: String?,
    val stepCountFetcher: StepCountFetcher,
)

@Singleton
class HistoricalRecomputePhase
    @Inject
    constructor(
        private val healthIngestionStore: HealthIngestionStore,
        private val recomputeSupport: DailyRecomputeSupport,
        private val checkpointStore: ResyncCheckpointStore,
        private val clock: Clock = Clock.systemDefaultZone(),
    ) {
        suspend fun execute(
            context: RecomputePhaseContext,
            onProgress: ((phase: ResyncPhase, current: Int, total: Int) -> Unit)?,
        ): Result<Unit> {
            val recomputeStart = clock.millis()
            val stepsMap = fetchSteps(context)
            val contexts = buildContexts(context)

            clearFrozenBaselinesIfNeeded(context)
            onProgress?.invoke(ResyncPhase.RECOMPUTE, context.completedDays, context.totalDays)

            var chunkStartDay = context.recomputeStartDate
            var recomputedDays = context.completedDays
            while (!chunkStartDay.isAfter(context.endDate)) {
                val chunkEndDay =
                    minOf(
                        chunkStartDay.plusDays((RECOMPUTE_CHECKPOINT_INTERVAL_DAYS - 1).toLong()),
                        context.endDate,
                    )
                val chunkFailure =
                    recomputeChunk(
                        context = context,
                        chunkStartDay = chunkStartDay,
                        chunkEndDay = chunkEndDay,
                        daysBeforeChunk = recomputedDays,
                        stepsMap = stepsMap,
                        contexts = contexts,
                        onProgress = onProgress,
                    )
                if (chunkFailure != null) {
                    return chunkFailure
                }
                recomputedDays =
                    ChronoUnit
                        .DAYS
                        .between(context.startDate, chunkEndDay.plusDays(1))
                        .toInt()
                        .coerceIn(0, context.totalDays)
                saveChunkCheckpoint(context, chunkEndDay)
                chunkStartDay = chunkEndDay.plusDays(1)
            }
            val recomputeEnd = clock.millis()
            logD(TELEMETRY_TAG) {
                "[RECOMPUTE] Completed in ${recomputeEnd - recomputeStart}ms. Days recomputed: $recomputedDays"
            }
            return Result.success(Unit)
        }

        private suspend fun fetchSteps(context: RecomputePhaseContext): Map<LocalDate, Long> =
            if (!context.skipIngestAndPrune && !context.recomputeStartDate.isAfter(context.endDate)) {
                context.stepCountFetcher.fetchRange(
                    startDate = context.recomputeStartDate,
                    endDate = context.endDate,
                    chunkDays = context.chunkDays,
                    stepsDevice = context.stepsDevice,
                    zoneId = context.zoneId,
                )
            } else {
                emptyMap()
            }

        private suspend fun buildContexts(context: RecomputePhaseContext): WalkForwardContexts =
            if (!context.recomputeStartDate.isAfter(context.endDate)) {
                WalkForwardContexts(
                    trimp =
                        recomputeSupport.buildWalkForwardTrimpContext(
                            context.recomputeStartDate,
                            context.endDate,
                            context.zoneId,
                        ),
                    baseline =
                        recomputeSupport.buildWalkForwardBaselineContext(
                            context.recomputeStartDate,
                            context.endDate,
                            context.zoneId,
                        ),
                    fatigue =
                        recomputeSupport.buildWalkForwardFatigueContext(
                            context.recomputeStartDate,
                            context.endDate,
                            context.prefs,
                            context.runContext,
                        ),
                    vo2Max =
                        recomputeSupport.buildWalkForwardVo2MaxContext(
                            context.recomputeStartDate,
                            context.endDate,
                            context.zoneId,
                        ),
                )
            } else {
                WalkForwardContexts(null, null, null, null)
            }

        private suspend fun clearFrozenBaselinesIfNeeded(context: RecomputePhaseContext) {
            if (context.checkpoint == null || context.checkpoint.phase != ResyncPhase.RECOMPUTE) {
                healthIngestionStore.clearFrozenBaselines(
                    context.startDate,
                    context.endDate.plusDays(1),
                    context.zoneId,
                )
            }
        }

        private suspend fun recomputeChunk(
            context: RecomputePhaseContext,
            chunkStartDay: LocalDate,
            chunkEndDay: LocalDate,
            daysBeforeChunk: Int,
            stepsMap: Map<LocalDate, Long>,
            contexts: WalkForwardContexts,
            onProgress: ((phase: ResyncPhase, current: Int, total: Int) -> Unit)?,
        ): Result.Failure? =
            recomputeSupport.inRecomputeTransaction {
                var day = chunkStartDay
                var failure: Result.Failure? = null
                var daysDone = daysBeforeChunk
                while (!day.isAfter(chunkEndDay)) {
                    currentCoroutineContext().ensureActive()
                    val stepsForDay =
                        StepAttribution.resolve(
                            day,
                            stepsMap,
                            stepsDeviceSelected = context.stepsDevice != null,
                            recomputeOnly = context.skipIngestAndPrune,
                        )
                    val dayResult =
                        recomputeSupport.recomputeDay(
                            day,
                            stepsForDay,
                            context.prefs,
                            contexts,
                            context.runContext,
                        )
                    if (dayResult is Result.Failure) {
                        logD(TELEMETRY_TAG) { "[RECOMPUTE] Failed at day $day: ${dayResult.reason}" }
                        failure = dayResult
                        break
                    }
                    daysDone++
                    onProgress?.invoke(ResyncPhase.RECOMPUTE, daysDone, context.totalDays)
                    day = day.plusDays(1)
                    yield()
                }
                failure
            }

        private suspend fun saveChunkCheckpoint(
            context: RecomputePhaseContext,
            chunkEndDay: LocalDate,
        ) {
            checkpointStore.save(
                ResyncCheckpoint(
                    startDate = context.startDate,
                    endDate = context.endDate,
                    phase = ResyncPhase.RECOMPUTE,
                    nextDate = chunkEndDay.plusDays(1),
                    selectionHash = context.selectionHash,
                    baselineChangeTokens = context.baselineChangeTokens,
                    completedTypes = context.runCompletedTypes,
                    runIdentity = context.runIdentity,
                ),
            )
        }

        companion object {
            private const val TELEMETRY_TAG = "ResyncTelemetry"
            private const val RECOMPUTE_CHECKPOINT_INTERVAL_DAYS = 30
        }
    }
