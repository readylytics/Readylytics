package app.readylytics.health.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "oxygen_saturation_records",
    indices = [
        Index(value = ["timestampMs"]),
        Index(value = ["timestampMs", "deviceName"]),
    ],
)
data class OxygenSaturationRecordEntity(
    @PrimaryKey val id: String,
    val timestampMs: Long,
    val percentage: Float,
    val deviceName: String? = null,
)
