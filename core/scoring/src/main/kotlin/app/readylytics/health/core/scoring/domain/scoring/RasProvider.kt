package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.RasProvider

import app.readylytics.health.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.util.toMidnightEpochMilli
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RasProvider
    @Inject
    constructor(
        private val scoringHistoryRepository: ScoringHistoryRepository,
    ) {
        suspend fun getPreciseRas(date: LocalDate): Double? {
            val dateMs = date.toMidnightEpochMilli()
            return scoringHistoryRepository.getPreciseRas(dateMs)
        }

        suspend fun getRoundedRas(date: LocalDate): Int? {
            val dateMs = date.toMidnightEpochMilli()
            return scoringHistoryRepository.getRoundedRas(dateMs)
        }
    }
