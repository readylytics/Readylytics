package app.readylytics.health.domain.user

import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.sync.HealthSyncUseCase
import app.readylytics.health.domain.util.HeartRateFormulas
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.Period
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserUseCase
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val healthSyncUseCase: HealthSyncUseCase,
        private val scoringRepository: ScoringRepository,
    ) : UserProfileActions {
        override suspend fun updateBirthday(date: LocalDate): Result<Unit> =
            try {
                val age = calculateAge(date)
                settingsRepo.updateBirthday(date)

                scoringRepository.computeAndPersistDailySummary()

                val prefs = settingsRepo.userPreferences.first()
                if (prefs.autoCalculateMaxHr) {
                    val maxHr = calculateMaxHeartRate(age)
                    settingsRepo.updateMaxHeartRate(maxHr)
                    healthSyncUseCase.sync()
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure("Failed to update birthday", "BIRTHDAY_UPDATE_ERROR")
            }

        override suspend fun calculateAndSetMaxHr(): Result<Unit> =
            try {
                val prefs = settingsRepo.userPreferences.first()
                if (prefs.autoCalculateMaxHr) {
                    val maxHr = calculateMaxHeartRate(prefs.age)
                    settingsRepo.updateMaxHeartRate(maxHr)
                    healthSyncUseCase.sync()
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure("Failed to calculate max HR", "MAX_HR_CALC_ERROR")
            }

        fun calculateAge(date: LocalDate): Int = Period.between(date, LocalDate.now()).years

        fun calculateMaxHeartRate(age: Int): Int = HeartRateFormulas.estimateMaxHr(age)
    }
