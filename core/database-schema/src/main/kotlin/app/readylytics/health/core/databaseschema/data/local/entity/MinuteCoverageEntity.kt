package app.readylytics.health.core.databaseschema.data.local.entity

import androidx.room.Entity
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "minute_coverage", primaryKeys = ["bucketStartMs"])
data class MinuteCoverageEntity(
    val bucketStartMs: Long,
    val visibleGeneration: Long,
    val tier: String, // "HOT", "WARM", "LEGACY_WARM"
    val quality: String, // "SOURCE_BACKED", "LEGACY_UNKNOWN"
    val sourceSelectionId: String? = null,
)
