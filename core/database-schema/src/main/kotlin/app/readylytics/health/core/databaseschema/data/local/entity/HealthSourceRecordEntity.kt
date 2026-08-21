package app.readylytics.health.core.databaseschema.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// @Serializable is load-bearing — see the note on HrMinuteBucketEntity. This table is
// populated by SourceRecordDao.getOrCreateSourceRef on every ingested Health Connect
// record, so it is non-empty for every real user and its absence broke backup for all
// of them while the test suite stayed green.
@Serializable
@Entity(
    tableName = "health_source_records",
    indices = [
        Index(value = ["sourceRecordId"], unique = true),
    ],
)
data class HealthSourceRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val sourceRecordId: String,
    val recordType: String,
    val createdAtMs: Long,
)
