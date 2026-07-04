package app.readylytics.health.domain.sync

import app.readylytics.health.domain.model.HealthDataType
import java.time.LocalDate
import java.time.ZoneId

interface SelectedSourcePruner {
    suspend fun prune(
        start: LocalDate,
        endInclusive: LocalDate,
        selections: Map<HealthDataType, String?>,
        zoneId: ZoneId,
    )
}
