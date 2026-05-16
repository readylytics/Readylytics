package com.gregor.lauritz.healthdashboard.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "heart_rate_records",
    indices = [
        Index(value = ["timestampMs"]),
        Index(value = ["recordType", "timestampMs"]),
        Index(value = ["sessionId", "recordType", "beatsPerMinute"]),
    ],
)
data class HeartRateRecordEntity(
    @PrimaryKey val id: String,
    val timestampMs: Long,
    val beatsPerMinute: Int,
    val recordType: String,
    val sessionId: String? = null,
    val deviceName: String? = null,
) {
    companion object {
        fun fromJson(json: org.json.JSONObject): HeartRateRecordEntity =
            HeartRateRecordEntity(
                id = json.getString("id"),
                timestampMs = json.getLong("timestampMs"),
                beatsPerMinute = json.getInt("beatsPerMinute"),
                recordType = json.getString("recordType"),
                sessionId =
                    if (json.has("sessionId") &&
                        !json.isNull("sessionId")
                    ) {
                        json.getString("sessionId")
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
