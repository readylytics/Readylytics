package app.readylytics.health.core.database.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// R2-DB-004: additive-only -- five cheap `ALTER TABLE ADD COLUMN` statements, no table rewrite,
// unlike the PK-changing migration Phase 2's WP-14 will need for this same table. Existing rows
// keep their minBpm/maxBpm/avgBpm/sampleCount untouched and read back the new percentile columns
// as NULL (rollup never reprocesses already-rolled minutes, so pre-migration buckets stay NULL
// forever -- see Migration14To15Test).
val MIGRATION_14_15 =
    object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE hr_minute_buckets ADD COLUMN p5Bpm INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE hr_minute_buckets ADD COLUMN p25Bpm INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE hr_minute_buckets ADD COLUMN p50Bpm INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE hr_minute_buckets ADD COLUMN p75Bpm INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE hr_minute_buckets ADD COLUMN p95Bpm INTEGER DEFAULT NULL")
        }
    }
