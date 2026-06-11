package app.readylytics.health.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "blood_pressure_records",
    indices = [
        Index(value = ["timestampMs"]),
        Index(value = ["timestampMs", "deviceName"]),
    ],
)
data class BloodPressureRecordEntity(
    @PrimaryKey val id: String,
    val timestampMs: Long,
    val systolicMmHg: Int,
    val diastolicMmHg: Int,
    val deviceName: String? = null,
)
