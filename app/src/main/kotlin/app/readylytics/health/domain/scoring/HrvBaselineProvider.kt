package app.readylytics.health.domain.scoring

import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.scoringZone
import app.readylytics.health.domain.util.toMidnightEpochMilli
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

@Singleton
class HrvBaselineProvider
    @Inject
    constructor(
        private val dao: DailySummaryDao,
        private val settingsRepository: SettingsRepository,
        private val baselineComputer: BaselineComputer,
    ) {
        suspend fun getPreciseHrvBaseline(date: LocalDate): Double? {
            val prefs = settingsRepository.userPreferences.first()
            val zone = prefs.scoringZone()
            val dateMs = date.toMidnightEpochMilli(zone)
            val mu = dao.getPreciseHrvMu(dateMs)
            if (mu != null) return exp(mu)

            if (prefs.hrvBaselineOverride != null) return prefs.hrvBaselineOverride.toDouble()

            val dayMidnight = date.atStartOfDay(zone).toInstant()
            val calculated = baselineComputer.computeHrvBaseline(dayMidnight, null)
            return calculated?.toDouble()
        }

        suspend fun getRoundedHrvBaseline(date: LocalDate): Int? {
            val prefs = settingsRepository.userPreferences.first()
            val zone = prefs.scoringZone()
            val dateMs = date.toMidnightEpochMilli(zone)
            val rounded = dao.getRoundedHrvBaseline(dateMs)
            if (rounded != null) return rounded

            if (prefs.hrvBaselineOverride != null) return Math.round(prefs.hrvBaselineOverride)

            val dayMidnight = date.atStartOfDay(zone).toInstant()
            return baselineComputer.computeHrvBaseline(dayMidnight, null)
        }
    }
