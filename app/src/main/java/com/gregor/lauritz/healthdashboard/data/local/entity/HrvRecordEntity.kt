package com.gregor.lauritz.healthdashboard.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "hrv_records",
    indices = [
        Index(value = ["timestampMs"]),
        Index(value = ["recordType", "timestampMs"]),
        Index(value = ["sessionId"]),
    ],
)
data class HrvRecordEntity(
    @PrimaryKey val id: String,
    val timestampMs: Long,
    val rmssdMs: Float,
    val recordType: String,
    val sessionId: String? = null,
    val deviceName: String? = null,
) {
    companion object {
        fun fromJson(json: org.json.JSONObject): HrvRecordEntity =
            HrvRecordEntity(
                id = json.getString("id"),
                timestampMs = json.getLong("timestampMs"),
                rmssdMs = json.getDouble("rmssdMs").toFloat(),
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
