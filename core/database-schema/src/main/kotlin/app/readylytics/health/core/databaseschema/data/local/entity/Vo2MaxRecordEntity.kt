package app.readylytics.health.core.databaseschema.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "vo2_max_records",
    indices = [Index(value = ["timestampMs"])],
)
data class Vo2MaxRecordEntity(
    @PrimaryKey val id: String,
    val timestampMs: Long,
    val vo2Max: Float,
    val measurementMethod: Int?,
    val deviceName: String,
)
