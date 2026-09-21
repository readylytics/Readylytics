package app.readylytics.health.core.databaseschema.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "hr_source_minute_contributions",
    primaryKeys = ["sourceRecordRef", "bucketStartMs", "generation"],
    foreignKeys = [
        ForeignKey(
            entity = HealthSourceRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceRecordRef"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["bucketStartMs", "generation"]),
    ],
)
data class HrSourceMinuteContributionEntity(
    val sourceRecordRef: Long,
    val bucketStartMs: Long,
    val generation: Long,
    val firstSampleMs: Long,
    val lastSampleMs: Long,
    val deviceName: String,
    val bpmHistogram: String,
)
