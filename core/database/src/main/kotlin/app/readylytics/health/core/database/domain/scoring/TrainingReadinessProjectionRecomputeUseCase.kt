package app.readylytics.health.core.database.domain.scoring

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.scoring.domain.scoring.ComputeTrainingReadinessUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable, parameter-only Training Readiness recompute: given a changed
 * [TrainingReadinessConfig] (residual-fatigue scale S, load-balance weight w), re-derives the
 * Training Readiness projection for every already-persisted [DailySummary] without touching
 * Health Connect, recomputing TRIMP/residual fatigue, or reading any other DAO.
 *
 * One [ScoringHistoryRepository.getDailySummariesSince] read loads the retained rows (kept behind
 * the domain-safe repository abstraction rather than a raw DAO, matching every other class in this
 * package), the transform below mirrors the data-layer `FinalSummaryAssembler`'s two-variant
 * (workout-only / everyday-HR) projection using the exact [TrainingReadinessConfig] supplied by
 * the caller (never a live/edited value), and one [TransactionRunner]-wrapped
 * [ScoringHistoryRepository.upsertDailySummaries] batch commits the result. A cancelled run rolls
 * the transaction back (Room's `withTransaction` semantics), leaving the prior valid projection
 * untouched.
 */
@Singleton
class TrainingReadinessProjectionRecomputeUseCase
    @Inject
    constructor(
        private val scoringHistoryRepository: ScoringHistoryRepository,
        private val transactionRunner: TransactionRunner,
        private val computeTrainingReadiness: ComputeTrainingReadinessUseCase,
    ) {
        suspend fun execute(
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
            config: TrainingReadinessConfig,
            onProgress: ((current: Int, total: Int) -> Unit)? = null,
        ): Result<Unit> {
            if (startDate.isAfter(endDate)) {
                return Result.success(Unit)
            }
            return try {
                val fromMs = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val projected = projectRetainedRows(fromMs, zoneId, config, onProgress)
                transactionRunner.runInTransaction {
                    scoringHistoryRepository.upsertDailySummaries(projected, zoneId)
                }
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logE(TAG, e) { "Training readiness projection recompute failed for $startDate..$endDate" }
                Result.failure(
                    "Training readiness projection recompute failed",
                    "TRAINING_READINESS_PROJECTION_ERROR",
                )
            }
        }

        /** Reads once, then maps+projects in memory -- no per-day querying, no other DAOs touched. */
        private suspend fun projectRetainedRows(
            fromMs: Long,
            zoneId: ZoneId,
            config: TrainingReadinessConfig,
            onProgress: ((current: Int, total: Int) -> Unit)?,
        ): List<DailySummary> =
            coroutineScope {
                val summaries = scoringHistoryRepository.getDailySummariesSince(fromMs, zoneId)
                val total = summaries.size
                summaries.mapIndexed { index, summary ->
                    if (index % PROGRESS_YIELD_INTERVAL == 0) {
                        ensureActive()
                        yield()
                    }
                    val projected = applyProjection(summary, config)
                    onProgress?.invoke(index + 1, total)
                    projected
                }
            }

        /**
         * Mirrors the data-layer `FinalSummaryAssembler`'s two-call pattern: the shared
         * residual-fatigue/restoration/sleep/recovery-flag inputs feed both the workout-only and
         * everyday-HR [ComputeTrainingReadinessUseCase] branches, so only the load-dependent fields
         * diverge.
         */
        private fun applyProjection(
            summary: DailySummary,
            config: TrainingReadinessConfig,
        ): DailySummary {
            val projectionForWorkout =
                computeTrainingReadiness.compute(
                    restoration = summary.sRest,
                    sleepScore = summary.sleepScore,
                    loadScore = summary.loadScoreWorkoutOnly,
                    legacyReadiness = summary.readinessWorkoutOnly,
                    residualFatigue = summary.residualFatigue,
                    recoveryFlags = summary.recoveryFlags,
                    config = config,
                )
            val projectionForEveryday =
                computeTrainingReadiness.compute(
                    restoration = summary.sRest,
                    sleepScore = summary.sleepScore,
                    loadScore = summary.loadScoreEverydayHr,
                    legacyReadiness = summary.readinessEverydayHr,
                    residualFatigue = summary.residualFatigue,
                    recoveryFlags = summary.recoveryFlags,
                    config = config,
                )
            require(projectionForWorkout.acuteLoadRecovery == projectionForEveryday.acuteLoadRecovery) {
                "Acute load recovery must match between load variants"
            }
            return summary.copy(
                acuteLoadRecovery = projectionForWorkout.acuteLoadRecovery,
                trainingLoadReadinessWorkoutOnly = projectionForWorkout.trainingLoadReadiness,
                trainingReadinessWorkoutOnly = projectionForWorkout.trainingReadiness,
                trainingLoadReadinessEverydayHr = projectionForEveryday.trainingLoadReadiness,
                trainingReadinessEverydayHr = projectionForEveryday.trainingReadiness,
            )
        }

        private companion object {
            const val TAG = "TrainingReadinessProjectionRecomputeUseCase"
            const val PROGRESS_YIELD_INTERVAL = 50
        }
    }
