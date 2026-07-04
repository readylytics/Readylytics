package app.readylytics.health.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.readylytics.health.data.local.entity.AuditEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditEventDao {
    @Insert
    suspend fun insert(event: AuditEventEntity)

    @Query("SELECT * FROM audit_events ORDER BY occurredAtEpochMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditEventEntity>>
}
