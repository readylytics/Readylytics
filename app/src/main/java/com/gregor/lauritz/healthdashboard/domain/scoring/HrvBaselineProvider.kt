package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.util.toMidnightEpochMilli
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

@Singleton
class HrvBaselineProvider @Inject constructor(
    private val dao: DailySummaryDao,
    private val settingsRepository: SettingsRepository,
    private val baselineComputer: BaselineComputer,
) {
    suspend fun getPreciseHrvBaseline(date: LocalDate): Double? {
        val dateMs = date.toMidnightEpochMilli()
        val mu = dao.getPreciseHrvMu(dateMs)
        if (mu != null) return exp(mu).toDouble()

        val prefs = settingsRepository.userPreferences.first()
        if (prefs.hrvBaselineOverride != null) return prefs.hrvBaselineOverride.toDouble()

        val dayMidnight = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val calculated = baselineComputer.computeHrvBaseline(dayMidnight, null)
        return calculated?.toDouble()
    }

    suspend fun getRoundedHrvBaseline(date: LocalDate): Int? {
        val dateMs = date.toMidnightEpochMilli()
        val rounded = dao.getRoundedHrvBaseline(dateMs)
        if (rounded != null) return rounded

        val prefs = settingsRepository.userPreferences.first()
        if (prefs.hrvBaselineOverride != null) return Math.round(prefs.hrvBaselineOverride)

        val dayMidnight = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
        return baselineComputer.computeHrvBaseline(dayMidnight, null)
    }
}
