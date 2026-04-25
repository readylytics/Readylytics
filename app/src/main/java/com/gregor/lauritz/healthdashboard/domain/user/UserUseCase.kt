package com.gregor.lauritz.healthdashboard.domain.user

import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.sync.HealthSyncUseCase
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringRepository
import com.gregor.lauritz.healthdashboard.domain.util.HeartRateFormulas
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.Period
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserUseCase @Inject constructor(
    private val prefsRepo: UserPreferencesRepository,
    private val healthSyncUseCase: HealthSyncUseCase,
    private val scoringRepository: ScoringRepository,
) {
    suspend fun updateBirthday(day: Int, month: Int, year: Int) {
        val age = calculateAge(day, month, year)
        prefsRepo.updateBirthday(day, month, year)

        scoringRepository.computeAndPersistDailySummary()

        val prefs = prefsRepo.userPreferences.first()
        if (prefs.autoCalculateMaxHr) {
            val maxHr = calculateMaxHeartRate(age)
            prefsRepo.updateMaxHeartRate(maxHr)
            healthSyncUseCase.sync()
        }
    }

    suspend fun calculateAndSetMaxHr() {
        val prefs = prefsRepo.userPreferences.first()
        if (prefs.autoCalculateMaxHr) {
            val maxHr = calculateMaxHeartRate(prefs.age)
            prefsRepo.updateMaxHeartRate(maxHr)
            healthSyncUseCase.sync()
        }
    }

    fun calculateAge(day: Int, month: Int, year: Int): Int {
        val birthDate = LocalDate.of(year, month, day)
        return Period.between(birthDate, LocalDate.now()).years
    }

    fun calculateMaxHeartRate(age: Int): Int {
        return HeartRateFormulas.estimateMaxHr(age)
    }
}
