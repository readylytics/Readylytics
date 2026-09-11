package app.readylytics.health.core.model.domain.backup

object BackupInventoryPolicy {
    private val core = setOf(
        "sleepSessions",
        "heartRateRecords",
        "hrvRecords",
        "workouts",
        "dailySummaries",
    )
    private val vitals = setOf(
        "weightRecords",
        "bodyFatRecords",
        "bloodPressureRecords",
        "oxygenSaturationRecords",
        "bodyTemperatureRecords",
        "stepRecords",
    )

    fun requiredTables(version: Int): Set<String> = buildSet {
        require(version >= 5) {
            "Unsupported backup schema version $version; minimum supported is 5"
        }
        addAll(core)
        if (version >= 10) {
            addAll(vitals)
            add("healthSourceRecords")
            add("hrMinuteBuckets")
        }
        if (version >= 11) add("workoutRoutePoints")
        if (version >= 19) add("vo2MaxRecords")
    }

    fun validateInventory(
        required: Set<String>,
        declared: Map<String, Long>,
        observed: Map<String, Long>,
    ) {
        require(observed.keys.containsAll(required)) { "BACKUP_REQUIRED_TABLE_MISSING" }
        require(declared.keys.containsAll(required)) { "BACKUP_REQUIRED_COUNT_MISSING" }
        require(declared.values.all { it >= 0L }) { "BACKUP_COUNT_INVALID" }
        require(observed.all { (table, count) -> declared[table] == count }) { "BACKUP_COUNT_MISMATCH" }
        require(declared.keys.all { it in observed }) {
            "BACKUP_DECLARED_TABLE_MISSING"
        }
    }
}
