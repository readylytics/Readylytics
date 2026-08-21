package app.readylytics.health.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity

/**
 * Test-only Room schema for Phase 3 (Option C) conflict-strategy research.
 *
 * Declares the same production entities (`HeartRateRecordEntity` / `HrvRecordEntity`) so the
 * generated tables and unique `(sourceRecordRef, timestampMs)` index exactly match the production
 * `HealthDatabase` v10 schema (including the `health_source_records` parent table their FKs
 * reference). The prototype DAO exposes the `@Insert(REPLACE)` baseline, the Room
 * `@Upsert` candidate, and the conflict-targeted `@Query` UPSERT query to compare behavior across engines.
 */
@Database(
    entities = [
        HeartRateRecordEntity::class,
        HrvRecordEntity::class,
        HealthSourceRecordEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class UpsertPrototypeDatabase : RoomDatabase() {
    abstract fun upsertPrototypeDao(): UpsertPrototypeDao
}
