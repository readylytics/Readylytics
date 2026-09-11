package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.readylytics.health.core.databaseschema.data.local.entity.HealthMutationStateEntity

@Dao
interface HealthMutationStateDao {
    @Query("SELECT * FROM health_mutation_state WHERE id = 1")
    suspend fun get(): HealthMutationStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: HealthMutationStateEntity)

    @Query("UPDATE health_mutation_state SET backfillAfterSourceRef = :afterRef WHERE id = 1")
    suspend fun updateBackfillAfterSourceRef(afterRef: Long): Int

    @Query("UPDATE health_mutation_state SET sourceGeneration = :generation WHERE id = 1")
    suspend fun updateSourceGeneration(generation: Long): Int

    @Transaction
    suspend fun getOrCreate(): HealthMutationStateEntity {
        val existing = get()
        if (existing != null) return existing
        upsert(HealthMutationStateEntity(id = 1, sourceGeneration = 0, backfillAfterSourceRef = 0))
        return get() ?: HealthMutationStateEntity(id = 1, sourceGeneration = 0, backfillAfterSourceRef = 0)
    }
}
