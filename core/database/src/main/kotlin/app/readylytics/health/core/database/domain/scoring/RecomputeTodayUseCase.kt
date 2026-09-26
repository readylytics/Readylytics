package app.readylytics.health.core.database.domain.scoring

import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.repository.ScoringRepository
import app.readylytics.health.core.model.domain.scoring.RecomputeToday
import app.readylytics.health.core.model.domain.sync.HealthMutationCoordinator
import app.readylytics.health.core.model.domain.sync.RecalcTrigger
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.workers.WorkerScheduler
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecomputeTodayUseCase @Inject constructor(
    private val coordinator: HealthMutationCoordinator,
    private val scoringRepository: ScoringRepository,
    private val workerScheduler: WorkerScheduler,
) : RecomputeToday {

    override suspend fun execute(day: LocalDate): Result<Unit> {
        return try {
            coordinator.withMutation {
                scoringRepository.computeAndPersistDailySummary(day)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.message?.contains("MAINTENANCE_PENDING") == true) {
                try {
                    workerScheduler.scheduleResyncWorker(
                        recomputeOnly = true,
                        startDate = day,
                        endDate = day,
                        trigger = RecalcTrigger.SETTINGS_CHANGE,
                        triggerDetail = null,
                    )
                    Result.failure(
                        reason = "Maintenance is pending; scheduled background recompute",
                        code = "MAINTENANCE_PENDING",
                    )
                } catch (fallbackError: CancellationException) {
                    throw fallbackError
                } catch (fallbackError: Exception) {
                    logE("RecomputeTodayUseCase", fallbackError) { "Failed to schedule fallback resync" }
                    Result.failure(
                        reason = fallbackError.message ?: "Failed to schedule fallback resync",
                        code = "RECOMPUTE_TODAY_ERROR",
                    )
                }
            } else {
                logE("RecomputeTodayUseCase", e) { "Failed to recompute day $day" }
                Result.failure(
                    reason = e.message ?: "Failed to recompute day",
                    code = "RECOMPUTE_TODAY_ERROR",
                )
            }
        }
    }
}
