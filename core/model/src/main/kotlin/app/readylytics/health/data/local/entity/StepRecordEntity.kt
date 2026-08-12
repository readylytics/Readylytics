package app.readylytics.health.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Raw per-record steps rows, keyed by stable Health Connect record id. Persisted purely so a
 * later `DeletionChange` for steps can resolve the deleted record's own `(startTime, endTime)`
 * and mark the right days affected (HC-005) -- daily step totals remain sourced from
 * [app.readylytics.health.domain.sync.StepCountFetcher]'s aggregate/device-filtered reads, never
 * from this table.
 */
@Serializable
@Entity(tableName = "step_records")
data class StepRecordEntity(
    @PrimaryKey val id: String,
    val startTime: Long,
    val endTime: Long,
    val count: Long,
    val deviceName: String? = null,
)
