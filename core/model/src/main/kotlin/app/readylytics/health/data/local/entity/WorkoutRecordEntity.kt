package app.readylytics.health.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
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
    // SCORE-001: the user-selected TRIMP model's value (Banister/Cheng/iTRIMP), as opposed to
    // [trimp] which is the zone-weighted (Edwards-style) value. Nullable additive column (v5->v6);
    // lazily backfilled by the next walk-forward recompute -- read paths use COALESCE(modelTrimp,
    // trimp) until every row has been touched. See WorkoutDao.getTrimpPoints.
    val modelTrimp: Float? = null,
)
