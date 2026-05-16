package com.gregor.lauritz.healthdashboard.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "workout_records",
    indices = [
        Index(value = ["startTime"]),
    ],
)
data class WorkoutRecordEntity(
    @PrimaryKey val id: String,
    val startTime: Long,
    val endTime: Long,
    val exerciseType: String,
    val durationMinutes: Int,
    val zone1Minutes: Float,
    val zone2Minutes: Float,
    val zone3Minutes: Float,
    val zone4Minutes: Float,
    val zone5Minutes: Float,
    val trimp: Float,
    val avgHr: Float,
    val deviceName: String? = null,
) {
    companion object {
        fun fromJson(json: org.json.JSONObject): WorkoutRecordEntity =
            WorkoutRecordEntity(
                id = json.getString("id"),
                startTime = json.getLong("startTime"),
                endTime = json.getLong("endTime"),
                exerciseType = json.getString("exerciseType"),
                durationMinutes = json.getInt("durationMinutes"),
                zone1Minutes = json.getDouble("zone1Minutes").toFloat(),
                zone2Minutes = json.getDouble("zone2Minutes").toFloat(),
                zone3Minutes = json.getDouble("zone3Minutes").toFloat(),
                zone4Minutes = json.getDouble("zone4Minutes").toFloat(),
                zone5Minutes = json.getDouble("zone5Minutes").toFloat(),
                trimp = json.getDouble("trimp").toFloat(),
                avgHr = json.getDouble("avgHr").toFloat(),
                deviceName =
                    if (json.has("deviceName") &&
                        !json.isNull("deviceName")
                    ) {
                        json.getString("deviceName")
                    } else {
                        null
                    },
            )
    }
}
