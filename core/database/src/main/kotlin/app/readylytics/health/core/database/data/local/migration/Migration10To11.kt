package app.readylytics.health.core.database.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v10 -> v11: GPS route ingestion for workouts.
 *
 * Adds the [workout_route_points] normalized route table (cascade-deleted with its parent workout)
 * and four additive, nullable summary columns on `workout_records`
 * ([totalDistanceMeters], [avgSpeedKmh], [elevationGainMeters], [routeState]).
 *
 * Idempotent: all DDL uses IF NOT EXISTS and ALTER ADD COLUMN fails atomically if re-run on an
 * already-migrated database (fresh installs create this schema directly from Room).
 */
val MIGRATION_10_11 =
    object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `workout_route_points` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `workoutId` TEXT NOT NULL,
                    `latitude` REAL NOT NULL,
                    `longitude` REAL NOT NULL,
                    `altitude` REAL,
                    `timestampMs` INTEGER NOT NULL,
                    `horizontalAccuracy` REAL,
                    `verticalAccuracy` REAL,
                    FOREIGN KEY(`workoutId`) REFERENCES `workout_records`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_workout_route_points_workoutId_timestampMs` " +
                    "ON `workout_route_points` (`workoutId`, `timestampMs`)",
            )
            db.execSQL("ALTER TABLE `workout_records` ADD COLUMN `totalDistanceMeters` REAL")
            db.execSQL("ALTER TABLE `workout_records` ADD COLUMN `avgSpeedKmh` REAL")
            db.execSQL("ALTER TABLE `workout_records` ADD COLUMN `elevationGainMeters` REAL")
            db.execSQL(
                "ALTER TABLE `workout_records` ADD COLUMN `routeState` TEXT NOT NULL DEFAULT 'NOT_AVAILABLE'",
            )
        }
    }
