package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.model.PhysiologyConstants
import app.readylytics.health.domain.persistence.DailySummaryDao
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.scoringZone
import app.readylytics.health.domain.util.toMidnightEpochMilli
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
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
            val prefs = settingsRepository.userPreferences.first()
            val zone = prefs.scoringZone()
            val dateMs = date.toMidnightEpochMilli(zone)
            val dbValue = dao.getPreciseRhrBaseline(dateMs)
            if (dbValue != null) return dbValue

            if (prefs.rhrBaselineOverride != null) return prefs.rhrBaselineOverride.toDouble()

            val dayMidnight = date.atStartOfDay(zone).toInstant()
            val rhrValues = baselineComputer.rhrHistory(dayMidnight, prefs.restingHrPercentile)
            val hasEnoughData = rhrValues.size >= ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION
            return if (hasEnoughData) {
                baselineComputer.resolveBaselineRhrBpm(rhrValues, null).toDouble()
            } else {
                PhysiologyConstants.DEFAULT_RHR_BPM.toDouble()
            }
        }

        override suspend fun getRoundedRhrBaseline(date: LocalDate): Int {
            val prefs = settingsRepository.userPreferences.first()
            val zone = prefs.scoringZone()
            val dateMs = date.toMidnightEpochMilli(zone)
            val dbValue = dao.getRoundedRhrBaseline(dateMs)
            if (dbValue != null) return dbValue

            if (prefs.rhrBaselineOverride != null) return Math.round(prefs.rhrBaselineOverride)

            val dayMidnight = date.atStartOfDay(zone).toInstant()
            val rhrValues = baselineComputer.rhrHistory(dayMidnight, prefs.restingHrPercentile)
            val hasEnoughData = rhrValues.size >= ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION
            return if (hasEnoughData) {
                Math.round(baselineComputer.resolveBaselineRhrBpm(rhrValues, null))
            } else {
                PhysiologyConstants.DEFAULT_RHR_BPM
            }
        }

        override suspend fun getRhrBaseline(dayMidnight: Instant): Float {
            val prefs = settingsRepository.userPreferences.first()
            val date = dayMidnight.atZone(prefs.scoringZone()).toLocalDate()
            return getPreciseRhrBaseline(date).toFloat()
        }
    }
