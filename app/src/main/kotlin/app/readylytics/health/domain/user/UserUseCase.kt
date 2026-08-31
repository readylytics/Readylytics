package app.readylytics.health.domain.user

import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.repository.ScoringRepository
import app.readylytics.health.core.model.domain.user.UserProfileActions
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.workers.WorkerScheduler
import app.readylytics.health.core.scoring.domain.util.HeartRateFormulas
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import java.time.Period
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserUseCase
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val workerScheduler: WorkerScheduler,
        private val scoringRepository: ScoringRepository,
        private val clock: Clock,
    ) : UserProfileActions {
        override suspend fun updateBirthday(date: LocalDate): Result<Unit> =
            try {
                val age = calculateAge(date)
                settingsRepo.updateBirthday(date)

                scoringRepository.computeAndPersistDailySummary(LocalDate.now(clock))

                val prefs = settingsRepo.userPreferences.first()
                if (prefs.autoCalculateMaxHr) {
                    val maxHr = calculateMaxHeartRate(age)
                    settingsRepo.updateMaxHeartRate(maxHr)
                }
                // Birthday changes age-dependent scoring inputs, and automatic hrMax when enabled.
                // Queue one durable historical pass after every affected preference has been persisted.
                workerScheduler.scheduleResyncWorker(recomputeOnly = true)
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logE("UserUseCase", e) { "Failed to update birthday" }
                Result.failure("Failed to update birthday", "BIRTHDAY_UPDATE_ERROR")
            }

        override suspend fun calculateAndSetMaxHr(): Result<Unit> =
            try {
                val prefs = settingsRepo.userPreferences.first()
                if (prefs.autoCalculateMaxHr) {
                    val maxHr = calculateMaxHeartRate(prefs.age)
                    settingsRepo.updateMaxHeartRate(maxHr)
                    workerScheduler.scheduleResyncWorker(recomputeOnly = true)
                }
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logE("UserUseCase", e) { "Failed to calculate max HR" }
                Result.failure("Failed to calculate max HR", "MAX_HR_CALC_ERROR")
            }

        fun calculateAge(
            date: LocalDate,
            today: LocalDate = LocalDate.now(clock),
        ): Int = Period.between(date, today).years

        fun calculateMaxHeartRate(age: Int): Int = HeartRateFormulas.estimateMaxHr(age)
    }
