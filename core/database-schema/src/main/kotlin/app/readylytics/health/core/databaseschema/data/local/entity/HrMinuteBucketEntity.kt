package app.readylytics.health.core.databaseschema.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// @Serializable is load-bearing: LocalBackupManager.writeJsonStreaming encodes this entity
// row-by-row, and json.encodeToString falls back to a runtime serializer lookup that throws
// for a non-@Serializable class. The loop body only runs when the table has rows, so a
// missing annotation here fails at backup time on real devices while every empty-table
// unit test passes. See LocalBackupSerializationRegressionTest.
@Serializable
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
    // R2-DB-004: percentile sketch (Room v14->v15). Nullable because rollup never reprocesses
    // already-rolled minutes -- buckets written before the v15 migration keep these `null`
    // forever. Task 4 (WarmTierReconstructor) branches its reconstruction on `p50Bpm != null`.
    val p5Bpm: Int? = null,
    val p25Bpm: Int? = null,
    val p50Bpm: Int? = null,
    val p75Bpm: Int? = null,
    val p95Bpm: Int? = null,
)
