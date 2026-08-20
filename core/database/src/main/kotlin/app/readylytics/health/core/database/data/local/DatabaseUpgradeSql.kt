package app.readylytics.health.core.database.data.local

object DatabaseUpgradeSql {
    val V5_TO_V6 =
        listOf(
            "ALTER TABLE `workout_records` ADD COLUMN `modelTrimp` REAL DEFAULT NULL",
            """
            CREATE TABLE IF NOT EXISTS `step_records` (
                `id` TEXT NOT NULL,
                `startTime` INTEGER NOT NULL,
                `endTime` INTEGER NOT NULL,
                `count` INTEGER NOT NULL,
                `deviceName` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
            "DROP INDEX IF EXISTS `index_daily_summaries_dateMidnightMs`",
        )
}
