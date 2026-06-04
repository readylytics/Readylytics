package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.model.PhysiologyConstants
import com.gregor.lauritz.healthdashboard.domain.util.toMidnightEpochMilli
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

interface RhrBaselineProvider {
    suspend fun getPreciseRhrBaseline(date: LocalDate): Double

    suspend fun getRoundedRhrBaseline(date: LocalDate): Int

    suspend fun getRhrBaseline(dayMidnight: Instant): Float
}

@Singleton
class AdaptiveRhrBaselineProvider
    @Inject
    constructor(
        private val dao: DailySummaryDao,
        private val settingsRepository: SettingsRepository,
        private val baselineComputer: BaselineComputer,
    ) : RhrBaselineProvider {
        override suspend fun getPreciseRhrBaseline(date: LocalDate): Double {
            val dateMs = date.toMidnightEpochMilli()
            val dbValue = dao.getPreciseRhrBaseline(dateMs)
            if (dbValue != null) return dbValue

            val prefs = settingsRepository.userPreferences.first()
            if (prefs.rhrBaselineOverride != null) return prefs.rhrBaselineOverride.toDouble()

            val dayMidnight = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
            val rhrValues = baselineComputer.rhrHistory(dayMidnight, prefs.restingHrPercentile)
            val hasEnoughData = rhrValues.size >= ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION
            return if (hasEnoughData) {
                baselineComputer.resolveBaselineRhrBpm(rhrValues, null).toDouble()
            } else {
                PhysiologyConstants.DEFAULT_RHR_BPM.toDouble()
            }
        }

        override suspend fun getRoundedRhrBaseline(date: LocalDate): Int {
            val dateMs = date.toMidnightEpochMilli()
            val dbValue = dao.getRoundedRhrBaseline(dateMs)
            if (dbValue != null) return dbValue

            val prefs = settingsRepository.userPreferences.first()
            if (prefs.rhrBaselineOverride != null) return Math.round(prefs.rhrBaselineOverride)

            val dayMidnight = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
            val rhrValues = baselineComputer.rhrHistory(dayMidnight, prefs.restingHrPercentile)
            val hasEnoughData = rhrValues.size >= ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION
            return if (hasEnoughData) {
                Math.round(baselineComputer.resolveBaselineRhrBpm(rhrValues, null))
            } else {
                PhysiologyConstants.DEFAULT_RHR_BPM.toInt()
            }
        }

        override suspend fun getRhrBaseline(dayMidnight: Instant): Float {
            val date = dayMidnight.atZone(ZoneId.systemDefault()).toLocalDate()
            return getPreciseRhrBaseline(date).toFloat()
        }
    }
