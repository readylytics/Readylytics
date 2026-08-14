package app.readylytics.health.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "hr_minute_buckets",
    primaryKeys = ["bucketStartMs", "recordType", "sessionId"],
    indices = [
        Index(value = ["sessionId", "recordType"]),
        Index(value = ["bucketStartMs", "bucketEndMs"]),
    ],
)
data class HrMinuteBucketEntity(
    val bucketStartMs: Long,
    val bucketEndMs: Long,
    val minBpm: Int,
    val maxBpm: Int,
    val avgBpm: Double,
    val sampleCount: Int,
    val recordType: String,
    val sessionId: String = "",
    val deviceName: String? = null,
)
