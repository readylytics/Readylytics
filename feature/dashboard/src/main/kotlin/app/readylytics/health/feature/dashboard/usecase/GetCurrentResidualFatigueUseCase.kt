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
 * past day already ended, so its persisted snapshot is the correct final value. Returns null for
 * any other day so [DashboardMetricPresentationFactory] falls back to that persisted value.
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
        ): Float? {
            if (selectedDate != LocalDate.now(clock.withZone(zoneId))) return null
            return scoringRepository.computeCurrentResidualFatigue(clock.millis())
        }
    }
