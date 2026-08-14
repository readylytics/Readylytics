package app.readylytics.health.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.HrvRecordEntity

/**
 * Test-only Room schema for Phase 3 (Option C) conflict-strategy research.
 *
 * Declares the same production entities (`HeartRateRecordEntity` / `HrvRecordEntity`) so the
 * generated tables and unique `(sourceRecordId, timestampMs)` index exactly match the production
 * `HealthDatabase` v9 schema. The prototype DAO exposes both the current `@Insert(REPLACE)`
 * baseline and the Room `@Upsert` candidate; the conflict-targeted `INSERT ... ON CONFLICT
 * (sourceRecordId, timestampMs) DO UPDATE` SQL is exercised via raw `SupportSQLiteDatabase`
 * statements against the same tables (Room's @Query parser does not accept UPSERT syntax).
 */
@Database(
    entities = [HeartRateRecordEntity::class, HrvRecordEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class UpsertPrototypeDatabase : RoomDatabase() {
    abstract fun upsertPrototypeDao(): UpsertPrototypeDao
}
