package app.readylytics.health.core.model.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.repository.ReadOutcome

data class CompleteTypeScan(
    val type: HealthDataType,
    val windowStartMs: Long,
    val windowEndExclusiveMs: Long,
    val sourceSelectionId: String,
    val ids: Set<String>,
)

fun ReadOutcome<Unit>.isComplete(): Boolean = this is ReadOutcome.Available
