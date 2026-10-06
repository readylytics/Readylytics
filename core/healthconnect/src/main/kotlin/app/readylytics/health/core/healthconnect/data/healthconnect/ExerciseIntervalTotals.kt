package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.ReadRetryScope
import java.time.Instant

internal data class ExerciseIntervalTotals(
    val distance: Map<String, Double?>,
    val elevation: Map<String, Double?>,
    val completedTypes: Set<String>,
)

/** Completion describes whole paged reads, including empty windows, never an accepted prefix. */
internal suspend fun IntervalTotalsReader.readSessionTotals(
    sessions: List<ExerciseSessionRecord>,
    from: Instant,
    to: Instant,
    retryScope: ReadRetryScope?,
): ExerciseIntervalTotals {
    val distance = mutableMapOf<String, Double?>()
    val distanceOutcome = readDistanceTotalsPaged(from, to, retryScope) { page ->
        foldPageIntoSessionTotals(sessions, page, distance)
    }
    val elevation = mutableMapOf<String, Double?>()
    val elevationOutcome = readElevationTotalsPaged(from, to, retryScope) { page ->
        foldPageIntoSessionTotals(sessions, page, elevation)
    }
    val completed = buildSet {
        if (distanceOutcome is ReadOutcome.Available) add("DISTANCE") else distance.clear()
        if (elevationOutcome is ReadOutcome.Available) add("ELEVATION_GAINED") else elevation.clear()
    }
    return ExerciseIntervalTotals(distance, elevation, completed)
}
