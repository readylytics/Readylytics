package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.HrvBaselineProvider

import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.util.toMidnightEpochMilli
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/**
 * Precise/rounded HRV baseline lookups for one target [LocalDate] at a time.
 *
 * Resolution order: persisted per-day geometric mu -> user override -> live recompute via
 * [BaselineComputer.computeHrvBaseline]. WP-11: the live-recompute fallback is bounded to the
 * target date's scoring-zone day end (never the device's system zone/clock), so a lookup for a
 * past date never reads sessions dated after it.
 */
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
