package com.gregor.lauritz.healthdashboard.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sleep_sessions",
    indices = [
        Index(value = ["startTime"]),
        Index(value = ["endTime"]),
    ],
)
data class SleepSessionEntity(
    @PrimaryKey val id: String,
    val startTime: Long,
    val endTime: Long,
    val durationMinutes: Int,
    val efficiency: Float,
    val deepSleepMinutes: Int,
    val remSleepMinutes: Int,
    val lightSleepMinutes: Int,
    val awakeMinutes: Int,
    val sleepScore: Float? = null,
    val startZoneOffsetSeconds: Int? = null,
    val endZoneOffsetSeconds: Int? = null,
    val deviceName: String? = null,
) {
    companion object {
        fun fromJson(json: org.json.JSONObject): SleepSessionEntity =
            SleepSessionEntity(
                id = json.getString("id"),
                startTime = json.getLong("startTime"),
                endTime = json.getLong("endTime"),
                durationMinutes = json.getInt("durationMinutes"),
                efficiency = json.getDouble("efficiency").toFloat(),
                deepSleepMinutes = json.getInt("deepSleepMinutes"),
                remSleepMinutes = json.getInt("remSleepMinutes"),
                lightSleepMinutes = json.getInt("lightSleepMinutes"),
                awakeMinutes = json.getInt("awakeMinutes"),
                sleepScore =
                    if (json.has("sleepScore") &&
                        !json.isNull("sleepScore")
                    ) {
                        json.getDouble("sleepScore").toFloat()
                    } else {
                        null
                    },
                startZoneOffsetSeconds =
                    if (json.has("startZoneOffsetSeconds") &&
                        !json.isNull("startZoneOffsetSeconds")
                    ) {
                        json.getInt("startZoneOffsetSeconds")
                    } else {
                        null
                    },
                endZoneOffsetSeconds =
                    if (json.has("endZoneOffsetSeconds") &&
                        !json.isNull("endZoneOffsetSeconds")
                    ) {
                        json.getInt("endZoneOffsetSeconds")
                    } else {
                        null
                    },
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
