package com.gregor.lauritz.healthdashboard.domain.user

import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.model.Result
import com.gregor.lauritz.healthdashboard.domain.repository.ScoringRepository
import com.gregor.lauritz.healthdashboard.domain.sync.HealthSyncUseCase
import com.gregor.lauritz.healthdashboard.domain.util.HeartRateFormulas
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
    ) {
        suspend fun updateBirthday(date: LocalDate): Result<Unit> =
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

        suspend fun calculateAndSetMaxHr(): Result<Unit> =
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
