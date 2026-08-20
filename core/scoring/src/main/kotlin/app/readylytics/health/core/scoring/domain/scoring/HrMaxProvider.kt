package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.HrMaxProvider

import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.scoring.domain.util.HeartRateFormulas
import app.readylytics.health.core.model.domain.util.toMidnightEpochMilli
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HrMaxProvider
    @Inject
    constructor(
        private val scoringHistoryRepository: ScoringHistoryRepository,
        private val settingsRepository: SettingsRepository,
    ) {
        suspend fun getPreciseHrMax(date: LocalDate): Double {
            val dateMs = date.toMidnightEpochMilli()
            val dbValue = scoringHistoryRepository.getPreciseHrMax(dateMs)
            if (dbValue != null) return dbValue

            val prefs = settingsRepository.userPreferences.first()
            return HeartRateFormulas.resolveMaxHeartRate(prefs).toDouble()
        }

        suspend fun getRoundedHrMax(date: LocalDate): Int {
            val dateMs = date.toMidnightEpochMilli()
            val dbValue = scoringHistoryRepository.getRoundedHrMax(dateMs)
            if (dbValue != null) return dbValue

            val prefs = settingsRepository.userPreferences.first()
            return Math.round(HeartRateFormulas.resolveMaxHeartRate(prefs))
        }
    }
