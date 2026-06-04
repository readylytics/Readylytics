package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.util.HeartRateFormulas
import com.gregor.lauritz.healthdashboard.domain.util.toMidnightEpochMilli
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HrMaxProvider @Inject constructor(
    private val dao: DailySummaryDao,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun getPreciseHrMax(date: LocalDate): Double {
        val dateMs = date.toMidnightEpochMilli()
        val dbValue = dao.getPreciseHrMax(dateMs)
        if (dbValue != null) return dbValue

        val prefs = settingsRepository.userPreferences.first()
        return HeartRateFormulas.resolveMaxHeartRate(prefs).toDouble()
    }

    suspend fun getRoundedHrMax(date: LocalDate): Int {
        val dateMs = date.toMidnightEpochMilli()
        val dbValue = dao.getRoundedHrMax(dateMs)
        if (dbValue != null) return dbValue

        val prefs = settingsRepository.userPreferences.first()
        return Math.round(HeartRateFormulas.resolveMaxHeartRate(prefs))
    }
}
