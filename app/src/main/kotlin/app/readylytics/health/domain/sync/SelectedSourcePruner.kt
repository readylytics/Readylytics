package app.readylytics.health.domain.sync

import app.readylytics.health.domain.model.HealthDataType
import java.time.LocalDate

interface SelectedSourcePruner {
    suspend fun prune(
        start: LocalDate,
        endInclusive: LocalDate,
        selections: Map<HealthDataType, String?>,
    )
}
