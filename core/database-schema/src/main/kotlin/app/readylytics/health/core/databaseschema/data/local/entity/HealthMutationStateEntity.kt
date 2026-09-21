package app.readylytics.health.core.databaseschema.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "health_mutation_state")
data class HealthMutationStateEntity(
    @PrimaryKey
    val id: Int = 1,
    val sourceGeneration: Long = 0,
    val maintenanceOperationId: String? = null,
    val maintenancePhase: String? = null,
    val backfillAfterSourceRef: Long = 0,
)
