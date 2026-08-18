package app.readylytics.health.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "body_temperature_records",
    indices = [
        Index(value = ["timestampMs"]),
        Index(value = ["timestampMs", "deviceName"]),
    ],
)
data class BodyTemperatureRecordEntity(
    @PrimaryKey val id: String,
    val timestampMs: Long,
    val celsius: Float,
    val deviceName: String? = null,
)
