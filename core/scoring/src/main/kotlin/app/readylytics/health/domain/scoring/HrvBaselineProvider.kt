package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.scoringZone
import app.readylytics.health.domain.repository.ScoringHistoryRepository
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
        private val scoringHistoryRepository: ScoringHistoryRepository,
        private val settingsRepository: SettingsRepository,
        private val baselineComputer: BaselineComputer,
    ) {
        suspend fun getPreciseHrvBaseline(date: LocalDate): Double? {
            val prefs = settingsRepository.userPreferences.first()
            val zone = prefs.scoringZone()
            val dateMs = date.toMidnightEpochMilli(zone)
            val mu = scoringHistoryRepository.getPreciseHrvMu(dateMs)
            if (mu != null) return exp(mu)

            val override = prefs.hrvBaselineOverride
            if (override != null) return override.toDouble()

            val dayMidnight = date.atStartOfDay(zone).toInstant()
            val calculated = baselineComputer.computeHrvBaseline(dayMidnight, null)
            return calculated?.toDouble()
        }

        suspend fun getRoundedHrvBaseline(date: LocalDate): Int? =
            getPreciseHrvBaseline(date)?.let { Math.round(it).toInt() }
    }
