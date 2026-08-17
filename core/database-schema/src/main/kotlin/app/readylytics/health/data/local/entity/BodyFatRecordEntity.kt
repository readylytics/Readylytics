package app.readylytics.health.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "body_fat_records",
    indices = [
        Index(value = ["timestampMs"]),
        Index(value = ["timestampMs", "deviceName"]),
    ],
)
data class BodyFatRecordEntity(
    @PrimaryKey val id: String,
    val timestampMs: Long,
    val bodyFatPercent: Float,
    val deviceName: String? = null,
)
