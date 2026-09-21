package app.readylytics.health.core.model.domain.backup

enum class BackupOperationPhase {
    IDLE,
    PREPARING,
    STAGED,
    PUBLISHING,
    VERIFIED,
    CREDENTIAL_COMMITTED,
    CLEANUP,
    COMPLETE,
}

data class ArchiveRotationEntry(
    val originalLocation: String,
    val stagedLocation: String? = null,
    val publishedLocation: String? = null,
    val verified: Boolean = false,
)

data class BackupOperationState(
    val operationId: String? = null,
    val phase: BackupOperationPhase = BackupOperationPhase.IDLE,
    val completedArchives: Int = 0,
    val totalArchives: Int = 0,
)
