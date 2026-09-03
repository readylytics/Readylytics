package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.model.domain.repository.ScoringRepository
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live residual fatigue for the dashboard card, decayed through the current instant rather than
 * the persisted end-of-day snapshot (`DailySummary.residualFatigue`). Only meaningful for today: a
 * past day already ended, so its persisted snapshot is the correct final value — those days report
 * [LiveResidualFatigue.NotApplicable] so the presentation falls back to it.
 *
 * For today, a null from the repository is *not* a fallback signal: it means a retained workout was
 * never backfilled, so the value is unknown rather than low. That maps to
 * [LiveResidualFatigue.Unavailable], which renders NO_DATA.
 */
@Singleton
class GetCurrentResidualFatigueUseCase
    @Inject
    constructor(
        private val scoringRepository: ScoringRepository,
        private val clock: Clock,
    ) {
        suspend operator fun invoke(
            selectedDate: LocalDate,
            zoneId: ZoneId,
        ): LiveResidualFatigue {
            if (selectedDate != LocalDate.now(clock.withZone(zoneId))) return LiveResidualFatigue.NotApplicable
            return scoringRepository
                .computeCurrentResidualFatigue(clock.millis())
                ?.let(LiveResidualFatigue::Value)
                ?: LiveResidualFatigue.Unavailable
        }
    }
