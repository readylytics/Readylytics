package app.readylytics.health.domain.scoring.sleep

import app.readylytics.health.domain.model.SleepSessionEntity
import app.readylytics.health.domain.repository.ScoringHistoryRepository
import app.readylytics.health.domain.util.mean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrentNightHrvResolver
    @Inject
    constructor(
        private val scoringHistoryRepository: ScoringHistoryRepository,
    ) {
        data class HrvResult(
            val samples: List<Float>,
            val mean: Float,
        )

        suspend fun resolve(
            session: SleepSessionEntity,
            currentSessionIds: Set<String> = setOf(session.id),
        ): HrvResult {
            var samples =
                if (currentSessionIds.isNotEmpty()) {
                    currentSessionIds
                        .toList()
                        .sorted()
                        .flatMap { sessionId ->
                            scoringHistoryRepository.getSleepRmssdForSession(sessionId)
                        }
                } else {
                    scoringHistoryRepository.getSleepRmssdForSession(session.id)
                }
            if (samples.isEmpty()) {
                samples = scoringHistoryRepository.getRmssdInTimeRange(session.startTime, session.endTime)
            }
            val mean = if (samples.isNotEmpty()) samples.mean() else 0f
            return HrvResult(samples, mean)
        }
    }
