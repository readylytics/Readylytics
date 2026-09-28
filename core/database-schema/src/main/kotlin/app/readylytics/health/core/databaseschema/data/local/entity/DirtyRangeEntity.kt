package app.readylytics.health.core.databaseschema.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dirty_ranges",
    indices = [
        Index(value = ["sourceGeneration", "id"]),
    ],
)
data class DirtyRangeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sourceGeneration: Long,
    val startEpochDay: Long,
    val endEpochDayInclusive: Long,
    val nextEpochDay: Long,
    val reason: String,
    /** Diagnostic metadata only; publication is fenced by source generation and the captured ticket cursor. */
    val scoringSnapshotId: String,
)
