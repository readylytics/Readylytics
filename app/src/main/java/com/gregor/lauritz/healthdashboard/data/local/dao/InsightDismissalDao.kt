package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gregor.lauritz.healthdashboard.data.local.entity.InsightDismissalEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface InsightDismissalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun dismiss(entity: InsightDismissalEntity)

    @Query("DELETE FROM insight_dismissals WHERE dateMidnightMs = :dateMidnightMs")
    suspend fun restoreAllForDate(dateMidnightMs: Long)

    @Query("SELECT * FROM insight_dismissals WHERE dateMidnightMs = :dateMidnightMs")
    fun _observeForDate(dateMidnightMs: Long): Flow<List<InsightDismissalEntity>>

    fun observeForDate(dateMidnightMs: Long): Flow<List<InsightDismissalEntity>> =
        _observeForDate(dateMidnightMs).distinctUntilChanged()
}
