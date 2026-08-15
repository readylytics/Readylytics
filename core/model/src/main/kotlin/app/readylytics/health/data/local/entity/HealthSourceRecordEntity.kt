package app.readylytics.health.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "health_source_records",
    indices = [
        Index(value = ["sourceRecordId"], unique = true),
    ],
)
data class HealthSourceRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val sourceRecordId: String,
    val recordType: String,
    val createdAtMs: Long,
)
