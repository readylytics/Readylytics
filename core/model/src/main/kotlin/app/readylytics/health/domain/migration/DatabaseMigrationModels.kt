package app.readylytics.health.domain.migration

enum class V7MigrationPhase {
    PREFLIGHT,
    UPGRADE_5_TO_6,
    CREATE_SHADOW_TABLES,
    COPY_HEART_RATE,
    COPY_HRV,
    INDEX_HEART_RATE_TIMESTAMP,
    INDEX_HEART_RATE_SESSION,
    INDEX_HEART_RATE_TYPE_TIME,
    INDEX_HRV_TIMESTAMP,
    INDEX_HRV_TYPE_TIME,
    INDEX_HRV_SESSION,
    VALIDATE,
    SWAP,
    COMPLETE,
}

sealed interface DatabaseReadiness {
    data object Ready : DatabaseReadiness

    data class MigrationRequired(
        val fromVersion: Int,
    ) : DatabaseReadiness

    data class InsufficientSpace(
        val requiredBytes: Long,
        val availableBytes: Long,
    ) : DatabaseReadiness

    data class Failed(
        val message: String,
    ) : DatabaseReadiness

    data object KeyCorrupted : DatabaseReadiness
}

fun interface DatabaseReadinessInspector {
    fun inspect(): DatabaseReadiness
}

data class DatabaseMigrationProgress(
    val phase: V7MigrationPhase,
    val copiedRows: Long,
    val totalRows: Long,
)

fun DatabaseMigrationProgress.fraction(): Float {
    if (phase == V7MigrationPhase.COMPLETE) return 1f

    val phaseCount = V7MigrationPhase.entries.size.toFloat()
    val rowFraction =
        if (totalRows > 0L) {
            copiedRows.coerceIn(0L, totalRows).toFloat() / totalRows.toFloat()
        } else {
            0f
        }
    return ((phase.ordinal + rowFraction) / phaseCount).coerceIn(0f, 1f)
}

sealed interface V7MigrationResult {
    data object Complete : V7MigrationResult

    data class InsufficientSpace(
        val requiredBytes: Long,
        val availableBytes: Long,
    ) : V7MigrationResult

    data class Failed(
        val reason: String,
    ) : V7MigrationResult
}
