package app.readylytics.health.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "workout_route_points",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["workoutId", "timestampMs"])]
)
data class WorkoutRoutePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val timestampMs: Long,
    val horizontalAccuracy: Float?,
    val verticalAccuracy: Float?
)
